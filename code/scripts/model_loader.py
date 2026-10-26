"""model_loader.py — localización, verificación y carga de modelos entrenados (sklearn, XGBoost, LightGBM, Keras).

Lo usan el motor RT con IA y el backtest con modelo; vive aparte para que el backtest no arrastre
websockets ni el resto de engine_ai_rt.
"""
from __future__ import annotations

import json
import logging
import os
from typing import Any

import joblib
import numpy as np
from shared_utils import PROJECT_ROOT, verify_model_manifest

logger = logging.getLogger(__name__)

#: Extensiones reconocidas para un modelo entrenado, en orden de preferencia.
MODEL_EXTENSIONS = (".pkl", ".keras", ".h5")


def _load_label_classes(model_path: str) -> list[int] | None:
    """Lee label_classes del .metadata.json junto al modelo. Devuelve None si no existe."""
    metadata_path = os.path.splitext(model_path)[0] + ".metadata.json"
    if not os.path.exists(metadata_path):
        # intentar .metadata.json si la extensión era .h5
        metadata_path = model_path + ".metadata.json"
    if os.path.exists(metadata_path):
        with open(metadata_path, encoding="utf-8") as f:
            meta = json.load(f)
        classes = meta.get("label_classes")
        if classes is not None:
            logger.info("Metadata cargada: label_classes=%s", classes)
            return [int(c) for c in classes]
    return None


# =========================
# CARGA DE MODELO IA UNIVERSAL
# =========================
def _load_from_path(path: str) -> tuple[Any, str, list[int] | None]:
    """Carga un modelo ya localizado, eligiendo el backend por su extensión."""
    if path.endswith(".pkl"):
        logger.info("Cargando modelo clasico (PKL): %s", path)
        loaded = joblib.load(path)
        encoder = getattr(loaded, "label_encoder", None)
        classes = list(encoder.classes_) if encoder is not None else None
        return loaded, "ml_standard", classes

    logger.info("Cargando modelo de Red Neuronal (Keras/TF): %s", path)
    from tensorflow.keras.models import load_model as load_keras_model
    return load_keras_model(path), "deep_learning", _load_label_classes(path)


def _candidate_model_paths(
    models_dir: str, model_name: str, base_filename: str, strategy_name: str | None = None,
) -> list[str]:
    """
    Rutas candidatas en orden de precedencia:
    0) {modelo}_{tf}_{symbol}_{estrategia} exacto (es lo que escribe el entrenamiento),
    1) nombre libre (el usuario ha renombrado el modelo),
    2) formato {modelo}_{timeframe}_{symbol} sin sufijo.
    Las variantes con otro sufijo de estrategia las resuelve load_model (ambigüedad).
    """
    names = [f"{base_filename}_{strategy_name}"] if strategy_name else []
    names += [model_name, base_filename]
    return [os.path.join(models_dir, f"{name}{ext}") for name in names for ext in MODEL_EXTENSIONS]


def _suffix_variants(models_dir: str, base_filename: str) -> list[str]:
    """Modelos del mismo modelo/tf/symbol entrenados con alguna estrategia (orden estable)."""
    return [
        os.path.join(models_dir, fname)
        for fname in sorted(os.listdir(models_dir))
        if fname.startswith(f"{base_filename}_") and fname.endswith(MODEL_EXTENSIONS)
    ]


def _trained_strategy(model: Any, path: str) -> str | None:
    """Estrategia con la que se entrenó el modelo (atributo del .pkl o .metadata.json del .keras)."""
    declared = getattr(model, "strategy_name", None)
    if declared:
        return declared
    metadata_path = os.path.splitext(path)[0] + ".metadata.json"
    if os.path.exists(metadata_path):
        with open(metadata_path, encoding="utf-8") as f:
            return json.load(f).get("strategy_name")
    return None


def _attach_training_metadata(model: Any, path: str) -> None:
    """Expone warmup_candles y train_end_ts también en los .keras (viven en su .metadata.json)."""
    metadata_path = os.path.splitext(path)[0] + ".metadata.json"
    if not os.path.exists(metadata_path):
        return
    with open(metadata_path, encoding="utf-8") as f:
        meta = json.load(f)
    for key in ("warmup_candles", "train_end_ts", "feature_cols"):
        if getattr(model, key, None) is None and meta.get(key) is not None:
            setattr(model, key, meta[key])


def load_model(
    model_name: str, timeframe: str, symbol: str, strategy_name: str | None = None,
) -> tuple[Any, str, list[int] | None]:
    """
    Carga dinámicamente diferentes tipos de modelos basados en su extensión y librería.
    Soporta Scikit-Learn, XGBoost, LightGBM (.pkl) y TensorFlow/Keras (.h5 / .keras).
    Devuelve (modelo, tipo, label_classes) donde label_classes es la lista original
    de clases [-1, 0, 1] leída del .metadata.json (o None si no existe).

    Con strategy_name se prefiere el modelo entrenado con esa estrategia y se aborta si el
    modelo cargado declara otra distinta (sus features no coincidirían).
    """
    models_dir = os.path.join(PROJECT_ROOT, 'models')
    base_filename = f"{model_name}_{timeframe}_{symbol}"

    path = next((p for p in _candidate_model_paths(models_dir, model_name, base_filename, strategy_name)
                 if os.path.exists(p)), None)
    if path is None:
        variants = _suffix_variants(models_dir, base_filename)
        if len(variants) > 1:
            raise ValueError(
                f"Modelo ambiguo para '{base_filename}': {[os.path.basename(v) for v in variants]}. "
                "Indica la estrategia o elimina los que sobren."
            )
        path = variants[0] if variants else None
    if path is None:
        raise FileNotFoundError(
            f"No se encontró ningún modelo para '{model_name}' "
            f"(buscado como {base_filename}[_estrategia], nombre libre y variantes con sufijo). "
            f"Extensiones probadas: {', '.join(MODEL_EXTENSIONS)}"
        )

    verify_model_manifest(path)
    loaded = _load_from_path(path)
    _attach_training_metadata(loaded[0], path)
    trained_with = _trained_strategy(loaded[0], path)
    if strategy_name and trained_with and trained_with != strategy_name:
        raise ValueError(
            f"El modelo {os.path.basename(path)} se entrenó con '{trained_with}' "
            f"pero el motor usa '{strategy_name}': las features no coinciden."
        )
    return loaded


#: Marca de "ni compra ni vende" para la sigmoide binaria (banda entre los dos umbrales).
NEUTRAL = -99
#: Probabilidades de la sigmoide a partir de las cuales el modelo binario decide comprar o vender.
SIGMOID_BUY_PROB = 0.55
SIGMOID_SELL_PROB = 0.45


def decode_deep_learning(raw: np.ndarray, label_classes: list[int] | None) -> np.ndarray:
    """Etiquetas en el espacio original para la salida de un modelo Keras (una por fila).

    softmax (varias columnas): argmax, decodificado con label_classes; sin ellas, 3 columnas son
    {-1, 0, 1} y 2 son {0, 1}. sigmoide (una columna): 1 si prob >= SIGMOID_BUY_PROB, 0 si
    <= SIGMOID_SELL_PROB y NEUTRAL entre medias. Backtest y motor RT usan esta misma función.
    """
    if raw.ndim == 2 and raw.shape[1] > 1:
        encoded = np.argmax(raw, axis=1)
        if label_classes is not None:
            classes = np.array(label_classes, dtype=int)
            return classes[np.clip(encoded, 0, len(classes) - 1)]
        return encoded - (raw.shape[1] // 2) if raw.shape[1] > 2 else encoded
    prob = raw.reshape(-1)
    return np.where(prob >= SIGMOID_BUY_PROB, 1, np.where(prob <= SIGMOID_SELL_PROB, 0, NEUTRAL)).astype(int)

