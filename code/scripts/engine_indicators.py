"""engine_indicators.py — Motor de calculo de indicadores tecnicos via IPC.

Protocolo IPC:
  Entrada : MessagePack framed con type INDICATORS_REQUEST — payload {velas: [...]}
  Salida  : MessagePack framed con type INDICATORS_RESPONSE — payload {indicadores: [...]}
"""
from __future__ import annotations

import sys
import warnings

import pandas as pd

from ipc_protocol import read_request_payload, write_response
from shared_utils import setup_engine_logging

warnings.filterwarnings("ignore", category=UserWarning)

logger = setup_engine_logging("engine_indicators", stream=sys.stderr)


def compute_basic_indicators(df: pd.DataFrame) -> pd.DataFrame:
    """
    Calcula indicadores tecnicos basicos como columnas adicionales del DataFrame.
    Disenada para ser importada por otros engines directamente.

    Entrada: df con columna 'close' (string o numerico).
    Salida:  df enriquecido con SMA_14, EMA_14, RSI_14, MACD, MACD_signal.
    """
    df = df.copy()
    df["close"] = pd.to_numeric(df["close"], errors="coerce")

    df["SMA_14"] = df["close"].rolling(window=14).mean()
    df["EMA_14"] = df["close"].ewm(span=14, adjust=False).mean()

    delta = df["close"].diff()
    gain = delta.where(delta > 0, 0).rolling(window=14).mean()
    loss = (-delta).where(delta < 0, 0).rolling(window=14).mean()
    rs = gain / loss
    df["RSI_14"] = 100 - (100 / (1 + rs))

    ema_12 = df["close"].ewm(span=12, adjust=False).mean()
    ema_26 = df["close"].ewm(span=26, adjust=False).mean()
    df["MACD"] = ema_12 - ema_26
    df["MACD_signal"] = df["MACD"].ewm(span=9, adjust=False).mean()

    return df


# Mapa indicador -> parametros para el payload IPC
_INDICATOR_PARAMS: dict[str, str] = {
    "SMA_14": "periodo=14",
    "EMA_14": "periodo=14",
    "RSI_14": "periodo=14",
    "MACD": "fast=12,slow=26",
    "MACD_signal": "signal=9",
}


def calcular_indicadores(df: pd.DataFrame) -> list[dict]:
    """
    Calcula indicadores y devuelve lista de dicts IPC {id, tipo, parametros, valor}.

    Vectorizado con melt: 10-50x mas rapido que iteracion fila-por-fila.
    """
    logger.info("Calculando indicadores tecnicos (SMA_14, EMA_14, RSI_14, MACD, MACD_signal)...")

    if "id" not in df.columns:
        raise ValueError("El DataFrame no contiene la columna 'id'. Revisa el DTO de Java.")

    df = compute_basic_indicators(df)

    cols = ["id"] + list(_INDICATOR_PARAMS.keys())
    melted = (
        df[cols]
        .melt(id_vars="id", var_name="tipo", value_name="valor")
        .dropna(subset=["valor", "id"])
    )
    melted = melted.copy()
    melted["parametros"] = melted["tipo"].map(_INDICATOR_PARAMS)
    melted["id"] = melted["id"].astype(int)
    melted["valor"] = melted["valor"].astype(float)

    result: list[dict] = melted[["id", "tipo", "parametros", "valor"]].to_dict("records")
    logger.info("Indicadores calculados: %d registros generados.", len(result))
    return result


if __name__ == "__main__":
    logger.info("=== Arrancando Motor de Indicadores Tecnicos ===")
    try:
        logger.info("Esperando recepcion de datos IPC desde Java...")
        payload = read_request_payload()
        data = payload.get("velas", [])

        if not data:
            logger.warning("No se recibieron datos de entrada. Devolviendo lista vacia.")
            write_response("INDICATORS_RESPONSE", {"indicadores": []})
            sys.exit(0)

        logger.info("Datos recibidos: %d velas.", len(data))
        df = pd.DataFrame(data)
        lista_indicadores = calcular_indicadores(df)

        logger.info("Enviando respuesta IPC a Java (%d indicadores).", len(lista_indicadores))
        write_response("INDICATORS_RESPONSE", {"indicadores": lista_indicadores})
        logger.info("=== Proceso finalizado correctamente ===")

    except Exception as exc:
        logger.error("Fallo critico en el script: %s", str(exc), exc_info=True)
        write_response("ERROR", {"status": "error", "message": str(exc), "indicadores": []})
        sys.exit(1)
