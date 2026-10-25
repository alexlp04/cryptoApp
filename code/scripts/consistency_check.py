"""consistency_check.py — ¿ve la estrategia en vivo lo mismo que vio el backtest?

El backtest calcula los indicadores sobre todo el histórico; el motor en tiempo real, sobre un buffer corto
de velas. Esta herramienta compara, en puntos repartidos por el histórico, lo que calcula la estrategia con:

  - el histórico completo (lo que usa el backtest),
  - solo hasta ese punto ("lookahead"): si el valor cambia, usa datos del futuro y el backtest es optimista,
  - solo la ventana del motor RT ("buffer"): si cambia, depende de cuánta historia vea; si sale NaN, el
    indicador necesita más velas de las que el motor guarda y la señal no se dispararía nunca en vivo.

Se comparan los indicadores, las señales (should_*) y los niveles de stop/objetivo (que dimensionan la posición).

Uso:  python consistency_check.py estrategia.py velas.csv [--window N] [--samples N] [--ai]
"""
from __future__ import annotations

import argparse
import math
import sys
from typing import Any

import numpy as np
import pandas as pd
from risk_management import strategy_levels

_BASE_COLUMNS = {"timestamp", "open", "high", "low", "close", "volume"}
_SIGNALS = ("should_buy", "should_sell", "should_short", "should_cover")


def _last_row(strategy, candles: pd.DataFrame) -> dict | None:
    """Última fila con indicadores, o None si la estrategia no puede calcularla con tan pocas velas."""
    try:
        return strategy.populate_indicators(candles.copy()).iloc[-1].to_dict()
    except Exception:  # noqa: BLE001 - lo que falla con una ventana corta es justo lo que se mide
        return None


def _deviation(a: Any, b: Any) -> float:
    """Desviación relativa entre dos valores; inf si solo uno es NaN, 0 si los dos lo son."""
    a_nan, b_nan = _is_nan(a), _is_nan(b)
    if a_nan or b_nan:
        return 0.0 if a_nan and b_nan else math.inf
    return abs(a - b) / max(1.0, abs(a), abs(b))


def _is_nan(value: Any) -> bool:
    return isinstance(value, float) and math.isnan(value)


def _signal(strategy, name: str, row: dict | None) -> bool | None:
    if row is None:
        return None
    try:
        return bool(getattr(strategy, name)(row))
    except Exception:  # noqa: BLE001 - p. ej. KeyError por una columna que la ventana no trae
        return None


def _levels(strategy, row: dict | None) -> tuple[float, float] | None:
    if row is None or _is_nan(row.get("close")):
        return None
    return strategy_levels(strategy, float(row["close"]), row)


def _compare(strategy, reference: dict, other: dict | None, columns: list[str], rel_tol: float, out: dict) -> None:
    """Acumula en out las diferencias de other respecto a reference (indicadores, señales y niveles)."""
    out["samples"] += 1
    if other is None:
        out["not_computable"] += 1
        return
    for col in columns:
        dev = _deviation(reference.get(col), other.get(col))
        if dev > rel_tol:
            entry = out["columns"].setdefault(col, {"samples": 0, "max_rel": 0.0})
            entry["samples"] += 1
            entry["max_rel"] = max(entry["max_rel"], dev)
    for name in _SIGNALS:
        if not hasattr(strategy, name) or (
                name in ("should_short", "should_cover") and not getattr(strategy, "allow_short", False)):
            continue
        if _signal(strategy, name, reference) != _signal(strategy, name, other):
            out["signals"][name] = out["signals"].get(name, 0) + 1
    ref_levels, other_levels = _levels(strategy, reference), _levels(strategy, other)
    if (ref_levels is None) != (other_levels is None) or (
            ref_levels and other_levels and _deviation(ref_levels[0], other_levels[0]) > rel_tol):
        out["levels"] += 1


def _empty() -> dict:
    return {"samples": 0, "not_computable": 0, "columns": {}, "signals": {}, "levels": 0}


def check_consistency(strategy, df: pd.DataFrame, window: int, samples: int = 200, rel_tol: float = 1e-6) -> dict:
    """Compara la estrategia con el histórico completo, con el prefijo (lookahead) y con la ventana del RT.

    window: velas que guarda el motor en tiempo real. samples: puntos repartidos por el histórico
    (cada uno calcula los indicadores tres veces, así que el coste crece con samples * velas).
    """
    df = df.reset_index(drop=True)
    if len(df) <= window:
        raise ValueError(f"Hacen falta más de {window} velas (hay {len(df)}) para comparar contra una ventana")
    full = strategy.populate_indicators(df.copy()).reset_index(drop=True)
    columns = [c for c in full.columns if c not in _BASE_COLUMNS and pd.api.types.is_numeric_dtype(full[c])]
    points = np.unique(np.linspace(window, len(df) - 1, num=min(samples, len(df) - window)).astype(int))

    lookahead, buffer = _empty(), _empty()
    for t in points:
        reference = full.iloc[t].to_dict()
        _compare(strategy, reference, _last_row(strategy, df.iloc[: t + 1]), columns, rel_tol, lookahead)
        _compare(strategy, reference, _last_row(strategy, df.iloc[t + 1 - window: t + 1]), columns, rel_tol, buffer)
    return {"window": window, "candles": len(df), "lookahead": lookahead, "buffer": buffer}


def format_report(result: dict) -> str:
    """Resumen legible: qué columnas, señales y niveles difieren y en cuántas muestras."""
    lines = [f"Velas: {result['candles']}, ventana del motor RT: {result['window']}"]
    for key, title, meaning in (
        ("lookahead", "LOOKAHEAD (prefijo contra histórico completo)", "usa datos del futuro"),
        ("buffer", "BUFFER (ventana del RT contra histórico completo)", "depende de la historia que ve"),
    ):
        part = result[key]
        clean = not (part["columns"] or part["signals"] or part["levels"] or part["not_computable"])
        lines.append(f"\n{title}: {'sin diferencias' if clean else 'HAY DIFERENCIAS'} en {part['samples']} muestras")
        if part["not_computable"]:
            lines.append(f"  - no se pudo calcular en {part['not_computable']} muestras")
        for col, entry in sorted(part["columns"].items(), key=lambda kv: -kv[1]["samples"]):
            lines.append(f"  - {col}: difiere en {entry['samples']} muestras (desviación máx. {entry['max_rel']:.3g}); "
                         f"{meaning}")
        for name, count in part["signals"].items():
            lines.append(f"  - señal {name}: cambia en {count} muestras")
        if part["levels"]:
            lines.append(f"  - stop/objetivo: cambian en {part['levels']} muestras")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("strategy", help="ruta al .py de la estrategia")
    parser.add_argument("candles", help="CSV con timestamp,open,high,low,close,volume")
    parser.add_argument("--window", type=int, help="velas del buffer RT (por defecto, el del motor clásico)")
    parser.add_argument("--samples", type=int, default=200)
    parser.add_argument("--ai", action="store_true", help="usa el tamaño de buffer del motor con IA")
    args = parser.parse_args()

    from shared_utils import load_strategy_by_path

    strategy = load_strategy_by_path(args.strategy)
    window = args.window
    if window is None:
        if args.ai:
            window = max(100, max(strategy.get_warmup_period(), 50) + 50)  # engine_ai_rt: BACKFILL_MARGIN
        else:
            from engine_rt import _buffer_size
            window = _buffer_size(100, getattr(strategy, "WARMUP_PERIOD", 20))
    result = check_consistency(strategy, pd.read_csv(args.candles), window, args.samples)
    print(format_report(result))
    clean = all(not (result[k]["columns"] or result[k]["signals"] or result[k]["levels"]) for k in ("lookahead", "buffer"))
    sys.exit(0 if clean else 1)


if __name__ == "__main__":
    main()
