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
from datetime import UTC, datetime
from typing import Any

import numpy as np
import pandas as pd

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


# =============================================================================
# HELPERS PRIVADOS DE GESTIÓN DE POSICIÓN
# =============================================================================

def _open_long(capital: float, risk_per_trade: float, price: float) -> float:
    """Calcula el tamaño de posición al abrir un long."""
    return (capital * risk_per_trade) / price


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
) -> tuple[float, int, int, float, float, float, float, float]:
    """
    Cierra un long y actualiza todos los acumuladores del backtest.
    Devuelve: (capital, op_ganadas, op_perdidas, pos_pnl, neg_pnl,
               peak_capital, max_drawdown, pnl)
    """
    pnl = position_size * (exit_price - entry_price)
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
# MOTOR DE BACKTESTING (versión completa con señales de estrategia)
# =============================================================================

def run_backtest(
    strategy,
    df: pd.DataFrame,
    symbol: str,
    carpeta_estrategia: str | None = None,
    timeframe: str | None = None,
    escribir_trades: bool = False,
) -> tuple[int, dict]:
    """
    Ejecuta la simulación de trading sobre datos históricos usando las señales
    de la estrategia (should_buy / should_sell).

    Llama internamente a strategy.populate_indicators(df).
    Si escribir_trades=True escribe trades a CSV en streaming.

    Devuelve: (trade_count, stats_dict)
    """
    if df is None or df.empty:
        return 0, _generate_empty_stats(symbol, strategy)

    df = strategy.populate_indicators(df)

    capital = float(strategy.capital)
    risk_per_trade = float(strategy.risk_per_trade)
    capital_history = [capital]
    in_position = False
    entry_price = 0.0
    current_position_size = 0.0
    peak_capital = capital
    max_drawdown = 0.0
    trade_count = 0
    op_ganadas = op_perdidas = 0
    pos_pnl = neg_pnl = 0.0

    for row in df.itertuples(index=False):
        row_data = row._asdict()
        price = float(row_data["close"])
        timestamp = int(row_data["timestamp"])

        if not in_position and strategy.should_buy(row_data):
            in_position = True
            entry_price = price
            current_position_size = _open_long(capital, risk_per_trade, price)
            trade_count += 1
            if escribir_trades and carpeta_estrategia and timeframe:
                guardar_trade_a_csv(carpeta_estrategia, symbol, timeframe,
                                    {"symbol": symbol, "side": "BUY", "price": price, "timestamp": timestamp})

        elif in_position and strategy.should_sell(row_data):
            in_position = False
            (capital, op_ganadas, op_perdidas, pos_pnl, neg_pnl,
             peak_capital, max_drawdown, pnl) = _close_long(
                capital, current_position_size, entry_price, price,
                op_ganadas, op_perdidas, pos_pnl, neg_pnl, peak_capital, max_drawdown,
            )
            trade_count += 1
            capital_history.append(capital)
            if escribir_trades and carpeta_estrategia and timeframe:
                guardar_trade_a_csv(carpeta_estrategia, symbol, timeframe,
                                    {"symbol": symbol, "side": "SELL", "price": price,
                                     "timestamp": timestamp, "pnl": pnl, "capital": capital})

    stats = _calculate_backtest_stats(
        strategy, capital, capital_history, max_drawdown,
        df, symbol, trade_count, op_ganadas, op_perdidas, pos_pnl, neg_pnl,
    )
    return trade_count, stats


# =============================================================================
# MOTOR DE BACKTESTING CON PREDICCIONES ML
# =============================================================================

def run_backtest_with_predictions(
    strategy,
    df: pd.DataFrame,
    predictions: np.ndarray,
    symbol: str,
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
    dict con las mismas claves que _calculate_backtest_stats más "sharpe".
    Si df está vacío devuelve _generate_empty_stats con sharpe=0.0.
    """
    if df is None or df.empty:
        empty = _generate_empty_stats(symbol, strategy)
        empty["sharpe"] = 0.0
        return empty

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

    capital = float(getattr(strategy, "capital", _DEFAULT_CAPITAL))
    risk_per_trade = float(getattr(strategy, "risk_per_trade", _DEFAULT_RISK))
    capital_history = [capital]
    in_position = False
    entry_price = 0.0
    current_position_size = 0.0
    peak_capital = capital
    max_drawdown = 0.0
    trade_count = 0
    op_ganadas = op_perdidas = 0
    pos_pnl = neg_pnl = 0.0

    for i, row in enumerate(df.itertuples(index=False)):
        price = float(row.close)
        pred = int(predictions[i])

        if not in_position and pred == 1:
            in_position = True
            entry_price = price
            current_position_size = _open_long(capital, risk_per_trade, price)
            trade_count += 1

        # row._asdict() solo se materializa si de verdad hay que consultar la estrategia:
        # esta funcion es el bucle caliente de Optuna (una pasada por trial).
        elif in_position and (pred != 1 or strategy.should_close(row._asdict(), entry_price)):
            in_position = False
            (capital, op_ganadas, op_perdidas, pos_pnl, neg_pnl,
             peak_capital, max_drawdown, _) = _close_long(
                capital, current_position_size, entry_price, price,
                op_ganadas, op_perdidas, pos_pnl, neg_pnl, peak_capital, max_drawdown,
            )
            trade_count += 1
            capital_history.append(capital)

    # Cerrar posición abierta al final del periodo
    if in_position and n_rows > 0:
        price = float(df.iloc[-1]["close"])
        (capital, op_ganadas, op_perdidas, pos_pnl, neg_pnl,
         peak_capital, max_drawdown, _) = _close_long(
            capital, current_position_size, entry_price, price,
            op_ganadas, op_perdidas, pos_pnl, neg_pnl, peak_capital, max_drawdown,
        )
        trade_count += 1
        capital_history.append(capital)

    stats = _calculate_backtest_stats(
        strategy, capital, capital_history, max_drawdown,
        df, symbol, trade_count, op_ganadas, op_perdidas, pos_pnl, neg_pnl,
    )

    # Calcular Sharpe sobre la curva de capital
    sharpe = 0.0
    if len(capital_history) > 2:
        arr = np.array(capital_history, dtype=float)
        returns = np.diff(arr) / np.where(arr[:-1] != 0, arr[:-1], 1.0)
        if returns.std() > 1e-9:
            sharpe = float(returns.mean() / returns.std())
    stats["sharpe"] = round(sharpe, 6)

    return stats


# =============================================================================
# CÁLCULO DE ESTADÍSTICAS (función interna, compartida)
# =============================================================================

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
) -> dict[str, Any]:
    """Procesa las métricas de rendimiento usando los acumuladores calculados en caliente."""
    op_totales = max(trade_count // 2, 0)
    initial_capital = float(getattr(strategy, "capital", _DEFAULT_CAPITAL))

    retorno_total = final_capital - initial_capital
    retorno_acumulado = (retorno_total / initial_capital * 100) if initial_capital > 0 else 0.0
    abs_drawdown = max(capital_history) - min(capital_history) if capital_history else 0.0

    win_rate = (op_ganadas / op_totales * 100) if op_totales > 0 else 0.0
    if op_totales == 0:
        profit_factor = 0.0
    elif neg_pnl == 0:
        profit_factor = float(pos_pnl)
    else:
        profit_factor = pos_pnl / neg_pnl

    fecha_inicio = "N/A"
    fecha_fin = "N/A"
    if not df.empty and "timestamp" in df.columns:
        fecha_inicio = datetime.fromtimestamp(
            df["timestamp"].iloc[0] / 1000, UTC
        ).isoformat()
        fecha_fin = datetime.fromtimestamp(
            df["timestamp"].iloc[-1] / 1000, UTC
        ).isoformat()

    resultado = "NEUTRO"
    if retorno_total > 0:
        resultado = "GANANCIA"
    elif retorno_total < 0:
        resultado = "PERDIDA"

    return {
        "symbol": symbol,
        "timeframe": getattr(strategy, "timeframe", "N/A"),
        "op_ganadas": int(op_ganadas),
        "op_perdidas": int(op_perdidas),
        "op_totales": int(op_totales),
        "max_drawdown": float(max_drawdown),
        "abs_drawdown": float(abs_drawdown),
        "retorno_acumulado": float(retorno_acumulado),
        "retorno_total": float(retorno_total),
        "win_rate": float(win_rate),
        "profit_factor": float(profit_factor),
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
        "abs_drawdown": 0.0,
        "retorno_acumulado": 0.0,
        "retorno_total": 0.0,
        "win_rate": 0.0,
        "profit_factor": 0.0,
        "fecha_inicio": "N/A",
        "fecha_fin": "N/A",
        "resultado": "NEUTRO",
    }
