import sys
import json
import pandas as pd

def calcular_indicadores(df):
    indicadores = []

    # SMA
    sma = df['close'].rolling(window=14).mean()
    for i, valor in enumerate(sma):
        if not pd.isna(valor):
            indicadores.append({
                'id': df.loc[i, 'id'],
                'tipo': 'SMA',
                'parametros': 'periodo=14',
                'valor': float(valor)
            })

    # EMA
    ema = df['close'].ewm(span=14, adjust=False).mean()
    for i, valor in enumerate(ema):
        if not pd.isna(valor):
            indicadores.append({
                'id': df.loc[i, 'id'],
                'tipo': 'EMA',
                'parametros': 'periodo=14',
                'valor': float(valor)
            })

    # RSI
    delta = df['close'].diff()
    gain = delta.where(delta > 0, 0).rolling(window=14).mean()
    loss = -delta.where(delta < 0, 0).rolling(window=14).mean()
    rs = gain / loss
    rsi = 100 - (100 / (1 + rs))
    for i, valor in enumerate(rsi):
        if not pd.isna(valor):
            indicadores.append({
                'id': df.loc[i, 'id'],
                'tipo': 'RSI',
                'parametros': 'periodo=14',
                'valor': float(valor)
            })

    # MACD y MACD_signal
    ema_12 = df['close'].ewm(span=12, adjust=False).mean()
    ema_26 = df['close'].ewm(span=26, adjust=False).mean()
    macd = ema_12 - ema_26
    macd_signal = macd.ewm(span=9, adjust=False).mean()

    for i in range(len(df)):
        if not pd.isna(macd[i]):
            indicadores.append({
                'id': df.loc[i, 'id'],
                'tipo': 'MACD',
                'parametros': 'fast=12,slow=26',
                'valor': float(macd[i])
            })
        if not pd.isna(macd_signal[i]):
            indicadores.append({
                'id': df.loc[i, 'id'],
                'tipo': 'MACD_signal',
                'parametros': 'signal=9',
                'valor': float(macd_signal[i])
            })

    return pd.DataFrame(indicadores)

if __name__ == "__main__":
    try:
        input_data = sys.stdin.read()

        data = json.loads(input_data)
        df = pd.DataFrame(data)

        df_indicators = calcular_indicadores(df)

        # Enviar resultado a Java
        print(df_indicators.to_json(orient='records'))
        sys.stdout.flush()

    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        sys.exit(1)