import asyncio
import json
import sys
import importlib.util
import pandas as pd
import websockets
import os
import types
import logging
import joblib
import warnings
from datetime import datetime

# Ignorar advertencias de Pandas/Scikit-learn sobre nombres de características (Feature names)
warnings.filterwarnings("ignore", category=UserWarning)

# =========================
# CONFIGURACIÓN DE LOGS
# =========================
current_dir = os.path.dirname(os.path.abspath(__file__))
project_root = os.path.dirname(current_dir)
log_dir = os.path.join(project_root, "logs")
os.makedirs(log_dir, exist_ok=True)

log_file = os.path.join(log_dir, "engine_ai_rt.log")

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[
        logging.FileHandler(log_file, encoding='utf-8')
    ]
)

if sys.platform == "win32":
    if "posix" not in sys.modules:
        sys.modules["posix"] = types.ModuleType("posix")

if project_root not in sys.path:
    sys.path.append(project_root)

from strategies.BaseStrategy import BaseStrategy

# =========================
# CARGA DE ESTRATEGIA (Para calcular indicadores)
# =========================
def load_strategy(path, capital=1000, risk_per_trade=0.02):
    try:
        logging.info(f"Cargando estrategia generadora de indicadores desde: {path}")
        spec = importlib.util.spec_from_file_location("user_strategy", path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)

        for obj in module.__dict__.values():
            if isinstance(obj, type) and issubclass(obj, BaseStrategy) and obj is not BaseStrategy:
                return obj(capital=capital, risk_per_trade=risk_per_trade)
        raise Exception("No se encontró una clase válida en el archivo.")
    except Exception as e:
        logging.error(f"Error crítico cargando estrategia: {str(e)}", exc_info=True)
        sys.exit(1)

# =========================
# CARGA DE MODELO IA UNIVERSAL
# =========================
def load_model(model_name, timeframe, symbol):
    """
    Carga dinámicamente diferentes tipos de modelos basados en su extensión y librería.
    Soporta Scikit-Learn, XGBoost, LightGBM (.pkl) y TensorFlow/Keras (.h5 / .keras).
    """
    models_dir = os.path.join(project_root, 'models')
    base_filename = f"{model_name}_{timeframe}_{symbol}"
    
    # 1. Buscar modelos estándar (Scikit-Learn, XGBoost, LightGBM)
    pkl_path = os.path.join(models_dir, f"{base_filename}.pkl")
    if os.path.exists(pkl_path):
        logging.info(f"Cargando modelo clásico (PKL): {pkl_path}")
        return joblib.load(pkl_path), "ml_standard"

    # 2. Buscar modelos de Deep Learning (TensorFlow / Keras)
    keras_path = os.path.join(models_dir, f"{base_filename}.keras")
    h5_path = os.path.join(models_dir, f"{base_filename}.h5")
    
    dl_path = keras_path if os.path.exists(keras_path) else h5_path if os.path.exists(h5_path) else None
    
    if dl_path:
        logging.info(f"Cargando modelo de Red Neuronal (Keras/TF): {dl_path}")
        from tensorflow.keras.models import load_model as load_keras_model
        return load_keras_model(dl_path), "deep_learning"

    raise FileNotFoundError(f"No se encontró ningún modelo para {base_filename} (.pkl, .keras, .h5)")

# =========================
# LOOP POR SÍMBOLO
# =========================
async def run_symbol(symbol, timeframe, strategy_path, model_name, capital, risk_per_trade, is_real, max_candles=100):
    clean_symbol = symbol.lower().replace("/", "")
    url = f"wss://stream.binance.com:9443/ws/{clean_symbol}@kline_{timeframe}"

    logging.info(f"Iniciando AI Stream para {symbol} en {timeframe}")
    
    strategy = load_strategy(strategy_path, capital, risk_per_trade)
    
    # 🔥 AHORA RECIBIMOS TAMBIÉN EL TIPO DE MODELO
    model, model_type = load_model(model_name, timeframe, symbol)
    
    df = pd.DataFrame(columns=["timestamp", "close", "open", "high", "low", "volume"])
    last_candle_close_time = 0

    while True:
        try:
            async with websockets.connect(url) as ws:
                logging.info(f"Conectado a WebSocket de Binance para {symbol}")
                async for msg in ws:
                    data = json.loads(msg)
                    k = data["k"]
                    is_candle_closed = k["x"]

                    if not is_candle_closed:
                        continue

                    if k["T"] == last_candle_close_time:
                        continue
                    last_candle_close_time = k["T"]

                    new_row = pd.DataFrame([{
                        "timestamp": int(k["t"]),
                        "open": float(k["o"]),
                        "high": float(k["h"]),
                        "low": float(k["l"]),
                        "close": float(k["c"]),
                        "volume": float(k["v"])
                    }])
                    
                    df = pd.concat([df, new_row], ignore_index=True)
                    if len(df) > max_candles:
                        df = df.iloc[-max_candles:]

                    if len(df) < 50:
                        continue
                        
                    df_con_indicadores = strategy.populate_indicators(df.copy())
                    ultima_fila = df_con_indicadores.iloc[[-1]].copy()
                    
                    columnas_a_borrar = ['timestamp']
                    if 'Target' in ultima_fila.columns: columnas_a_borrar.append('Target')
                    if 'id' in ultima_fila.columns: columnas_a_borrar.append('id')
                    
                    ultima_fila.drop(columns=[col for col in columnas_a_borrar if col in ultima_fila.columns], inplace=True)

                    # ==========================================
                    # 3. HACEMOS LA PREDICCIÓN (Adaptada por tipo)
                    # ==========================================
                    action = None
                    
                    if model_type == "ml_standard":
                        # Para Random Forest, XGBoost, SVM, LightGBM...
                        # Muchos modelos modernos prefieren numpy arrays puros para evitar warnings de feature names
                        X_pred = ultima_fila.values 
                        
                        prediccion = model.predict(X_pred)[0]
                        try:
                            probabilidades = model.predict_proba(X_pred)[0]
                            prob_baja, prob_sube = probabilidades[0], probabilidades[1]
                            logging.info(f"[{symbol}] {model_name.upper()} Predice: {prediccion} (Sube: {prob_sube*100:.1f}%, Baja: {prob_baja*100:.1f}%)")
                            
                            if prediccion == 1 and prob_sube >= 0.65: action = "BUY"
                            elif prediccion == 0 and prob_baja >= 0.65: action = "SELL"
                                
                        except AttributeError:
                            # SVM lineales u otros modelos sin probabilidades
                            logging.info(f"[{symbol}] {model_name.upper()} Predice: {prediccion} (Binario)")
                            if prediccion == 1: action = "BUY"
                            elif prediccion == 0: action = "SELL"
                            
                    elif model_type == "deep_learning":
                        # Para Redes Neuronales (TensorFlow / Keras)
                        # Las RNN devuelven un porcentaje continuo entre 0 y 1 (Sigmoid)
                        prediccion_cruda = model.predict(ultima_fila.values, verbose=0)[0][0]
                        logging.info(f"[{symbol}] RED NEURONAL Predice: {prediccion_cruda*100:.2f}% confianza alcista")
                        
                        if prediccion_cruda >= 0.70: action = "BUY"
                        elif prediccion_cruda <= 0.30: action = "SELL"

                    # 4. ENVIAMOS LA SEÑAL A JAVA
                    if action:
                        signal = {
                            "symbol": symbol,
                            "action": action,
                            "timeframe": timeframe,
                            "price": float(new_row.iloc[0]["close"]),
                            "timestamp": int(new_row.iloc[0]["timestamp"]),
                            "is_real": is_real,
                            "source": f"AI_{model_name.upper()}"
                        }
                        print(json.dumps(signal), flush=True)
                        logging.info(f"🚀 SEÑAL {model_name.upper()} ENVIADA: {action} para {symbol} a {signal['price']}")

        except websockets.exceptions.ConnectionClosed:
            logging.warning(f"Conexión WS cerrada para {symbol}. Reconectando...")
            await asyncio.sleep(2)
        except Exception as e:
            logging.error(f"Error en loop WS de {symbol}: {str(e)}", exc_info=True)
            await asyncio.sleep(5) 

# =========================
# MAIN
# =========================
def main():
    logging.info("Motor Python AI RT iniciado. Esperando Payload de Java...")
    input_data = sys.stdin.read()
    if not input_data:
        logging.warning("No se recibió configuración de entrada. Cerrando.")
        sys.exit(0)

    try:
        payload = json.loads(input_data)
        logging.info(f"Configuración recibida: {payload}")
        
        model_name = payload.get("model_name")
        if not model_name:
            raise ValueError("El payload de Java no incluye 'model_name'. Imposible arrancar motor IA.")

        asyncio.run(run_all(
            symbols=payload["symbols"],
            timeframe=payload["timeframe"],
            strategy_path=payload["strategy_path"],
            model_name=model_name,
            capital=payload.get("capital", 1000),
            risk_per_trade=payload.get("risk_per_trade", 0.02),
            is_real=payload.get("is_real", False)
        ))
    except Exception as e:
        logging.critical(f"Fallo catastrófico en el motor IA: {str(e)}", exc_info=True)
        sys.exit(1)

async def run_all(symbols, timeframe, strategy_path, model_name, capital, risk_per_trade, is_real):
    tasks = [
        run_symbol(sym, timeframe, strategy_path, model_name, capital, risk_per_trade, is_real)
        for sym in symbols
    ]
    await asyncio.gather(*tasks)

if __name__ == "__main__":
    main()