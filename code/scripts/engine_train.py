import sys
import json
import os
import importlib
import pandas as pd
import joblib
import logging
import warnings
import gc
from datetime import datetime
from typing import TYPE_CHECKING
from sklearn.metrics import accuracy_score, precision_score, recall_score, f1_score

if TYPE_CHECKING:
    from strategies.BaseStrategy import BaseStrategy

# ==========================================
# IMPORTACIÓN DE LA ARMADA DE MODELOS (Clásicos)
# ==========================================
from sklearn.ensemble import RandomForestClassifier, GradientBoostingClassifier
from sklearn.linear_model import LogisticRegression
from sklearn.svm import SVC
import xgboost as xgb
import lightgbm as lgb
from ipc_protocol import read_request_payload, write_response, write_error

warnings.filterwarnings("ignore")

# =========================
# CONFIGURACIÓN DE LOGS
# =========================
current_dir = os.path.dirname(os.path.abspath(__file__))
project_root = os.path.dirname(current_dir)
log_dir = os.path.join(project_root, "logs")
os.makedirs(log_dir, exist_ok=True)

log_file = os.path.join(log_dir, "engine_train.log")

file_handler = logging.FileHandler(log_file, encoding='utf-8')
file_handler.setLevel(logging.INFO)
file_handler.setFormatter(logging.Formatter('%(asctime)s [%(levelname)s] %(message)s'))

stream_handler = logging.StreamHandler(sys.stderr)
stream_handler.setLevel(logging.INFO)
stream_handler.setFormatter(logging.Formatter('[%(levelname)s] %(message)s'))

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[file_handler, stream_handler]
)


def load_strategy(strategy_name: str) -> "BaseStrategy":
    """Carga una estrategia por nombre y valida que herede de BaseStrategy."""
    if not strategy_name or not strategy_name.strip():
        raise ValueError("strategy_name está vacío o no es válido.")

    strategy_name = strategy_name.strip()

    if project_root not in sys.path:
        sys.path.insert(0, project_root)

    try:
        from strategies.BaseStrategy import BaseStrategy
    except Exception as exc:
        raise ValueError(f"No se pudo importar BaseStrategy: {exc}") from exc

    module = None
    errors: list[str] = []

    for module_name in (f"strategies.{strategy_name}", strategy_name):
        try:
            module = importlib.import_module(module_name)
            break
        except Exception as exc:
            errors.append(f"{module_name}: {exc}")

    if module is None:
        detalle = " | ".join(errors)
        raise ValueError(f"No se pudo importar la estrategia '{strategy_name}'. Detalle: {detalle}")

    strategy_class = getattr(module, strategy_name, None)
    if strategy_class is None:
        raise ValueError(f"No existe la clase '{strategy_name}' en el módulo de estrategia.")

    if not isinstance(strategy_class, type) or not issubclass(strategy_class, BaseStrategy):
        raise ValueError(f"La clase '{strategy_name}' no hereda de BaseStrategy.")

    return strategy_class()


def apply_strategy_features(
    df: pd.DataFrame,
    strategy: "BaseStrategy",
    warmup_candles: int,
) -> tuple[pd.DataFrame, pd.Series]:
    """Aplica indicadores/labels de estrategia y devuelve X, y alineados."""
    if "timestamp" not in df.columns and df.index.name == "timestamp":
        df = df.reset_index()

    if "timestamp" in df.columns:
        df = df.sort_values("timestamp")

    logging.info("Iniciando populate_indicators para estrategia '%s'", strategy.get_name())
    start = datetime.now()
    df_enriched = strategy.populate_indicators(df.copy())
    duration = (datetime.now() - start).total_seconds()
    logging.info("populate_indicators finalizado en %.2fs (filas=%d)", duration, len(df_enriched))

    warmup = max(int(warmup_candles or 0), 0)
    if warmup > 0:
        logging.info("Aplicando warmup: descartando primeras %d velas", warmup)
        df_enriched = df_enriched.iloc[warmup:].copy()

    if len(df_enriched) < 2:
        raise ValueError("No hay suficientes velas tras aplicar indicadores y warmup.")

    feature_cols = strategy.get_feature_columns(df_enriched)
    if not feature_cols:
        raise ValueError("La estrategia no devolvió columnas de features (get_feature_columns vacío).")

    # Asegura pares (row, next_row): la última vela no tiene siguiente y se descarta.
    current_rows = df_enriched.iloc[:-1].copy()
    next_rows = df_enriched.iloc[1:].copy()

    labels: list[int] = []
    for row, next_row in zip(
        current_rows.itertuples(index=False, name="Row"),
        next_rows.itertuples(index=False, name="NextRow"),
    ):
        labels.append(strategy.get_label(row._asdict(), next_row._asdict()))

    y = pd.Series(labels, index=current_rows.index, name="Target")
    x_features = current_rows[feature_cols].copy()

    # Limpieza defensiva de no-finitos para evitar fallos en entrenamiento.
    x_features = x_features.replace([float("inf"), float("-inf")], float("nan"))
    valid_mask = ~x_features.isna().any(axis=1)
    dropped = int((~valid_mask).sum())
    if dropped > 0:
        logging.warning("Se descartaron %d filas por NaN/inf en features tras warmup.", dropped)
    x_features = x_features.loc[valid_mask]
    y = y.loc[valid_mask]

    if len(x_features) < 50:
        raise ValueError("No hay suficientes datos limpios tras aplicar estrategia dinámica.")

    label_distribution = y.value_counts(dropna=False).to_dict()
    logging.info(
        "Feature engineering dinámico completado: filas_validas=%d, num_features=%d, labels=%s",
        len(x_features),
        len(feature_cols),
        label_distribution,
    )
    logging.info("Feature columns usadas: %s", feature_cols)

    return x_features, y

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
    from tensorflow.keras.callbacks import Callback
    from tensorflow.keras.models import Sequential
    from tensorflow.keras.layers import Dense, Dropout, Normalization
    from tensorflow.keras.optimizers import Adam

    # 1. Extraer hiperparámetros con valores por defecto
    epochs = int(hyperparams.get('epochs', 50))
    batch_size = int(hyperparams.get('batch_size', 64))
    learning_rate = float(hyperparams.get('learning_rate', 0.001))
    dropout_rate = float(hyperparams.get('dropout_rate', 0.3))

    class EpochProgressLogger(Callback):
        def on_epoch_end(self, epoch, logs=None):
            logs = logs or {}
            loss = float(logs.get('loss') or 0.0)
            val_loss = float(logs.get('val_loss') or 0.0)
            acc = float(logs.get('accuracy') or 0.0)
            val_acc = float(logs.get('val_accuracy') or 0.0)
            logging.info(
                "Epoch %d/%d completada. loss=%.6f val_loss=%.6f acc=%.4f val_acc=%.4f",
                epoch + 1,
                epochs,
                loss,
                val_loss,
                acc,
                val_acc,
            )

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
    model.fit(
        X_train,
        y_train,
        epochs=epochs,
        batch_size=batch_size,
        validation_split=0.1,
        verbose=0,
        callbacks=[EpochProgressLogger()],
    )
    
    # 5. Predecir para evaluar
    y_pred_probs = model.predict(X_test, verbose=0)
    y_pred_binario = (y_pred_probs > 0.5).astype(int).flatten()
    
    return model, y_pred_binario


def main():
    logging.info("=== Iniciando proceso de entrenamiento masivo (engine_train.py) ===")
    
    try:
        payload = read_request_payload()
        
        model_type = payload.get("model_type", "random_forest").lower().strip()
        symbol = payload.get("symbol", "UNKNOWN")
        timeframe = payload.get("timeframe", "UNKNOWN")
        dataset = payload.get("dataset", [])
        strategy_name = payload.get("strategy_name")
        is_dynamic = "strategy_name" in payload and payload.get("strategy_name") is not None
        warmup_candles = payload.get("warmup_candles")
        
        # 🔥 Extraer hiperparámetros del JSON que manda Java
        hyperparams = payload.get("hyperparameters", {})

        if not dataset: raise ValueError("El dataset está vacío.")

        df = pd.DataFrame(dataset)
        df.sort_values('timestamp', inplace=True)
        df.set_index('timestamp', inplace=True)

        if strategy_name:
            logging.info("Modo dinámico activado con estrategia: %s", strategy_name)
            strategy = load_strategy(strategy_name)
            X, y = apply_strategy_features(df, strategy, int(warmup_candles or 0))
            feature_cols = list(X.columns)
            total_rows_used = len(X)
        else:
            # Flujo legacy intacto: Target binario por siguiente vela.
            df['Target'] = (df['close'].shift(-1) > df['close']).astype(int)
            
            # ⚠️ VALIDAR ANTES DE DROPNA - Problema crítico detectado
            rows_before_dropna = len(df)
            logging.info(f"Dataset antes de dropna(): {rows_before_dropna} filas")
            
            df.dropna(inplace=True)
            rows_after_dropna = len(df)
            rows_lost = rows_before_dropna - rows_after_dropna
            
            if rows_lost > 0:
                loss_percentage = (rows_lost / rows_before_dropna) * 100
                if loss_percentage > 50:
                    logging.warning(
                        f"⚠️ ADVERTENCIA: Se perderán {rows_lost} filas ({loss_percentage:.1f}%) "
                        f"por NaN/Inf. Dataset final: {rows_after_dropna} filas. "
                        f"Considera aumentar el número de días de entrenamiento."
                    )
                else:
                    logging.info(f"Filas descartadas por NaN/Inf: {rows_lost} ({loss_percentage:.1f}%)")

            if len(df) < 50:
                raise ValueError(
                    f"Insuficientes datos limpios tras dropna(): {len(df)} < 50 (requerido mínimo). "
                    f"Se perdieron {rows_lost} filas. "
                    f"Solución: ejecutar con más días de histórico o verificar calidad de datos."
                )

            X = df.drop(columns=['Target'])
            y = df['Target']
            feature_cols = list(X.columns)
            total_rows_used = len(df)
        
        X_array = X.values 
        y_array = y.values

        split_idx = int(len(X_array) * 0.8)
        X_train, X_test = X_array[:split_idx], X_array[split_idx:]
        y_train, y_test = y_array[:split_idx], y_array[split_idx:]
        
        vars_to_delete = ['X', 'y', 'X_array', 'y_array', 'df', 'strategy']

        for var in vars_to_delete:
            if var in globals():
                del globals()[var]
        if 'strategy_name' in globals():
            del strategy_name

        gc.collect()
        logging.info("Memoria liberada antes del entrenamiento")
        
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

            setattr(model, "feature_cols", feature_cols)
            setattr(model, "strategy_name", strategy_name)
            setattr(model, "warmup_candles", warmup_candles)
            
            # Guardamos formato .keras
            if strategy_name:
                model_filename = f"{model_type}_{timeframe}_{symbol}_{strategy_name}.keras"
            else:
                model_filename = f"{model_type}_{timeframe}_{symbol}.keras"
            model_path = os.path.join(models_dir, model_filename)
            model.save(model_path)
            metadata_filename = model_filename.replace(".keras", ".metadata.json")
            metadata_path = os.path.join(models_dir, metadata_filename)
            with open(metadata_path, "w", encoding="utf-8") as metadata_file:
                json.dump(
                    {
                        "feature_cols": feature_cols,
                        "strategy_name": strategy_name,
                        "warmup_candles": warmup_candles,
                    },
                    metadata_file,
                    indent=2,
                )
            logging.info(f"Red Neuronal guardada físicamente en: {model_path}")
            
        else:
            # Pasamos los custom_params a la función instanciadora
            model = get_ml_model_instance(model_type, hyperparams)
            model.fit(X_train, y_train)
            y_pred = model.predict(X_test)

            setattr(model, "feature_cols", feature_cols)
            setattr(model, "strategy_name", strategy_name)
            setattr(model, "warmup_candles", warmup_candles)
            
            # Guardamos formato .pkl
            if strategy_name:
                model_filename = f"{model_type}_{timeframe}_{symbol}_{strategy_name}.pkl"
            else:
                model_filename = f"{model_type}_{timeframe}_{symbol}.pkl"
            model_path = os.path.join(models_dir, model_filename)
            joblib.dump(model, model_path)
            logging.info(f"Modelo ML clásico guardado en: {model_path}")

        training_time = (datetime.now() - start_time).total_seconds()

        # ==========================================
        # EVALUACIÓN COMÚN
        # ==========================================
        acc = accuracy_score(y_test, y_pred)
        if is_dynamic:
            prec = precision_score(y_test, y_pred, average='weighted', zero_division=0)
            rec = recall_score(y_test, y_pred, average='weighted', zero_division=0)
            f1 = f1_score(y_test, y_pred, average='weighted', zero_division=0)
        else:
            # Flujo legacy intacto (binario): pos_label=1 por defecto
            prec = precision_score(y_test, y_pred, zero_division=0)
            rec = recall_score(y_test, y_pred, zero_division=0)
            f1 = f1_score(y_test, y_pred, zero_division=0)

        label_distribution = {
            str(label): int(count)
            for label, count in pd.Series(y_test).value_counts(dropna=False).to_dict().items()
        }

        resultado = {
            "status": "success",
            "model_saved_at": model_filename,
            "feature_cols": feature_cols,
            "strategy_name": strategy_name,
            "warmup_candles": warmup_candles,
            "label_distribution": label_distribution,
            "metrics": {
                "Accuracy (Precisión Global)": f"{acc * 100:.2f}%",
                "Precision (Acierto en subidas)": f"{prec * 100:.2f}%",
                "Recall (Detección de subidas)": f"{rec * 100:.2f}%",
                "F1-Score (Balance)": f"{f1 * 100:.2f}%"
            },
            "data_info": {
                "modelo_usado": model_type.upper(),
                "tiempo_entrenamiento_seg": round(training_time, 2),
                "total_velas_usadas": total_rows_used,
                "velas_entrenamiento": len(X_train),
                "velas_prueba": len(X_test),
                "indicadores_usados": feature_cols,
                "feature_cols": feature_cols,
                "strategy_name": strategy_name,
                "warmup_candles": warmup_candles,
                "hiperparametros_aplicados": hyperparams # Se los devolvemos a Java para confirmar
            }
        }

        write_response("TRAIN_RESPONSE", resultado)

    except Exception as e:
        logging.error(f"Fallo durante el proceso: {str(e)}", exc_info=True)
        write_error("ERROR", str(e))
        sys.exit(1)

if __name__ == "__main__":
    main()