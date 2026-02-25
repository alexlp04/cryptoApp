import sys
import json
import os
import pandas as pd
import joblib
import logging
from datetime import datetime
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, precision_score, recall_score, f1_score

# =========================
# CONFIGURACIÓN DE LOGS
# =========================
# Creamos la carpeta models/logs en el directorio de trabajo actual
log_dir = os.path.join(os.getcwd(), "models", "logs")
os.makedirs(log_dir, exist_ok=True)

log_file = os.path.join(log_dir, f"engine_train_{datetime.now().strftime('%Y%m%d')}.log")

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[
        logging.FileHandler(log_file, encoding='utf-8'),
        # NO usamos StreamHandler para no ensuciar la salida estándar (Stdout) que lee Java
    ]
)

def main():
    logging.info("=== Iniciando proceso de entrenamiento (engine_train.py) ===")
    
    try:
        # 1. Leer los datos JSON que envía Java por la entrada estándar (Stdin)
        logging.info("Esperando datos JSON por entrada estándar (Stdin)...")
        input_data = sys.stdin.read()
        
        if not input_data:
            raise ValueError("No se recibieron datos desde Java.")

        payload = json.loads(input_data)
        
        model_type = payload.get("model_type", "random_forest")
        symbol = payload.get("symbol", "UNKNOWN")
        timeframe = payload.get("timeframe", "UNKNOWN")
        dataset = payload.get("dataset", [])

        logging.info(f"Configuración recibida: Symbol={symbol}, Timeframe={timeframe}, Model={model_type}. Registros recibidos: {len(dataset)}")

        if not dataset:
            raise ValueError("El dataset está vacío. Asegúrate de tener velas e indicadores calculados.")

        # 2. Cargar los datos en Pandas
        df = pd.DataFrame(dataset)
        
        # Asegurar orden cronológico estricto
        df.sort_values('timestamp', inplace=True)
        df.set_index('timestamp', inplace=True)

        # 3. CREACIÓN DEL TARGET (La Inteligencia Artificial predecirá esto)
        # 1 = El precio subirá (Tendencia alcista)
        # 0 = El precio bajará o se mantendrá
        df['Target'] = (df['close'].shift(-1) > df['close']).astype(int)
        
        # Eliminar la última fila y cualquier fila con NaNs derivados de los indicadores
        filas_antes = len(df)
        df.dropna(inplace=True)
        filas_despues = len(df)
        
        logging.info(f"Limpieza de datos: se eliminaron {filas_antes - filas_despues} filas por valores NaN o bordes. Filas útiles: {filas_despues}")

        if len(df) < 50:
            raise ValueError(f"No hay suficientes datos limpios para entrenar (solo {len(df)} registros válidos).")

        # 4. Separar las Características (X) de la Variable Objetivo (y)
        X = df.drop(columns=['Target'])
        y = df['Target']

        # 5. División Entrenamiento / Prueba (80% / 20%)
        split_idx = int(len(df) * 0.8)
        X_train, X_test = X.iloc[:split_idx], X.iloc[split_idx:]
        y_train, y_test = y.iloc[:split_idx], y.iloc[split_idx:]
        
        logging.info(f"División de datos completada: {len(X_train)} entrenamiento, {len(X_test)} prueba.")

        # 6. Inicializar y Entrenar el Modelo
        logging.info("Iniciando entrenamiento del modelo...")
        if model_type == 'random_forest':
            model = RandomForestClassifier(n_estimators=100, random_state=42, max_depth=10)
        else:
            model = RandomForestClassifier(n_estimators=100, random_state=42)

        model.fit(X_train, y_train)
        logging.info("Entrenamiento finalizado exitosamente.")

        # 7. Evaluación del Modelo
        y_pred = model.predict(X_test)
        
        acc = accuracy_score(y_test, y_pred)
        prec = precision_score(y_test, y_pred, zero_division=0)
        rec = recall_score(y_test, y_pred, zero_division=0)
        f1 = f1_score(y_test, y_pred, zero_division=0)
        
        logging.info(f"Métricas calculadas: Acc={acc:.4f}, Prec={prec:.4f}, Rec={rec:.4f}, F1={f1:.4f}")

        # 8. Guardar el modelo entrenado en disco (.pkl)
        models_dir = os.path.join(os.getcwd(), 'models')
        os.makedirs(models_dir, exist_ok=True)
        
        model_filename = f"{model_type}_{symbol}_{timeframe}.pkl"
        model_path = os.path.join(models_dir, model_filename)
        
        joblib.dump(model, model_path)
        logging.info(f"Modelo guardado físicamente en: {model_path}")

        # 9. Preparar la respuesta formateada para que Java la entienda y la muestre
        resultado = {
            "status": "success",
            "model_saved_at": model_filename,
            "metrics": {
                "Accuracy (Precisión Global)": f"{acc * 100:.2f}%",
                "Precision (Acierto en subidas)": f"{prec * 100:.2f}%",
                "Recall (Detección de subidas)": f"{rec * 100:.2f}%",
                "F1-Score (Balance)": f"{f1 * 100:.2f}%"
            },
            "data_info": {
                "total_velas_usadas": len(df),
                "velas_entrenamiento": len(X_train),
                "velas_prueba": len(X_test),
                "indicadores_usados": list(X.columns)
            }
        }

        # Imprimir JSON final (Java lo leerá por Stdout)
        print(json.dumps(resultado, indent=4))
        logging.info("=== Entrenamiento concluido con éxito y JSON enviado a Java ===")

    except Exception as e:
        # Registrar el error en el archivo log con la traza completa
        logging.error(f"Fallo durante el proceso de entrenamiento: {str(e)}", exc_info=True)
        
        # Capturar cualquier error para que Java no se quede colgado esperando
        error_res = {
            "status": "error",
            "message": str(e)
        }
        print(json.dumps(error_res))
        sys.exit(1)

if __name__ == "__main__":
    main()