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
from ipc_protocol import read_request_payload

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

# Logger a archivo (para debugging offline)
file_handler = logging.FileHandler(log_file, encoding='utf-8')
file_handler.setLevel(logging.INFO)
file_handler.setFormatter(logging.Formatter('%(asctime)s [%(levelname)s] %(message)s'))

# Logger a stdout (para que Java vea el progreso)
stream_handler = logging.StreamHandler(sys.stdout)
stream_handler.setLevel(logging.INFO)
stream_handler.setFormatter(logging.Formatter('[%(levelname)s] %(message)s'))

logging.basicConfig(
    level=logging.INFO,
    handlers=[file_handler, stream_handler]
)

# Evita el ruido de bajo nivel del cliente websocket en el log del bot.
logging.getLogger("websockets").setLevel(logging.WARNING)
logging.getLogger("asyncio").setLevel(logging.WARNING)

# Alias para logging.info → stdout
log = logging.getLogger(__name__)

if sys.platform == "win32":
    if "posix" not in sys.modules:
        sys.modules["posix"] = types.ModuleType("posix")

if project_root not in sys.path:
    sys.path.append(project_root)

from strategies.BaseStrategy import BaseStrategy

# Modo estrés para validar rápidamente el pipeline de señales en paper trading.
# Cuando está activo, también evalúa updates intravela (no sólo velas cerradas).
HIGH_ACTIVITY_TEST_MODE = True

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
    
    # 0. Buscar por nombre libre (el usuario ha renombrado el modelo)
    for ext in (".pkl", ".keras", ".h5"):
        bare_path = os.path.join(models_dir, f"{model_name}{ext}")
        if os.path.exists(bare_path):
            if ext == ".pkl":
                logging.info(f"Cargando modelo (nombre libre, PKL): {bare_path}")
                return joblib.load(bare_path), "ml_standard"
            else:
                logging.info(f"Cargando modelo (nombre libre, Keras/TF): {bare_path}")
                from tensorflow.keras.models import load_model as load_keras_model
                return load_keras_model(bare_path), "deep_learning"

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

    # 3. Buscar variantes con sufijo de estrategia: {base_filename}_{strategy}.ext
    for fname in os.listdir(models_dir):
        if fname.startswith(f"{base_filename}_") and fname.endswith((".pkl", ".keras", ".h5")):
            candidate = os.path.join(models_dir, fname)
            if fname.endswith(".pkl"):
                logging.info(f"Cargando modelo con sufijo de estrategia (PKL): {candidate}")
                return joblib.load(candidate), "ml_standard"
            else:
                logging.info(f"Cargando modelo con sufijo de estrategia (Keras/TF): {candidate}")
                from tensorflow.keras.models import load_model as load_keras_model
                return load_keras_model(candidate), "deep_learning"

    raise FileNotFoundError(
        f"No se encontró ningún modelo para '{model_name}' "
        f"(buscado como nombre libre, {base_filename}, y variantes con sufijo de estrategia). "
        f"Extensiones probadas: .pkl, .keras, .h5"
    )

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
    last_processed_event_id = 0

    while True:
        try:
            async with websockets.connect(url) as ws:
                logging.info(f"Conectado a WebSocket de Binance para {symbol}")
                async for msg in ws:
                    data = json.loads(msg)
                    k = data["k"]
                    is_candle_closed = k["x"]

                    if not is_candle_closed and not HIGH_ACTIVITY_TEST_MODE:
                        continue

                    # En modo normal deduplicamos por close time (T); en modo test intravela por event time (E).
                    event_id = int(data.get("E", 0)) if (HIGH_ACTIVITY_TEST_MODE and not is_candle_closed) else int(k["T"])
                    if event_id == last_processed_event_id:
                        continue
                    last_processed_event_id = event_id

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
                    feature_cols = strategy.get_feature_columns(df_con_indicadores)
                    if not feature_cols:
                        logging.warning("[%s] La estrategia no devolvió feature columns; se omite predicción.", symbol)
                        continue

                    missing_cols = [c for c in feature_cols if c not in df_con_indicadores.columns]
                    if missing_cols:
                        logging.warning("[%s] Faltan columnas de features: %s", symbol, missing_cols)
                        continue

                    # Debe replicar el mismo schema usado en entrenamiento (mismo orden y columnas).
                    ultima_fila = df_con_indicadores[feature_cols].iloc[[-1]].copy()

                    # Keras no acepta dtype=object: normalizamos entrada a float32 defensivamente.
                    ultima_fila = (
                        ultima_fila
                        .apply(pd.to_numeric, errors="coerce")
                        .replace([float("inf"), float("-inf")], float("nan"))
                        .fillna(0.0)
                        .astype("float32")
                    )
                    x_pred = ultima_fila.to_numpy(dtype="float32", copy=False)

                    # ==========================================
                    # 3. HACEMOS LA PREDICCIÓN (Adaptada por tipo)
                    # ==========================================
                    action = None
                    
                    if model_type == "ml_standard":
                        # Para Random Forest, XGBoost, SVM, LightGBM...
                        # Muchos modelos modernos prefieren numpy arrays puros para evitar warnings de feature names
                        prediccion = model.predict(x_pred)[0]
                        try:
                            probabilidades = model.predict_proba(x_pred)[0]
                            prob_baja, prob_sube = probabilidades[0], probabilidades[1]
                            logging.info(f"[{symbol}] {model_name.upper()} Predice: {prediccion} (Sube: {prob_sube*100:.1f}%, Baja: {prob_baja*100:.1f}%)")
                            
                            if prediccion == 1 and prob_sube >= 0.52: action = "BUY"
                            elif prediccion == 0 and prob_baja >= 0.52: action = "SELL"
                                
                        except AttributeError:
                            # SVM lineales u otros modelos sin probabilidades
                            logging.info(f"[{symbol}] {model_name.upper()} Predice: {prediccion} (Binario)")
                            if prediccion == 1: action = "BUY"
                            elif prediccion == 0: action = "SELL"
                            
                    elif model_type == "deep_learning":
                        # Para Redes Neuronales (TensorFlow / Keras)
                        # Las RNN devuelven un porcentaje continuo entre 0 y 1 (Sigmoid)
                        prediccion_cruda = model.predict(x_pred, verbose=0)[0][0]
                        logging.info(f"[{symbol}] RED NEURONAL Predice: {prediccion_cruda*100:.2f}% confianza alcista")
                        
                        if prediccion_cruda >= 0.55: action = "BUY"
                        elif prediccion_cruda <= 0.45: action = "SELL"

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
                        print(f"SIGNAL\t{json.dumps(signal)}", flush=True)
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
    try:
        payload = read_request_payload()
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