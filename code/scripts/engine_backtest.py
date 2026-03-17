import sys
import json
import importlib.util
import pandas as pd
import os
import csv
from datetime import datetime, timezone
import traceback
import logging
from ipc_protocol import read_request_payload, write_response, write_error

# --- CONFIGURACIÓN DE LOGS ---
current_dir = os.path.dirname(os.path.abspath(__file__))
project_root = os.path.dirname(current_dir)
log_dir = os.path.join(project_root, "logs")
os.makedirs(log_dir, exist_ok=True)

log_file = os.path.join(log_dir, "engine_backtest.log")

# Logger a archivo (para debugging offline)
file_handler = logging.FileHandler(log_file, encoding='utf-8')
file_handler.setLevel(logging.DEBUG)
file_handler.setFormatter(logging.Formatter('%(asctime)s [%(levelname)s] %(message)s'))

# Logger a stderr para no contaminar stdout, reservado al frame MessagePack IPC.
stream_handler = logging.StreamHandler(sys.stderr)
stream_handler.setLevel(logging.INFO)
stream_handler.setFormatter(logging.Formatter('[%(levelname)s] %(message)s'))

logging.basicConfig(
    level=logging.DEBUG,
    handlers=[file_handler, stream_handler]
)

# --- CONFIGURACIÓN DE DIRECTORIO DE RESULTADOS ---
results_root = os.path.join(project_root, "results")
os.makedirs(results_root, exist_ok=True)

# --- CONFIGURACIÓN DE RUTAS ---
if project_root not in sys.path:
    sys.path.append(project_root)

from strategies.BaseStrategy import BaseStrategy

def crear_carpeta_estrategia(nombre_estrategia: str) -> str:
    """
    Crea la carpeta para resultados de la estrategia y devuelve la ruta.
    """
    estrategia_dir = os.path.join(results_root, nombre_estrategia)
    os.makedirs(estrategia_dir, exist_ok=True)
    return estrategia_dir

def guardar_trade_a_csv(carpeta_estrategia: str, symbol: str, timeframe: str, trade: dict) -> None:
     """
     Escribe un trade individual a CSV en streaming (sin acumular en memoria).
     """
     archivo = os.path.join(carpeta_estrategia, f"{symbol}-{timeframe}-trades_backtest.csv")
   
     archivo_existe = os.path.exists(archivo)
   
     try:
         with open(archivo, 'a', newline='', encoding='utf-8') as f:
             writer = csv.writer(f)
           
             if not archivo_existe:
                 writer.writerow(["symbol", "timeframe", "side", "price", "timestamp", "pnl", "capital"])
           
             writer.writerow([
                 symbol,
                 timeframe,
                 trade.get("side", ""),
                 trade.get("price", ""),
                 trade.get("timestamp", ""),
                 trade.get("pnl", ""),
                 trade.get("capital", "")
             ])
     except IOError as e:
         logging.error(f"Error escribiendo trade a CSV: {e}")
         raise

def load_strategy(path, capital=1000, risk_per_trade=0.02):
    spec = importlib.util.spec_from_file_location("user_strategy", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    for obj in module.__dict__.values():
        if isinstance(obj, type) and issubclass(obj, BaseStrategy) and obj is not BaseStrategy:
            return obj(capital=capital, risk_per_trade=risk_per_trade)

    raise Exception(f"Valid strategy not found at {path}")

def run_backtest(strategy, df: pd.DataFrame, symbol: str, carpeta_estrategia: str = None, timeframe: str = None, escribir_trades: bool = False) -> tuple:
    """
    Ejecuta la simulación de trading sobre datos históricos.
    Si escribir_trades=True, escribe trades a CSV en streaming (sin acumular en memoria).
    Devuelve: (trade_count, stats_dict)
    """
    if df is None or df.empty:
        return 0, _generate_empty_stats(symbol, strategy)

    df = strategy.populate_indicators(df)

    trade_count = 0
    capital = strategy.capital
    capital_history = [capital]
    
    in_position = False
    entry_price = 0.0
    current_position_size = 0.0
    
    peak_capital = capital
    max_drawdown = 0.0

    # ACUMULADORES ESTADÍSTICOS (Memoria O(1))
    op_ganadas = 0
    op_perdidas = 0
    pos_pnl = 0.0
    neg_pnl = 0.0

    for row in df.itertuples(index=False):
        price = float(row.close)
        timestamp = int(row.timestamp)

        if not in_position and strategy.should_buy(row):
            in_position = True
            entry_price = price
            current_position_size = (capital * strategy.risk_per_trade) / price

            trade = {
                "symbol": symbol, "side": "BUY", "price": price, "timestamp": timestamp
            }
            
            if escribir_trades and carpeta_estrategia and timeframe:
                guardar_trade_a_csv(carpeta_estrategia, symbol, timeframe, trade)
            
            trade_count += 1

        elif in_position and strategy.should_sell(row):
            in_position = False
            
            pnl = current_position_size * (price - entry_price)
            capital += pnl

            # LÓGICA DE ACUMULACIÓN
            if pnl > 0:
                op_ganadas += 1
                pos_pnl += pnl
            elif pnl < 0:
                op_perdidas += 1
                neg_pnl += abs(pnl)

            trade = {
                "symbol": symbol, "side": "SELL", "price": price, 
                "timestamp": timestamp, "pnl": pnl, "capital": capital
            }
            
            if escribir_trades and carpeta_estrategia and timeframe:
                guardar_trade_a_csv(carpeta_estrategia, symbol, timeframe, trade)
            
            trade_count += 1
            
            capital_history.append(capital)
            peak_capital = max(peak_capital, capital)
            max_drawdown = max(max_drawdown, peak_capital - capital)

    stats = _calculate_backtest_stats(strategy, capital, capital_history, max_drawdown, df, symbol, trade_count, op_ganadas, op_perdidas, pos_pnl, neg_pnl)
    return trade_count, stats


def _calculate_backtest_stats(strategy, final_capital: float, capital_history: list, max_drawdown: float, df: pd.DataFrame, symbol: str, trade_count: int, op_ganadas: int, op_perdidas: int, pos_pnl: float, neg_pnl: float) -> dict:
    """
    Procesa las métricas de rendimiento utilizando los acumuladores calculados en caliente.
    """
    op_totales = max(trade_count // 2, 0)
    
    retorno_total = final_capital - strategy.capital
    retorno_acumulado = (retorno_total / strategy.capital * 100) if strategy.capital > 0 else 0.0
    
    abs_drawdown = max(capital_history) - min(capital_history) if capital_history else 0.0

    # CÁLCULO DE MÉTRICAS COMPLEJAS
    win_rate = (op_ganadas / op_totales * 100) if op_totales > 0 else 0.0
    
    if op_totales == 0:
        profit_factor = 0.0
    elif neg_pnl == 0:
        profit_factor = float(pos_pnl) # Previene división por cero
    else:
        profit_factor = pos_pnl / neg_pnl
    
    fecha_inicio = datetime.fromtimestamp(df["timestamp"].iloc[0] / 1000, timezone.utc).isoformat() if not df.empty else "N/A"
    fecha_fin = datetime.fromtimestamp(df["timestamp"].iloc[-1] / 1000, timezone.utc).isoformat() if not df.empty else "N/A"

    resultado = "NEUTRO"
    if retorno_total > 0: resultado = "GANANCIA"
    elif retorno_total < 0: resultado = "PERDIDA"

    # CONTRATO DE DATOS RESTAURADO
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
        "resultado": resultado
    }


def _generate_empty_stats(symbol: str, strategy) -> dict:
    """
    Devuelve un diccionario por defecto si no hay datos.
    Garantiza la consistencia del contrato de datos de salida.
    """
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
        "resultado": "NEUTRO"
    }

def main():
    try:
        logging.info("Backtest engine started")
        
        # 1. Read input
        payload = read_request_payload()
        strategy_path = payload.get("strategy_path")
        strategy_name = payload.get("strategy_name")
        timeframe = payload.get("timeframe")
        velas = payload.get("velas", {})
        capital = payload.get("capital", 1000)
        risk_per_trade = payload.get("risk_per_trade", 0.02)
        escribir_trades = payload.get("escribir_trades", False)

        logging.info(f"Loading strategy from: {strategy_path}")
        strategy = load_strategy(strategy_path, capital=capital, risk_per_trade=risk_per_trade)
        setattr(strategy, "timeframe", timeframe)

        # Create strategy results folder
        carpeta_estrategia = crear_carpeta_estrategia(strategy_name)
        logging.info(f"Results folder: {carpeta_estrategia}")

        stats_list = []
        total_trades = 0

        logging.info(f"Processing {len(velas)} symbols")
        for symbol, candles in velas.items():
            if not candles: continue
            df = pd.DataFrame(candles)
            df["close"] = pd.to_numeric(df["close"], errors="coerce")
            df["timestamp"] = pd.to_numeric(df["timestamp"], errors="coerce")
            df = df.dropna(subset=["close", "timestamp"])
            if df.empty: continue

            trade_count, stats = run_backtest(
                strategy, df, symbol, 
                carpeta_estrategia=carpeta_estrategia,
                timeframe=timeframe,
                escribir_trades=escribir_trades
            )
            
            total_trades += trade_count
            stats_list.append(stats)
            logging.info(f"Backtest for {symbol}: {trade_count} trades, result: {stats.get('resultado')}")

        logging.info(f"Backtest completed successfully. Total trades saved: {total_trades}")
        write_response("BACKTEST_RESPONSE", {
            "status": "success",
            "total_trades": total_trades,
            "stats": stats_list
        })

    except Exception as e:
        error_msg = f"Backtest engine error: {str(e)}\n{traceback.format_exc()}"
        logging.error(error_msg)
        write_error("ERROR", error_msg)
        sys.exit(1)

if __name__ == "__main__":
    main()