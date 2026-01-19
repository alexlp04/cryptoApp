import sys
import json
import importlib.util
import pandas as pd
from datetime import datetime
from strategies.BaseStrategy import BaseStrategy


def load_strategy(path, capital=1000, risk_per_trade=0.02):
    """
    Carga la estrategia desde un fichero Python dado.
    Retorna una instancia de la clase que herede de BaseStrategy.
    """
    spec = importlib.util.spec_from_file_location("user_strategy", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    for obj in module.__dict__.values():
        if isinstance(obj, type) and issubclass(obj, BaseStrategy) and obj is not BaseStrategy:
            return obj(capital=capital, risk_per_trade=risk_per_trade)

    raise Exception(f"No se encontró una estrategia válida en {path}")


def run_backtest(strategy, df, symbol):
    """
    Ejecuta el backtest y devuelve trades y estadísticas.
    """
    trades = []
    in_position = False
    entry_price = 0
    capital = strategy.capital
    capital_history = [capital]
    peak_capital = capital
    max_drawdown = 0

    # Calcula indicadores
    df = strategy.populate_indicators(df)

    for _, row in df.iterrows():
        price = float(row["close"])
        timestamp = int(row["timestamp"])
        # Tamaño de la posición según riesgo
        position_size = strategy.capital * strategy.risk_per_trade / price

        if not in_position and strategy.should_buy(row):
            in_position = True
            entry_price = price
            trades.append({
                "symbol": symbol,
                "side": "BUY",
                "price": price,
                "timestamp": timestamp
            })

        elif in_position and strategy.should_sell(row):
            in_position = False
            pnl = position_size * (price - entry_price)
            capital += pnl
            trades.append({
                "symbol": symbol,
                "side": "SELL",
                "price": price,
                "timestamp": timestamp,
                "pnl": pnl,
                "capital": capital
            })
            capital_history.append(capital)
            peak_capital = max(peak_capital, capital)
            drawdown = peak_capital - capital
            max_drawdown = max(max_drawdown, drawdown)

    # Estadísticas
    op_totales = len(trades) // 2
    op_ganadas = sum(1 for t in trades if t.get("pnl", 0) > 0)
    op_perdidas = sum(1 for t in trades if t.get("pnl", 0) < 0)
    retorno_total = capital - strategy.capital
    retorno_acumulado = (capital - strategy.capital) / strategy.capital * 100
    win_rate = (op_ganadas / op_totales * 100) if op_totales > 0 else 0
    profit_factor = (
        sum(t.get("pnl", 0) for t in trades if t.get("pnl", 0) > 0) /
        (-sum(t.get("pnl", 0) for t in trades if t.get("pnl", 0) < 0) or 1)
    )
    abs_drawdown = max(capital_history) - min(capital_history)
    fecha_inicio = datetime.utcfromtimestamp(df["timestamp"].iloc[0] / 1000).isoformat()
    fecha_fin = datetime.utcfromtimestamp(df["timestamp"].iloc[-1] / 1000).isoformat()
    resultado = "GANANCIA" if retorno_total > 0 else "PERDIDA" if retorno_total < 0 else "NEUTRO"

    stats = {
        "symbol": symbol,
        "timeframe": getattr(strategy, "timeframe", "N/A"),
        "op_ganadas": op_ganadas,
        "op_perdidas": op_perdidas,
        "op_totales": op_totales,
        "max_drawdown": max_drawdown,
        "abs_drawdown": abs_drawdown,
        "retorno_acumulado": retorno_acumulado,
        "retorno_total": retorno_total,
        "win_rate": win_rate,
        "profit_factor": profit_factor,
        "fecha_inicio": fecha_inicio,
        "fecha_fin": fecha_fin,
        "resultado": resultado
    }

    return trades, stats


def main():
    # Leer JSON desde stdin
    try:
        payload = json.load(sys.stdin)
    except json.JSONDecodeError:
        print(json.dumps({"error": "No se pudo leer JSON de entrada"}))
        sys.exit(1)

    # Parámetros de la estrategia
    strategy_path = payload.get("strategy_path")
    timeframe = payload.get("timeframe")
    velas = payload.get("velas", {})
    capital = payload.get("capital", 1000)
    risk_per_trade = payload.get("risk_per_trade", 0.02)

    if not strategy_path or not velas:
        print(json.dumps({"error": "Faltan datos: strategy_path o velas"}))
        sys.exit(1)

    # Cargar estrategia con capital y riesgo
    strategy = load_strategy(strategy_path, capital=capital, risk_per_trade=risk_per_trade)
    setattr(strategy, "timeframe", timeframe)

    all_trades = []
    stats_list = []

    for symbol, candles in velas.items():
        if not candles:
            continue

        df = pd.DataFrame(candles)
        if "close" not in df.columns or "timestamp" not in df.columns:
            print(json.dumps({"error": f"Las velas de {symbol} no tienen 'close' o 'timestamp'"}))
            continue

        df["close"] = pd.to_numeric(df["close"], errors="coerce")
        df["timestamp"] = pd.to_numeric(df["timestamp"], errors="coerce")
        df = df.dropna(subset=["close", "timestamp"])

        trades, stats = run_backtest(strategy, df, symbol)
        all_trades.extend(trades)
        stats_list.append(stats)

    # Salida final JSON
    print(json.dumps({
        "total_trades": len(all_trades),
        "trades": all_trades,
        "stats": stats_list
    }))


if __name__ == "__main__":
    main()
