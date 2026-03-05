import sys
import json
import os
import pandas as pd
import joblib
import logging
import warnings
from datetime import datetime
from sklearn.metrics import accuracy_score, precision_score, recall_score, f1_score

# ==========================================
# IMPORTACIÓN DE LA ARMADA DE MODELOS (Clásicos)
# ==========================================
from sklearn.ensemble import RandomForestClassifier, GradientBoostingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.svm import SVC
import xgboost as xgb
import lightgbm as lgb

warnings.filterwarnings("ignore")

# =========================
# CONFIGURACIÓN DE LOGS
# =========================
current_dir = os.path.dirname(os.path.abspath(__file__))
project_root = os.path.dirname(current_dir)
log_dir = os.path.join(project_root, "logs")
os.makedirs(log_dir, exist_ok=True)

log_file = os.path.join(log_dir, "engine_train.log")

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[
        logging.FileHandler(log_file, encoding='utf-8')
    ]
)

def get_ml_model_instance(model_type, custom_params):
    """Devuelve modelos de Machine Learning clásico inyectando hiperparámetros de Java."""
    if model_type == 'xgboost':
        params = {'n_estimators': 150, 'learning_rate': 0.05, 'max_depth': 6, 'random_state': 42, 'eval_metric': 'logloss'}
        params.update(custom_params)
        return xgb.XGBClassifier(**params)
        
    elif model_type == 'lightgbm':
        params = {'n_estimators': 150, 'learning_rate': 0.05, 'max_depth': 6, 'random_state': 42, 'verbose': -1}
        params.update(custom_params)
        return lgb.LGBMClassifier(**params)
        
    elif model_type == 'gradient_boosting':
        params = {'n_estimators': 100, 'learning_rate': 0.1, 'max_depth': 5, 'random_state': 42}
        params.update(custom_params)
        return GradientBoostingClassifier(**params)
        
    elif model_type == 'svm':
        params = {'kernel': 'rbf', 'probability': True, 'random_state': 42}
        params.update(custom_params)
        return SVC(**params)
        
    elif model_type == 'logistic_regression':
        params = {'max_iter': 1000, 'random_state': 42}
        params.update(custom_params)
        return LogisticRegression(**params)
        
    else: # random_forest
        params = {'n_estimators': 100, 'random_state': 42, 'max_depth': 10}
        params.update(custom_params)
        return RandomForestClassifier(**params)


def build_and_train_neural_network(X_train, y_train, X_test, hyperparams):
    """Construye, entrena y devuelve una Red Neuronal de Deep Learning."""
    logging.info("Importando TensorFlow/Keras para Deep Learning...")
    import tensorflow as tf
    from tensorflow.keras.models import Sequential
    from tensorflow.keras.layers import Dense, Dropout, Normalization
    from tensorflow.keras.optimizers import Adam

    # 1. Extraer hiperparámetros con valores por defecto
    epochs = int(hyperparams.get('epochs', 50))
    batch_size = int(hyperparams.get('batch_size', 64))
    learning_rate = float(hyperparams.get('learning_rate', 0.001))
    dropout_rate = float(hyperparams.get('dropout_rate', 0.3))

    # 2. Capa de Normalización (Aprende la escala del dataset automáticamente)
    norm_layer = Normalization()
    norm_layer.adapt(X_train)

    # 3. Arquitectura de la Red Neuronal (Feed-Forward)
    model = Sequential([
        norm_layer, 
        Dense(64, activation='relu'),
        Dropout(dropout_rate), 
        Dense(32, activation='relu'),
        Dropout(dropout_rate - 0.1 if dropout_rate > 0.1 else dropout_rate), # Un poco menos de dropout en la capa interna
        Dense(16, activation='relu'),
        Dense(1, activation='sigmoid') # Salida entre 0 y 1 (Probabilidad)
    ])

    optimizer = Adam(learning_rate=learning_rate)
    model.compile(optimizer=optimizer, loss='binary_crossentropy', metrics=['accuracy'])
    
    # 4. Entrenar la Red
    logging.info(f"Iniciando entrenamiento de la Red Neuronal (Épocas: {epochs}, Batch Size: {batch_size}, LR: {learning_rate})...")
    model.fit(X_train, y_train, epochs=epochs, batch_size=batch_size, validation_split=0.1, verbose=0)
    
    # 5. Predecir para evaluar
    y_pred_probs = model.predict(X_test, verbose=0)
    y_pred_binario = (y_pred_probs > 0.5).astype(int).flatten()
    
    return model, y_pred_binario


def main():
    logging.info("=== Iniciando proceso de entrenamiento masivo (engine_train.py) ===")
    
    try:
        input_data = sys.stdin.read()
        if not input_data: raise ValueError("No se recibieron datos desde Java.")
        payload = json.loads(input_data)
        
        model_type = payload.get("model_type", "random_forest").lower().strip()
        symbol = payload.get("symbol", "UNKNOWN")
        timeframe = payload.get("timeframe", "UNKNOWN")
        dataset = payload.get("dataset", [])
        
        # 🔥 Extraer hiperparámetros del JSON que manda Java
        hyperparams = payload.get("hyperparameters", {})

        if not dataset: raise ValueError("El dataset está vacío.")

        df = pd.DataFrame(dataset)
        df.sort_values('timestamp', inplace=True)
        df.set_index('timestamp', inplace=True)

        # TARGET (1 = Sube, 0 = Baja)
        df['Target'] = (df['close'].shift(-1) > df['close']).astype(int)
        df.dropna(inplace=True)

        if len(df) < 50: raise ValueError("No hay suficientes datos limpios.")

        X = df.drop(columns=['Target'])
        y = df['Target']
        indicadores_usados = list(X.columns)
        
        X_array = X.values 
        y_array = y.values

        split_idx = int(len(df) * 0.8)
        X_train, X_test = X_array[:split_idx], X_array[split_idx:]
        y_train, y_test = y_array[:split_idx], y_array[split_idx:]
        
        models_dir = os.path.join(project_root, 'models')
        os.makedirs(models_dir, exist_ok=True)
        
        start_time = datetime.now()

        # ==========================================
        # BIFURCACIÓN: DEEP LEARNING vs MACHINE LEARNING
        # ==========================================
        is_deep_learning = model_type in ['neural_network', 'deep_learning', 'keras']
        
        if is_deep_learning:
            logging.info("Seleccionado: Red Neuronal Profunda (Deep Learning)")
            model, y_pred = build_and_train_neural_network(X_train, y_train, X_test, hyperparams)
            
            # Guardamos formato .keras
            model_filename = f"{model_type}_{timeframe}_{symbol}.keras"
            model_path = os.path.join(models_dir, model_filename)
            model.save(model_path)
            logging.info(f"Red Neuronal guardada físicamente en: {model_path}")
            
        else:
            # Pasamos los custom_params a la función instanciadora
            model = get_ml_model_instance(model_type, hyperparams)
            model.fit(X_train, y_train)
            y_pred = model.predict(X_test)
            
            # Guardamos formato .pkl
            model_filename = f"{model_type}_{timeframe}_{symbol}.pkl"
            model_path = os.path.join(models_dir, model_filename)
            joblib.dump(model, model_path)
            logging.info(f"Modelo ML clásico guardado en: {model_path}")

        training_time = (datetime.now() - start_time).total_seconds()

        # ==========================================
        # EVALUACIÓN COMÚN
        # ==========================================
        acc = accuracy_score(y_test, y_pred)
        prec = precision_score(y_test, y_pred, zero_division=0)
        rec = recall_score(y_test, y_pred, zero_division=0)
        f1 = f1_score(y_test, y_pred, zero_division=0)

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
                "modelo_usado": model_type.upper(),
                "tiempo_entrenamiento_seg": round(training_time, 2),
                "total_velas_usadas": len(df),
                "velas_entrenamiento": len(X_train),
                "velas_prueba": len(X_test),
                "indicadores_usados": indicadores_usados,
                "hiperparametros_aplicados": hyperparams # Se los devolvemos a Java para confirmar
            }
        }

        print(json.dumps(resultado, indent=4))

    except Exception as e:
        logging.error(f"Fallo durante el proceso: {str(e)}", exc_info=True)
        print(json.dumps({"status": "error", "message": str(e)}))
        sys.exit(1)

if __name__ == "__main__":
    main()