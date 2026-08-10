"""
Contrato de esquema para DataFrames OHLCV.

Centraliza las invariantes que deben cumplir las velas antes de alimentar
indicadores, backtests o entrenamiento. Detectar aquí un dato corrupto es mucho
más barato que descubrirlo como una métrica de backtest sin sentido.

Uso típico en un engine:

    from ohlcv_schema import validate_ohlcv
    df = validate_ohlcv(df)
"""
from __future__ import annotations

import pandas as pd
import pandera.pandas as pa
from pandera.errors import SchemaError

# Columnas mínimas que BaseStrategy.get_feature_columns() considera "base".
OHLCV_COLUMNS: tuple[str, ...] = ("timestamp", "open", "high", "low", "close", "volume")


def _high_is_max(df: pd.DataFrame) -> pd.Series:
    """El máximo de la vela no puede ser inferior a apertura, cierre ni mínimo."""
    return (df["high"] >= df["low"]) & (df["high"] >= df["open"]) & (df["high"] >= df["close"])


def _low_is_min(df: pd.DataFrame) -> pd.Series:
    """El mínimo de la vela no puede superar apertura ni cierre."""
    return (df["low"] <= df["open"]) & (df["low"] <= df["close"])


OHLCV_SCHEMA = pa.DataFrameSchema(
    columns={
        # Epoch en milisegundos, tal como lo entrega Binance.
        "timestamp": pa.Column("int64", checks=pa.Check.gt(0), nullable=False, coerce=True),
        "open": pa.Column(float, checks=pa.Check.gt(0), nullable=False, coerce=True),
        "high": pa.Column(float, checks=pa.Check.gt(0), nullable=False, coerce=True),
        "low": pa.Column(float, checks=pa.Check.gt(0), nullable=False, coerce=True),
        "close": pa.Column(float, checks=pa.Check.gt(0), nullable=False, coerce=True),
        # El volumen sí puede ser 0 en velas sin operaciones.
        "volume": pa.Column(float, checks=pa.Check.ge(0), nullable=False, coerce=True),
    },
    checks=[
        pa.Check(_high_is_max, name="high_es_el_maximo",
                 error="high debe ser >= open, close y low"),
        pa.Check(_low_is_min, name="low_es_el_minimo",
                 error="low debe ser <= open y close"),
    ],
    # Los engines añaden columnas de indicadores; no deben invalidar el contrato.
    strict=False,
    unique=["timestamp"],
    name="OHLCV",
)


def validate_ohlcv(df: pd.DataFrame, *, lazy: bool = True) -> pd.DataFrame:
    """
    Valida el contrato OHLCV y devuelve el DataFrame (con tipos coercionados).

    Con lazy=True se acumulan todos los fallos en un único SchemaErrors, que es lo
    útil al diagnosticar un histórico descargado; con lazy=False aborta en el primero.
    """
    return OHLCV_SCHEMA.validate(df, lazy=lazy)


def find_time_gaps(df: pd.DataFrame, step_ms: int) -> list[tuple[int, int]]:
    """
    Devuelve los huecos temporales como pares (timestamp_previo, timestamp_siguiente).

    Complementa a validate_ohlcv: el esquema garantiza que los timestamps son únicos
    y positivos, pero no que la serie sea continua para el timeframe dado.

    Args:
        df: DataFrame OHLCV, no necesariamente ordenado.
        step_ms: milisegundos entre velas consecutivas (1h -> 3_600_000).
    """
    if step_ms <= 0:
        raise ValueError(f"step_ms debe ser positivo, recibido: {step_ms}")
    if len(df) < 2:
        return []

    ordered = df["timestamp"].sort_values().to_numpy()
    deltas = ordered[1:] - ordered[:-1]
    return [
        (int(ordered[i]), int(ordered[i + 1]))
        for i, delta in enumerate(deltas)
        if delta > step_ms
    ]


__all__ = [
    "OHLCV_COLUMNS",
    "OHLCV_SCHEMA",
    "SchemaError",
    "find_time_gaps",
    "validate_ohlcv",
]
