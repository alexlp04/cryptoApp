"""replay_check.py — ¿hace el motor en tiempo real las mismas operaciones que el backtest?

Pasa velas históricas por el motor RT clásico (engine_rt.run_symbol, el código real) como si llegasen
por el WebSocket, y compara las señales que emite con las operaciones del backtest sobre las mismas velas.

Cada vela llega como una trayectoria de precios (apertura, extremo adverso, extremo favorable, cierre) con
muchos mensajes por tramo, porque el motor vigila stop y objetivo en cada mensaje: con un solo mensaje por
vela el stop nunca se tocaría. La trayectoria es una aproximación; lo que se mide es la diferencia de
lógica y de ventana de indicadores, no el orden real de los ticks.

Uso:  python replay_check.py estrategia.py velas.csv [--candles N] [--window N]
"""
from __future__ import annotations

import argparse
import asyncio
import contextlib
import io
import itertools
import json
import sys
from unittest import mock

import numpy as np
import pandas as pd

STEPS_PER_LEG = 12  # mensajes por tramo de la trayectoria de una vela


class _Done(Exception):
    """Fin del replay: se acabaron las velas y el motor intentaría reconectar."""


def _path(candle: dict, adverse_low_first: bool) -> list[float]:
    """Precios de una vela: apertura -> extremo adverso -> extremo favorable -> cierre."""
    o, h, low, c = candle["open"], candle["high"], candle["low"], candle["close"]
    corners = [o, low, h, c] if adverse_low_first else [o, h, low, c]
    points: list[float] = []
    for a, b in itertools.pairwise(corners):
        points.extend(np.linspace(a, b, STEPS_PER_LEG, endpoint=False))
    return [*points, c]


class _ReplayStream:
    """WebSocket falso: sirve las velas de una en una, eligiendo el tramo adverso según la posición abierta."""

    def __init__(self, candles: list[dict], out: io.StringIO):
        self._candles, self._out = candles, out

    def _open_side(self) -> str | None:
        side = None
        for line in self._out.getvalue().splitlines():
            if line.startswith("SIGNAL"):
                action = json.loads(line.split("\t", 1)[1])["action"]
                side = {"BUY": "long", "SHORT": "short"}.get(action)
        return side

    async def __aenter__(self):
        return self

    async def __aexit__(self, *exc):
        return False

    def __aiter__(self):
        return self._messages()

    async def _messages(self):
        for candle in self._candles:
            prices = _path(candle, adverse_low_first=self._open_side() != "short")
            high = low = prices[0]
            for i, price in enumerate(prices):
                high, low = max(high, price), min(low, price)
                last = i == len(prices) - 1
                yield json.dumps({"k": {
                    "t": candle["timestamp"], "o": str(candle["open"]),
                    "h": str(candle["high"] if last else high), "l": str(candle["low"] if last else low),
                    "c": str(price), "v": str(candle["volume"]), "x": last}})


def _rt_events(strategy_rt, seed: list[dict], candles: list[dict], window: int) -> list[dict]:
    """Señales que emite engine_rt.run_symbol al recibir `candles` tras sembrar su buffer con `seed`.

    window es el max_candles del motor; el buffer real es max(window, WARMUP_PERIOD de la estrategia).
    """
    import engine_rt

    out = io.StringIO()

    async def stop(*_):
        raise _Done

    async def run() -> None:
        with contextlib.suppress(_Done):
            await engine_rt.run_symbol("TESTUSDT", "1h", "x.py", 10_000.0, 0.01, False, max_candles=window)

    with (contextlib.redirect_stdout(out),
          mock.patch.object(engine_rt, "load_strategy_by_path", lambda *a, **k: strategy_rt),
          mock.patch.object(engine_rt, "fetch_recent_klines", lambda *a, **k: list(seed)),
          mock.patch.object(engine_rt.websockets, "connect", lambda url: _ReplayStream(candles, out)),
          mock.patch.object(asyncio, "sleep", stop)):
        asyncio.run(run())
    signals = [json.loads(ln.split("\t", 1)[1]) for ln in out.getvalue().splitlines() if ln.startswith("SIGNAL")]
    return [{**s, "price": float(s["price"])} for s in signals]  # el motor emite el precio como texto


def _backtest_events(strategy_bt, df: pd.DataFrame) -> list[dict]:
    from backtest_engine import run_backtest

    events: list[dict] = []
    # on_trade no se expone en run_backtest: se captura escribiendo los trades a un CSV temporal
    import csv
    import tempfile
    with tempfile.TemporaryDirectory() as tmp:
        run_backtest(strategy_bt, df.copy(), "TESTUSDT", carpeta_estrategia=tmp, timeframe="1h",
                     escribir_trades=True, fee_rate=0.0, slippage=0.0)
        path = f"{tmp}/TESTUSDT-1h-trades_backtest.csv"
        try:
            with open(path, newline="", encoding="utf-8") as fh:
                events = [{"action": r["side"], "timestamp": int(float(r["timestamp"])), "price": float(r["price"])}
                          for r in csv.DictReader(fh)]
        except FileNotFoundError:
            pass  # sin operaciones no hay CSV
    return events


def replay_vs_backtest(make_strategy, df: pd.DataFrame, window: int, candles: int = 1000) -> dict:
    """Compara las operaciones del motor RT con las del backtest sobre las últimas `candles` velas de df.

    make_strategy: función sin argumentos que devuelve una estrategia nueva (cada lado necesita la suya).
    El backtest recibe `window` velas de calentamiento más las del replay, como el buffer sembrado del RT.
    """
    df = df.reset_index(drop=True)
    candles = min(candles, len(df) - window)
    if candles <= 0:
        raise ValueError(f"Hacen falta más de {window} velas")
    start = len(df) - candles
    seed = df.iloc[start - window: start].to_dict("records")
    replay = df.iloc[start:].to_dict("records")

    backtest = _backtest_events(make_strategy(), df.iloc[start - window:])
    rt = _rt_events(make_strategy(), seed, replay, window)

    # Se compara desde la primera apertura del backtest en el tramo del replay: el RT arranca sin posición.
    first_ts = int(df.iloc[start]["timestamp"])
    opens = [e for e in backtest if e["timestamp"] >= first_ts and e["action"] in ("BUY", "SHORT")]
    t0 = opens[0]["timestamp"] if opens else None
    backtest = [e for e in backtest if t0 is not None and e["timestamp"] >= t0]
    rt = [e for e in rt if t0 is not None and e["timestamp"] >= t0]

    bt_keys = {(e["action"], e["timestamp"]): e for e in backtest}
    rt_keys = {(e["action"], e["timestamp"]): e for e in rt}
    matched = [k for k in bt_keys if k in rt_keys]
    price_dev = [abs(bt_keys[k]["price"] - rt_keys[k]["price"]) / bt_keys[k]["price"] for k in matched]
    return {
        "window": window, "candles": candles, "backtest": len(backtest), "rt": len(rt), "matched": len(matched),
        "matched_keys": sorted(matched, key=lambda k: k[1]),
        "only_backtest": sorted(set(bt_keys) - set(rt_keys), key=lambda k: k[1]),
        "only_rt": sorted(set(rt_keys) - set(bt_keys), key=lambda k: k[1]),
        "max_price_dev": max(price_dev, default=0.0),
        "mean_price_dev": float(np.mean(price_dev)) if price_dev else 0.0,
    }


def format_report(r: dict) -> str:
    lines = [(f"Replay de {r['candles']} velas (ventana RT {r['window']}): backtest {r['backtest']} señales, "
              f"motor RT {r['rt']}, coinciden {r['matched']}")]
    if r["backtest"] == 0:
        lines.append("  Sin operaciones que comparar en el tramo.")
    for title, keys in (("solo en el backtest", r["only_backtest"]), ("solo en el motor RT", r["only_rt"])):
        if keys:
            lines.append(f"  {title} ({len(keys)}): " + ", ".join(f"{a}@{t}" for a, t in keys[:8])
                         + (" ..." if len(keys) > 8 else ""))
    if r["matched"]:
        lines.append(f"  desviación de precio en las coincidentes: media {r['mean_price_dev']:.4%}, "
                     f"máxima {r['max_price_dev']:.4%}")
    return "\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("strategy")
    parser.add_argument("candles", help="CSV con timestamp,open,high,low,close,volume")
    parser.add_argument("--candles-to-replay", type=int, default=1000)
    parser.add_argument("--window", type=int)
    args = parser.parse_args()

    from engine_rt import _buffer_size
    from shared_utils import load_strategy_by_path

    def make():
        return load_strategy_by_path(args.strategy)

    window = args.window or _buffer_size(100, getattr(make(), "WARMUP_PERIOD", 20))
    result = replay_vs_backtest(make, pd.read_csv(args.candles), window, args.candles_to_replay)
    print(format_report(result))
    sys.exit(0 if not (result["only_backtest"] or result["only_rt"]) else 1)


if __name__ == "__main__":
    main()
