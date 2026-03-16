import sys
import json
import os
import pandas as pd
import joblib
import logging
from datetime import datetime

# =========================
# CONFIGURACIÓN DE LOGS
# =========================
current_dir = os.path.dirname(os.path.abspath(__file__))
project_root = os.path.dirname(current_dir)
log_dir = os.path.join(project_root, "logs")
os.makedirs(log_dir, exist_ok=True)

log_file = os.path.join(log_dir, "engine_predict.log")

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[logging.FileHandler(log_file, encoding='utf-8')]
)

def main():
    logging.info("=== Iniciando motor de Predicción (engine_predict.py) ===")
    
    try:
        # 1. Leer los datos enviados por Java
        input_data = sys.stdin.read()
        if not input_data:
            raise ValueError("No se recibieron datos desde Java.")

        payload = json.loads(input_data)
        
        model_type = payload.get("model_type", "random_forest")
        symbol = payload.get("symbol", "UNKNOWN")
        timeframe = payload.get("timeframe", "UNKNOWN")
        dataset = payload.get("dataset", [])

        if not dataset:
            raise ValueError("El dataset está vacío. Se necesita la vela actual y sus indicadores para predecir.")

        logging.info(f"Petición de predicción: {symbol} - {timeframe}")

        # 2. Cargar el modelo guardado previamente
        # Convención de naming: {model_type}_{timeframe}_{symbol}[_{strategy_name}].{pkl|keras|h5}
        # (misma convención que engine_train.py y engine_ai_rt.py)
        strategy_name = payload.get("strategy_name")  # opcional
        models_dir = os.path.join(project_root, 'models')

        base_filename = f"{model_type}_{timeframe}_{symbol}"
        if strategy_name:
            base_filename = f"{base_filename}_{strategy_name}"

        # Intentar extensiones en orden de prioridad
        model_path = None
        model_filename = None
        for ext in [".pkl", ".keras", ".h5"]:
            candidate = os.path.join(models_dir, base_filename + ext)
            if os.path.exists(candidate):
                model_path = candidate
                model_filename = base_filename + ext
                break

        if model_path is None:
            raise FileNotFoundError(
                f"No se encontró el modelo entrenado para '{base_filename}' "
                f"en: {models_dir}. ¡Entrénalo primero!"
            )

        if model_filename.endswith(".pkl"):
            model = joblib.load(model_path)
        else:
            from tensorflow.keras.models import load_model as load_keras_model
            model = load_keras_model(model_path)
        logging.info(f"Modelo {model_filename} cargado exitosamente.")

        # 3. Preparar los datos (Pandas DataFrame)
        df = pd.DataFrame(dataset)
        df.sort_values('timestamp', inplace=True)
        df.set_index('timestamp', inplace=True)

        # MUY IMPORTANTE: El modelo debe recibir EXACTAMENTE las mismas columnas con las que se entrenó.
        # Si enviaste un 'Target' o un 'id' que no usaste en entrenamiento, hay que quitarlo.
        if 'Target' in df.columns:
            df.drop(columns=['Target'], inplace=True)
        
        # 4. Tomar SOLO la última vela (la más reciente) para predecir el futuro
        ultima_vela = df.iloc[[-1]] 
        
        # 5. ¡Hacer la predicción!
        prediccion = model.predict(ultima_vela)[0] # Devuelve 1 (Sube) o 0 (Baja)
        
        # (Opcional pero genial) Ver la probabilidad de acierto de la IA (ej: 75% seguro de que sube)
        probabilidades = model.predict_proba(ultima_vela)[0]
        prob_baja = probabilidades[0]
        prob_sube = probabilidades[1]

        logging.info(f"Predicción realizada: {prediccion} (Prob Sube: {prob_sube*100:.1f}%, Prob Baja: {prob_baja*100:.1f}%)")

        # 6. Devolver el resultado a Java
        resultado = {
            "status": "success",
            "symbol": symbol,
            "timeframe": timeframe,
            "prediction": int(prediccion), # 1 = COMPRAR (Sube), 0 = VENDER/ESPERAR (Baja)
            "confidence_up": float(prob_sube),
            "confidence_down": float(prob_baja),
            "action": "BUY" if prediccion == 1 and prob_sube > 0.6 else "HOLD" # Compramos solo si está >60% seguro
        }

        print(json.dumps(resultado, indent=4))
        logging.info("=== Predicción enviada a Java ===")

    except Exception as e:
        logging.error(f"Fallo en la predicción: {str(e)}", exc_info=True)
        print(json.dumps({"status": "error", "message": str(e)}))
        sys.exit(1)

if __name__ == "__main__":
    main()