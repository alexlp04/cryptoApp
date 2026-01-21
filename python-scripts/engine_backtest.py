import sys
import json
import importlib.util
import pandas as pd
import os
from datetime import datetime

# --- CONFIGURACIÓN DE RUTAS ---
current_dir = os.path.dirname(os.path.abspath(__file__))
project_root = os.path.dirname(current_dir)
if project_root not in sys.path:
    sys.path.append(project_root)

from strategies.BaseStrategy import BaseStrategy

def load_strategy(path, capital=1000, risk_per_trade=0.02):
    spec = importlib.util.spec_from_file_location("user_strategy", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    for obj in module.__dict__.values():
        if isinstance(obj, type) and issubclass(obj, BaseStrategy) and obj is not BaseStrategy:
            return obj(capital=capital, risk_per_trade=risk_per_trade)

    raise Exception(f"No se encontró una estrategia válida en {path}")

def run_backtest(strategy, df, symbol):
    trades = []
    in_position = False
    entry_price = 0
    capital = strategy.capital
    capital_history = [capital]
    peak_capital = capital
    max_drawdown = 0

    df = strategy.populate_indicators(df)

    for _, row in df.iterrows():
        price = float(row["close"])
        timestamp = int(row["timestamp"])
        position_size = strategy.capital * strategy.risk_per_trade / price

        if not in_position and strategy.should_buy(row):
            in_position = True
            entry_price = price
            trades.append({
                "symbol": symbol, "side": "BUY", "price": price, "timestamp": timestamp
            })

        elif in_position and strategy.should_sell(row):
            in_position = False
            pnl = position_size * (price - entry_price)
            capital += pnl
            trades.append({
                "symbol": symbol, "side": "SELL", "price": price, 
                "timestamp": timestamp, "pnl": pnl, "capital": capital
            })
            capital_history.append(capital)
            peak_capital = max(peak_capital, capital)
            max_drawdown = max(max_drawdown, peak_capital - capital)

    # --- LÓGICA DE ESTADÍSTICAS PROTEGIDA (Mínimo 0) ---
    op_totales = len(trades) // 2
    op_ganadas = sum(1 for t in trades if t.get("pnl", 0) > 0)
    op_perdidas = sum(1 for t in trades if t.get("pnl", 0) < 0)
    
    retorno_total = capital - strategy.capital
    retorno_acumulado = (retorno_total / strategy.capital * 100) if strategy.capital > 0 else 0.0
    
    # Win Rate: 0 si no hay operaciones
    win_rate = (op_ganadas / op_totales * 100) if op_totales > 0 else 0.0
    
    # Profit Factor: Suma de ganancias / Suma de pérdidas
    pos_pnl = sum(t.get("pnl", 0) for t in trades if t.get("pnl", 0) > 0)
    neg_pnl = abs(sum(t.get("pnl", 0) for t in trades if t.get("pnl", 0) < 0))
    
    if op_totales == 0:
        profit_factor = 0.0
    elif neg_pnl == 0:
        profit_factor = float(pos_pnl) # Si no hay pérdidas, el PF es el total ganado
    else:
        profit_factor = pos_pnl / neg_pnl

    abs_drawdown = max(capital_history) - min(capital_history) if len(capital_history) > 0 else 0.0
    
    # Fechas de seguridad
    fecha_inicio = datetime.utcfromtimestamp(df["timestamp"].iloc[0] / 1000).isoformat() if not df.empty else "N/A"
    fecha_fin = datetime.utcfromtimestamp(df["timestamp"].iloc[-1] / 1000).isoformat() if not df.empty else "N/A"
    
    resultado = "NEUTRO"
    if retorno_total > 0: resultado = "GANANCIA"
    elif retorno_total < 0: resultado = "PERDIDA"

    stats = {
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
        "resultado": resultado
    }

    return trades, stats

def main():
    try:
        # 1. Leer entrada
        input_data = sys.stdin.read()
        if not input_data:
            return # Salir silenciosamente si no hay entrada

        payload = json.loads(input_data)
        strategy_path = payload.get("strategy_path")
        timeframe = payload.get("timeframe")
        velas = payload.get("velas", {})
        capital = payload.get("capital", 1000)
        risk_per_trade = payload.get("risk_per_trade", 0.02)

        # 2. Cargar estrategia
        strategy = load_strategy(strategy_path, capital=capital, risk_per_trade=risk_per_trade)
        setattr(strategy, "timeframe", timeframe)

        all_trades = []
        stats_list = []

        # 3. Procesar símbolos
        for symbol, candles in velas.items():
            if not candles: continue
            df = pd.DataFrame(candles)
            df["close"] = pd.to_numeric(df["close"], errors="coerce")
            df["timestamp"] = pd.to_numeric(df["timestamp"], errors="coerce")
            df = df.dropna(subset=["close", "timestamp"])
            if df.empty: continue

            trades, stats = run_backtest(strategy, df, symbol)
            all_trades.extend(trades)
            stats_list.append(stats)

        # 4. Éxito
        print(json.dumps({
            "status": "success",
            "total_trades": len(all_trades),
            "trades": all_trades,
            "stats": stats_list
        }))

    except Exception as e:
        # CAPTURA DE ERROR: Enviamos el error a la salida estándar para Java
        # Usamos sys.stderr para errores críticos de sistema y print para errores controlados
        error_msg = f"ERROR POR EL ENGINE: {str(e)}\n{traceback.format_exc()}"
        print(error_msg, file=sys.stderr)
        sys.exit(1) # Salida con error

if __name__ == "__main__":
    main()