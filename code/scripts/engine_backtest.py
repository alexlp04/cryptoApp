import sys
import pandas as pd
import os
import traceback
import logging
from ipc_protocol import read_request_payload, write_response, write_error
from shared_utils import load_strategy_by_path

# --- CONFIGURACIÓN DE LOGS ---
# current_dir  = .../code/scripts/
# code_dir     = .../code/
# project_root = .../ (raíz del proyecto, donde están logs/, results/, models/)
current_dir = os.path.dirname(os.path.abspath(__file__))
code_dir = os.path.dirname(current_dir)
project_root = os.path.dirname(code_dir)
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

# --- IMPORTAR MOTOR COMPARTIDO ---
if code_dir not in sys.path:
    sys.path.append(code_dir)

from backtest_engine import (  # noqa: E402
    crear_carpeta_estrategia,
    guardar_trade_a_csv,
    run_backtest,
)
logger = logging.getLogger(__name__)


def main():
    try:
        logger.info("Backtest engine started")
        
        # 1. Read input
        payload = read_request_payload()
        strategy_path = payload.get("strategy_path")
        strategy_name = payload.get("strategy_name")
        timeframe = payload.get("timeframe")
        velas = payload.get("velas", {})
        capital = payload.get("capital", 1000)
        risk_per_trade = payload.get("risk_per_trade", 0.02)
        escribir_trades = payload.get("escribir_trades", False)

        logger.info("Loading strategy from: %s", strategy_path)
        strategy = load_strategy_by_path(strategy_path, capital=capital, risk_per_trade=risk_per_trade)
        setattr(strategy, "timeframe", timeframe)

        # Create strategy results folder
        carpeta_estrategia = crear_carpeta_estrategia(strategy_name)
        logger.info("Results folder: %s", carpeta_estrategia)

        stats_list = []
        total_trades = 0

        logger.info("Processing %d symbols", len(velas))
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
            logger.info("Backtest for %s: %d trades, result: %s", symbol, trade_count, stats.get("resultado"))

        logger.info("Backtest completed successfully. Total trades saved: %d", total_trades)
        write_response("BACKTEST_RESPONSE", {
            "status": "success",
            "total_trades": total_trades,
            "stats": stats_list
        })

    except Exception as e:
        error_msg = "Backtest engine error: %s\n%s" % (str(e), traceback.format_exc())
        logger.error(error_msg)
        write_error("ERROR", error_msg)
        sys.exit(1)

if __name__ == "__main__":
    main()