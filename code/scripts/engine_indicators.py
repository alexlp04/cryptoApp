import sys
import pandas as pd
import logging
import os
import msgpack
from datetime import datetime
from ipc_protocol import read_request_payload, write_response

# =========================
# CONFIGURACIÓN DE LOGS
# =========================
# Creamos la carpeta logs en la raíz real del proyecto (dos niveles arriba de scripts/)
current_dir = os.path.dirname(os.path.abspath(__file__))
code_dir = os.path.dirname(current_dir)
project_root = os.path.dirname(code_dir)
log_dir = os.path.join(project_root, "logs")
os.makedirs(log_dir, exist_ok=True)

# Archivo de log con el nombre del script
log_file = os.path.join(log_dir, "engine_indicators.log")

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

def calcular_indicadores(df):
    logging.info("Iniciando cálculo de indicadores...")
    indicadores = []

    # 1. Convertir la columna 'close' de string a float
    logging.info("Convirtiendo columna 'close' a formato numérico (float)...")
    df['close'] = pd.to_numeric(df['close'], errors='coerce')

    # 2. Asegurar que tenemos ID
    if 'id' not in df.columns:
        logging.error("CRÍTICO: El DataFrame no contiene la columna 'id'. Revisa el DTO de Java.")
        raise ValueError("El DataFrame no contiene la columna 'id'")

    # SMA
    logging.info("Calculando Media Móvil Simple (SMA) periodo 14...")
    sma = df['close'].rolling(window=14).mean()
    for i, valor in enumerate(sma):
        if not pd.isna(valor) and not pd.isna(df.loc[i, 'id']):
            indicadores.append({
                'id': int(df.loc[i, 'id']),
                'tipo': 'SMA',
                'parametros': 'periodo=14',
                'valor': float(valor)
            })

    # EMA
    logging.info("Calculando Media Móvil Exponencial (EMA) periodo 14...")
    ema = df['close'].ewm(span=14, adjust=False).mean()
    for i, valor in enumerate(ema):
        if not pd.isna(valor) and not pd.isna(df.loc[i, 'id']):
            indicadores.append({
                'id': int(df.loc[i, 'id']),
                'tipo': 'EMA',
                'parametros': 'periodo=14',
                'valor': float(valor)
            })

    # RSI
    logging.info("Calculando Índice de Fuerza Relativa (RSI) periodo 14...")
    delta = df['close'].diff()
    gain = delta.where(delta > 0, 0).rolling(window=14).mean()
    loss = -delta.where(delta < 0, 0).rolling(window=14).mean()
    rs = gain / loss
    rsi = 100 - (100 / (1 + rs))
    for i, valor in enumerate(rsi):
        if not pd.isna(valor) and not pd.isna(df.loc[i, 'id']):
            indicadores.append({
                'id': int(df.loc[i, 'id']),
                'tipo': 'RSI',
                'parametros': 'periodo=14',
                'valor': float(valor)
            })

    # MACD y MACD_signal
    logging.info("Calculando MACD (fast=12, slow=26, signal=9)...")
    ema_12 = df['close'].ewm(span=12, adjust=False).mean()
    ema_26 = df['close'].ewm(span=26, adjust=False).mean()
    macd = ema_12 - ema_26
    macd_signal = macd.ewm(span=9, adjust=False).mean()

    for i in range(len(df)):
        if not pd.isna(macd[i]) and not pd.isna(df.loc[i, 'id']):
            indicadores.append({
                'id': int(df.loc[i, 'id']),
                'tipo': 'MACD',
                'parametros': 'fast=12,slow=26',
                'valor': float(macd[i])
            })
        if not pd.isna(macd_signal[i]) and not pd.isna(df.loc[i, 'id']):
            indicadores.append({
                'id': int(df.loc[i, 'id']),
                'tipo': 'MACD_signal',
                'parametros': 'signal=9',
                'valor': float(macd_signal[i])
            })

    logging.info(f"Cálculo finalizado con éxito. Se generaron {len(indicadores)} registros de indicadores.")
    
    if not indicadores:
        logging.warning("No se generó ningún indicador válido (¿quizás hay menos de 26 velas?).")
        return []

    return indicadores

if __name__ == "__main__":
    logging.info("=== Arrancando Motor de Indicadores Técnicos ===")
    try:
        logging.info("Esperando recepción de datos IPC desde Java...")
        payload = read_request_payload()
        data = payload.get("velas", [])

        if not data:
            logging.warning("No se recibieron datos de entrada. Finalizando proceso y devolviendo lista vacía.")
            write_response("INDICATORS_RESPONSE", {"indicadores": []})
            sys.exit(0)

        logging.info(f"Datos recibidos. Total velas: {len(data)} registros.")

        df = pd.DataFrame(data)
        logging.info(f"DataFrame cargado correctamente con {len(df)} velas.")

        # Calculamos indicadores
        lista_indicadores = calcular_indicadores(df)

        packed = msgpack.packb(lista_indicadores, use_bin_type=True)
        logging.info(f"Enviando respuesta IPC a Java. Tamaño de indicadores: {len(packed)} bytes.")
        write_response("INDICATORS_RESPONSE", {"indicadores": lista_indicadores})

        logging.info("=== Proceso finalizado y cerrado correctamente ===")

    except Exception as e:
        # Cualquier otro error se captura aquí con la traza completa
        logging.error(f"Fallo crítico en el script: {str(e)}", exc_info=True)
        write_response("ERROR", {"status": "error", "message": str(e), "indicadores": []})
        sys.exit(1)