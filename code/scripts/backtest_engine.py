"""
backtest_engine.py — Motor compartido de simulación de trading.

Contiene la lógica central de backtesting extraída de engine_backtest.py para
ser reutilizada por otros engines (engine_optimize.py, engine_train.py, etc.)
sin duplicar código.

Funciones públicas:
  - crear_carpeta_estrategia(nombre_estrategia) -> str
  - guardar_trade_a_csv(carpeta, symbol, timeframe, trade) -> None
  - run_backtest(strategy, df, symbol, carpeta, timeframe, escribir_trades) -> tuple
  - run_backtest_with_predictions(strategy, df, predictions, symbol) -> dict
"""
from __future__ import annotations

import csv
import logging
import os
import sys
from collections.abc import Callable
from datetime import UTC, datetime
from typing import Any

import numpy as np
import pandas as pd
from risk_management import position_size, strategy_levels

# =============================================================================
# CONFIGURACIÓN DE RUTAS
# =============================================================================
current_dir = os.path.dirname(os.path.abspath(__file__))
code_dir = os.path.dirname(current_dir)
project_root = os.path.dirname(code_dir)

results_root = os.path.join(project_root, "results")
os.makedirs(results_root, exist_ok=True)

if code_dir not in sys.path:
    sys.path.insert(0, code_dir)


logger = logging.getLogger(__name__)

# Capital y riesgo por defecto cuando la estrategia no tiene valores explícitos
_DEFAULT_CAPITAL: float = 10_000.0
_DEFAULT_RISK: float = 0.02

# Costes de ejecución por defecto (Binance spot: 0,1 % de comisión por lado y 0,05 % de slippage).
# Sin ellos el backtest sobrestima el rendimiento, sobre todo en timeframes cortos.
DEFAULT_FEE_RATE: float = 0.001
DEFAULT_SLIPPAGE: float = 0.0005
#: profit_factor cuando no hay pérdidas (inf no se serializa bien en JSON/CSV).
PROFIT_FACTOR_CAP: float = 999.0
_MS_PER_YEAR: float = 365 * 24 * 3600 * 1000

# Vocabulario del campo 'resultado' del CSV de stats. Este modulo es la fuente de
# verdad: el paper trading en vivo (PaperTradingService) escribe en el mismo fichero
# y replica estos valores en AppConstants.RESULTADO_*. Mantenerlos sincronizados.
RESULTADO_GANANCIA: str = "GANANCIA"
RESULTADO_PERDIDA: str = "PERDIDA"
RESULTADO_NEUTRO: str = "NEUTRO"


# =============================================================================
# HELPERS PRIVADOS DE GESTIÓN DE POSICIÓN
# =============================================================================

def _protective_exit(
    open_: float, high: float, low: float, stop: float, take_profit: float,
) -> tuple[float, bool] | None:
    """Salida por stop o por objetivo dentro de la vela: (precio, aplica_slippage), o None.

    Orden pesimista: si la misma vela toca el stop y el objetivo, se asume que saltó primero el stop
    (con velas no se sabe el orden real). Un hueco más allá del nivel se ejecuta a la apertura:
    el stop es una orden a mercado (con slippage), el objetivo una orden límite (sin él).
    """
    if low <= stop:
        return min(open_, stop), True
    if high >= take_profit:
        return max(open_, take_profit), False
    return None


def _close_long(
    capital: float,
    position_size: float,
    entry_price: float,
    exit_price: float,
    op_ganadas: int,
    op_perdidas: int,
    pos_pnl: float,
    neg_pnl: float,
    peak_capital: float,
    max_drawdown: float,
    fee: float = 0.0,
) -> tuple[float, int, int, float, float, float, float, float]:
    """
    Cierra un long y actualiza todos los acumuladores del backtest.
    fee: comisión total (entrada + salida) en dinero, ya calculada por el llamador.
    Devuelve: (capital, op_ganadas, op_perdidas, pos_pnl, neg_pnl,
               peak_capital, max_drawdown, pnl)
    """
    pnl = position_size * (exit_price - entry_price) - fee
    capital += pnl
    peak_capital = max(peak_capital, capital)
    max_drawdown = max(max_drawdown, peak_capital - capital)
    if pnl > 0:
        op_ganadas += 1
        pos_pnl += pnl
    elif pnl < 0:
        op_perdidas += 1
        neg_pnl += abs(pnl)
    return capital, op_ganadas, op_perdidas, pos_pnl, neg_pnl, peak_capital, max_drawdown, pnl


# =============================================================================
# UTILIDADES DE CARPETA Y CSV
# =============================================================================

def crear_carpeta_estrategia(nombre_estrategia: str) -> str:
    """Crea la carpeta para resultados de la estrategia y devuelve la ruta."""
    estrategia_dir = os.path.join(results_root, nombre_estrategia)
    os.makedirs(estrategia_dir, exist_ok=True)
    return estrategia_dir


def guardar_trade_a_csv(carpeta_estrategia: str, symbol: str, timeframe: str, trade: dict) -> None:
    """Escribe un trade individual a CSV en streaming (sin acumular en memoria)."""
    archivo = os.path.join(carpeta_estrategia, f"{symbol}-{timeframe}-trades_backtest.csv")
    archivo_existe = os.path.exists(archivo)

    try:
        with open(archivo, "a", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            if not archivo_existe:
                writer.writerow(["symbol", "timeframe", "side", "price",
                                  "timestamp", "pnl", "capital"])
            writer.writerow([
                symbol,
                timeframe,
                trade.get("side", ""),
                trade.get("price", ""),
                trade.get("timestamp", ""),
                trade.get("pnl", ""),
                trade.get("capital", ""),
            ])
    except OSError as exc:
        logger.error("Error escribiendo trade a CSV: %s", exc)
        raise


# =============================================================================
# BUCLE ÚNICO DE SIMULACIÓN
# =============================================================================

def _simulate(
    strategy,
    df: pd.DataFrame,
    symbol: str,
    entry_signal: Callable[[int, Callable[[], dict]], bool],
    exit_signal: Callable[[int, Callable[[], dict], float], bool],
    fee_rate: float,
    slippage: float,
    on_trade: Callable[[str, float, int, float | None, float | None], None] | None = None,
) -> tuple[int, dict[str, Any]]:
    """
    Simula longs sobre df con las señales dadas. Es el único bucle de backtesting:
    el modo clásico y el modo IA solo difieren en entry_signal / exit_signal, así que
    comparten costes, cierre de la posición final y métricas.

    Las señales reciben un callable que materializa la fila como dict solo si lo piden
    (el modo IA no lo necesita mientras está fuera de posición).
    """
    capital = float(getattr(strategy, "capital", _DEFAULT_CAPITAL))
    risk_per_trade = float(getattr(strategy, "risk_per_trade", _DEFAULT_RISK))
    capital_history = [capital]
    equity: list[float] = []
    cols = df.columns.tolist()
    close_idx = cols.index("close")
    ts_idx = cols.index("timestamp") if "timestamp" in cols else None
    ohl_idx = [cols.index(c) for c in ("open", "high", "low")] if {"open", "high", "low"} <= set(cols) else None
    skipped_no_stop = 0
    stop_price = take_profit = 0.0
    in_position = False
    entry_price = size = 0.0
    entry_fill = 0.0
    peak_capital = capital
    max_drawdown = 0.0
    trade_count = 0
    op_ganadas = op_perdidas = 0
    pos_pnl = neg_pnl = 0.0
    price = 0.0
    timestamp = 0

    def close_position(price: float, timestamp: int, slip: float = slippage) -> None:
        nonlocal capital, op_ganadas, op_perdidas, pos_pnl, neg_pnl, peak_capital, max_drawdown
        nonlocal trade_count, in_position
        exit_fill = price * (1 - slip)
        fee = fee_rate * size * (entry_fill + exit_fill)
        (capital, op_ganadas, op_perdidas, pos_pnl, neg_pnl,
         peak_capital, max_drawdown, pnl) = _close_long(
            capital, size, entry_fill, exit_fill,
            op_ganadas, op_perdidas, pos_pnl, neg_pnl, peak_capital, max_drawdown, fee,
        )
        in_position = False
        trade_count += 1
        capital_history.append(capital)
        if on_trade:
            on_trade("SELL", price, timestamp, pnl, capital)

    # tuplas planas: sin renombrado de columnas y sin dict por vela salvo que se pida
    for i, vals in enumerate(df.itertuples(index=False, name=None)):
        def row(vals=vals) -> dict:
            return dict(zip(cols, vals, strict=True))

        price = float(vals[close_idx])
        timestamp = int(vals[ts_idx]) if ts_idx is not None else 0

        if not in_position:
            if entry_signal(i, row):
                levels = strategy_levels(strategy, price, row())
                entry_fill = price * (1 + slippage)
                size = position_size(capital, risk_per_trade, entry_fill, levels[0]) if levels else 0.0
                if size > 0:
                    in_position = True
                    entry_price = price
                    stop_price, take_profit = levels  # type: ignore[misc]
                    trade_count += 1
                    if on_trade:
                        on_trade("BUY", price, timestamp, None, None)
                else:
                    skipped_no_stop += 1
        else:
            # Stop y objetivo son órdenes que viven en el mercado: se evalúan con el rango de la vela,
            # no solo con su cierre. La señal de la estrategia se evalúa al cierre, como antes.
            hit = None
            if ohl_idx is not None:
                hit = _protective_exit(float(vals[ohl_idx[0]]), float(vals[ohl_idx[1]]),
                                       float(vals[ohl_idx[2]]), stop_price, take_profit)
            if hit is not None:
                close_position(hit[0], timestamp, slippage if hit[1] else 0.0)
            elif exit_signal(i, row, entry_price):
                close_position(price, timestamp)

        equity.append(capital + size * (price * (1 - slippage) - entry_fill) if in_position else capital)

    if skipped_no_stop:
        logger.warning("%s: %d señales de compra descartadas porque la estrategia no dio un stop válido "
                       "(por debajo de la entrada): sin stop no se puede dimensionar el riesgo",
                       symbol, skipped_no_stop)

    # Posición abierta al terminar: se cierra al último close (mismo criterio en ambos modos)
    if in_position and len(df) > 0:
        close_position(price, timestamp)
        equity[-1] = capital

    timestamps = df["timestamp"].to_numpy() if "timestamp" in df.columns else None
    stats = _calculate_backtest_stats(
        strategy, capital, capital_history, max_drawdown,
        df, symbol, trade_count, op_ganadas, op_perdidas, pos_pnl, neg_pnl,
        equity=np.asarray(equity, dtype=float), timestamps=timestamps,
    )
    return trade_count, stats


# =============================================================================
# MOTOR DE BACKTESTING (versión completa con señales de estrategia)
# =============================================================================

def run_backtest(
    strategy,
    df: pd.DataFrame,
    symbol: str,
    carpeta_estrategia: str | None = None,
    timeframe: str | None = None,
    escribir_trades: bool = False,
    fee_rate: float = DEFAULT_FEE_RATE,
    slippage: float = DEFAULT_SLIPPAGE,
) -> tuple[int, dict]:
    """
    Ejecuta la simulación de trading sobre datos históricos usando las señales
    de la estrategia (should_buy / should_sell) y su should_close (SL/TP).

    Llama internamente a strategy.populate_indicators(df).
    Si escribir_trades=True escribe trades a CSV en streaming.
    fee_rate / slippage: coste por lado (fracción). Pasar 0 para un backtest sin costes.

    Devuelve: (trade_count, stats_dict)
    """
    if df is None or df.empty:
        return 0, _generate_empty_stats(symbol, strategy)

    df = strategy.populate_indicators(df)

    def on_trade(side: str, price: float, timestamp: int, pnl: float | None, cap: float | None) -> None:
        if not (escribir_trades and carpeta_estrategia and timeframe):
            return
        trade: dict[str, Any] = {"symbol": symbol, "side": side, "price": price, "timestamp": timestamp}
        if pnl is not None:
            trade.update(pnl=pnl, capital=cap)
        guardar_trade_a_csv(carpeta_estrategia, symbol, timeframe, trade)

    return _simulate(
        strategy, df, symbol,
        entry_signal=lambda i, row: bool(strategy.should_buy(row())),
        exit_signal=lambda i, row, entry: bool(
            strategy.should_sell(row()) or strategy.should_close(row(), entry)),
        fee_rate=fee_rate, slippage=slippage, on_trade=on_trade,
    )


# =============================================================================
# MOTOR DE BACKTESTING CON PREDICCIONES ML
# =============================================================================

def run_backtest_with_predictions(
    strategy,
    df: pd.DataFrame,
    predictions: np.ndarray,
    symbol: str,
    fee_rate: float = DEFAULT_FEE_RATE,
    slippage: float = DEFAULT_SLIPPAGE,
) -> dict[str, Any]:
    """
    Simula trades usando predicciones de un modelo ML sobre un DataFrame ya
    enriquecido con indicadores.

    A diferencia de run_backtest:
      - NO llama strategy.populate_indicators (df ya tiene los indicadores).
      - NO escribe trades a CSV.
      - Usa predictions[i] == 1 (etiqueta original) como señal de compra.
      - Cierra posición si predictions[i] != 1 O si strategy.should_close() es True.

    Parámetros
    ----------
    strategy : instancia de BaseStrategy (con capital y risk_per_trade).
    df       : DataFrame con columnas timestamp, close, y todos los indicadores.
               Debe estar ordenado por timestamp. NO debe llamarse populate_indicators
               sobre él antes de pasarlo aquí.
    predictions : array de etiquetas en el espacio original (−1, 0, 1),
                  con len(predictions) == len(df).
    symbol   : ticker (para el informe de stats).

    Devuelve
    --------
    dict con las mismas claves que run_backtest (incluye "sharpe").
    Si df está vacío devuelve _generate_empty_stats.
    """
    if df is None or df.empty:
        return _generate_empty_stats(symbol, strategy)

    n_predictions = len(predictions)
    n_rows = len(df)
    if n_predictions != n_rows:
        logger.warning(
            "Longitud de predictions (%d) != filas de df (%d) para %s. "
            "Se recortará al mínimo.",
            n_predictions, n_rows, symbol,
        )
        min_len = min(n_predictions, n_rows)
        predictions = predictions[:min_len]
        df = df.iloc[:min_len].copy()

    preds = np.asarray(predictions).astype(int)
    _, stats = _simulate(
        strategy, df, symbol,
        entry_signal=lambda i, row: preds[i] == 1,
        exit_signal=lambda i, row, entry: bool(preds[i] != 1 or strategy.should_close(row(), entry)),
        fee_rate=fee_rate, slippage=slippage,
    )
    return stats


# =============================================================================
# CÁLCULO DE ESTADÍSTICAS (función interna, compartida)
# =============================================================================

def _equity_metrics(
    equity: np.ndarray, timestamps: np.ndarray | None, initial_capital: float,
) -> tuple[float, float, float, float]:
    """(max_drawdown en dinero, max_drawdown en %, abs_drawdown, sharpe anualizado) de la curva por vela."""
    if len(equity) == 0:
        return 0.0, 0.0, 0.0, 0.0
    peak = np.maximum.accumulate(equity)
    drawdown = peak - equity
    with np.errstate(divide="ignore", invalid="ignore"):
        dd_pct = np.where(peak > 0, drawdown / peak, 0.0)
    abs_drawdown = max(0.0, initial_capital - float(equity.min()))

    sharpe = 0.0
    if len(equity) > 2:
        prev = equity[:-1]
        returns = np.diff(equity) / np.where(prev != 0, prev, 1.0)
        std = float(returns.std(ddof=1))
        if std > 1e-12:
            periods_per_year = 1.0
            if timestamps is not None and len(timestamps) > 1:
                step = float(np.median(np.diff(timestamps.astype(float))))
                if step > 0:
                    periods_per_year = _MS_PER_YEAR / step
            sharpe = float(returns.mean() / std * np.sqrt(periods_per_year))
    return float(drawdown.max()), float(dd_pct.max() * 100), abs_drawdown, sharpe


def _calculate_backtest_stats(
    strategy,
    final_capital: float,
    capital_history: list[float],
    max_drawdown: float,
    df: pd.DataFrame,
    symbol: str,
    trade_count: int,
    op_ganadas: int,
    op_perdidas: int,
    pos_pnl: float,
    neg_pnl: float,
    equity: np.ndarray | None = None,
    timestamps: np.ndarray | None = None,
) -> dict[str, Any]:
    """Procesa las métricas de rendimiento usando los acumuladores calculados en caliente.

    Con la curva de equity por vela (equity) el drawdown incluye las pérdidas no realizadas
    y el Sharpe se anualiza; sin ella se usan los cierres de capital_history.
    """
    op_totales = max(trade_count // 2, 0)
    initial_capital = float(getattr(strategy, "capital", _DEFAULT_CAPITAL))

    retorno_total = final_capital - initial_capital
    retorno_acumulado = (retorno_total / initial_capital * 100) if initial_capital > 0 else 0.0
    if equity is not None and len(equity) > 0:
        max_drawdown, max_drawdown_pct, abs_drawdown, sharpe = _equity_metrics(
            equity, timestamps, initial_capital)
    else:
        abs_drawdown = max(0.0, initial_capital - min(capital_history)) if capital_history else 0.0
        peak = max(capital_history) if capital_history else 0.0
        max_drawdown_pct = (max_drawdown / peak * 100) if peak > 0 else 0.0
        sharpe = 0.0

    win_rate = (op_ganadas / op_totales * 100) if op_totales > 0 else 0.0
    if op_totales == 0:
        profit_factor = 0.0
    elif neg_pnl == 0:
        profit_factor = PROFIT_FACTOR_CAP if pos_pnl > 0 else 0.0
    else:
        profit_factor = min(pos_pnl / neg_pnl, PROFIT_FACTOR_CAP)

    fecha_inicio = "N/A"
    fecha_fin = "N/A"
    if not df.empty and "timestamp" in df.columns:
        fecha_inicio = datetime.fromtimestamp(
            df["timestamp"].iloc[0] / 1000, UTC
        ).isoformat()
        fecha_fin = datetime.fromtimestamp(
            df["timestamp"].iloc[-1] / 1000, UTC
        ).isoformat()

    resultado = RESULTADO_NEUTRO
    if retorno_total > 0:
        resultado = RESULTADO_GANANCIA
    elif retorno_total < 0:
        resultado = RESULTADO_PERDIDA

    return {
        "symbol": symbol,
        "timeframe": getattr(strategy, "timeframe", "N/A"),
        "op_ganadas": int(op_ganadas),
        "op_perdidas": int(op_perdidas),
        "op_totales": int(op_totales),
        # En dinero (unidad del capital); max_drawdown_pct es el mismo valor en % del pico.
        "max_drawdown": float(max_drawdown),
        "max_drawdown_pct": float(max_drawdown_pct),
        # Capital inicial menos el mínimo alcanzado (drawdown absoluto clásico).
        "abs_drawdown": float(abs_drawdown),
        "retorno_acumulado": float(retorno_acumulado),
        "retorno_total": float(retorno_total),
        "win_rate": float(win_rate),
        "profit_factor": float(profit_factor),
        "sharpe": round(float(sharpe), 6),
        "fecha_inicio": fecha_inicio,
        "fecha_fin": fecha_fin,
        "resultado": resultado,
    }


def _generate_empty_stats(symbol: str, strategy) -> dict[str, Any]:
    """Devuelve estadísticas vacías cuando no hay datos suficientes."""
    return {
        "symbol": symbol,
        "timeframe": getattr(strategy, "timeframe", "N/A"),
        "op_ganadas": 0,
        "op_perdidas": 0,
        "op_totales": 0,
        "max_drawdown": 0.0,
        "max_drawdown_pct": 0.0,
        "abs_drawdown": 0.0,
        "retorno_acumulado": 0.0,
        "retorno_total": 0.0,
        "win_rate": 0.0,
        "profit_factor": 0.0,
        "sharpe": 0.0,
        "fecha_inicio": "N/A",
        "fecha_fin": "N/A",
        "resultado": RESULTADO_NEUTRO,
    }
