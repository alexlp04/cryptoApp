import sys
import json
import pandas as pd
import logging
import os
from datetime import datetime

# =========================
# CONFIGURACIÓN DE LOGS
# =========================
# Creamos la carpeta logs en el directorio actual (raíz del proyecto)
current_dir = os.path.dirname(os.path.abspath(__file__))
project_root = os.path.dirname(current_dir)
log_dir = os.path.join(project_root, "logs")
os.makedirs(log_dir, exist_ok=True)

# Archivo de log diario (ej: engine_cbi_20260220.log)
log_file = os.path.join(log_dir, f"engine_cbi_{datetime.now().strftime('%Y%m%d')}.log")

logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s [%(levelname)s] %(message)s',
    handlers=[
        logging.FileHandler(log_file, encoding='utf-8')
    ]
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
        logging.info("Esperando recepción de datos JSON desde Java (Stdin)...")
        input_data = sys.stdin.read()
        
        if not input_data:
            logging.warning("No se recibieron datos de entrada. Finalizando proceso y devolviendo lista vacía.")
            print("[]") 
            sys.exit(0)

        logging.info(f"Datos recibidos. Tamaño del payload: {len(input_data)} caracteres.")
        
        data = json.loads(input_data)
        df = pd.DataFrame(data)
        logging.info(f"DataFrame cargado correctamente con {len(df)} velas.")

        # Calculamos indicadores
        lista_indicadores = calcular_indicadores(df)

        # Preparamos el JSON de salida
        json_output = json.dumps(lista_indicadores)
        logging.info(f"Enviando JSON a Java. Tamaño de respuesta: {len(json_output)} caracteres.")
        
        # Enviar resultado a Java por Stdout
        print(json_output)
        sys.stdout.flush()
        
        logging.info("=== Proceso finalizado y cerrado correctamente ===")

    except json.JSONDecodeError as e:
        logging.error(f"Error parseando el JSON enviado por Java: {str(e)}", exc_info=True)
        print("[]")
        sys.exit(1)
    except Exception as e:
        # Cualquier otro error se captura aquí con la traza completa
        logging.error(f"Fallo crítico en el script: {str(e)}", exc_info=True)
        print("[]") 
        sys.exit(1)