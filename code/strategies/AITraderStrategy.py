import pandas as pd
from strategies.BaseStrategy import BaseStrategy

class AITraderStrategy(BaseStrategy):
    """
    Estrategia diseñada específicamente para actuar como generadora de variables 
    (Feature Engineering) para el modelo de Inteligencia Artificial.
    Calcula SMA, EMA, RSI y MACD exactamente igual que el motor de Base de Datos.
    """

    def __init__(self, capital=1000, risk_per_trade=0.02):
        super().__init__(capital, risk_per_trade)

    def populate_indicators(self, df: pd.DataFrame) -> pd.DataFrame:
        """
        Recibe un DataFrame con las velas crudas y añade las columnas de los indicadores.
        Estos cálculos son idénticos a los de engine_cbi.py para mantener la coherencia.
        """
        # Asegurarnos de que el precio de cierre es numérico
        df['close'] = pd.to_numeric(df['close'], errors='coerce')

        # 1. SMA (Media Móvil Simple) - Periodo 14
        df['SMA_14'] = df['close'].rolling(window=14).mean()

        # 2. EMA (Media Móvil Exponencial) - Periodo 14
        df['EMA_14'] = df['close'].ewm(span=14, adjust=False).mean()

        # 3. RSI (Índice de Fuerza Relativa) - Periodo 14
        delta = df['close'].diff()
        gain = delta.where(delta > 0, 0).rolling(window=14).mean()
        loss = -delta.where(delta < 0, 0).rolling(window=14).mean()
        
        # Evitar división por cero
        loss = loss.replace(0, 0.00001) 
        
        rs = gain / loss
        df['RSI_14'] = 100 - (100 / (1 + rs))

        # 4. MACD (Convergencia/Divergencia de Medias Móviles)
        ema_12 = df['close'].ewm(span=12, adjust=False).mean()
        ema_26 = df['close'].ewm(span=26, adjust=False).mean()
        
        df['MACD'] = ema_12 - ema_26
        df['MACD_signal'] = df['MACD'].ewm(span=9, adjust=False).mean()

        # Rellenar los valores nulos (NaN) generados por los rolling windows con 0 o el valor anterior
        df.fillna(method='bfill', inplace=True)
        df.fillna(0, inplace=True)

        return df

    def should_buy(self, row: pd.Series) -> bool:
        """
        Como esta estrategia se usa junto a la IA, el motor de IA (engine_ai_rt.py)
        ignorará este método y usará el modelo .pkl para decidir.
        Aun así, dejamos una lógica básica por si se lanza sin IA.
        """
        # Lógica de ejemplo: Comprar si RSI está sobrevendido y MACD cruza hacia arriba
        macd_alcista = row['MACD'] > row['MACD_signal']
        rsi_sobrevendido = row['RSI_14'] < 30
        
        return macd_alcista and rsi_sobrevendido

    def should_sell(self, row: pd.Series) -> bool:
        """
        Igual que should_buy, la IA sobrescribe esta decisión.
        """
        # Lógica de ejemplo: Vender si RSI está sobrecomprado y MACD cruza hacia abajo
        macd_bajista = row['MACD'] < row['MACD_signal']
        rsi_sobrecomprado = row['RSI_14'] > 70
        
        return macd_bajista and rsi_sobrecomprado

    def get_stop_loss(self, entry_price: float, row) -> float:
        # Stop conservador para fallback cuando no hay modelo IA activo.
        return entry_price * 0.98

    def get_take_profit(self, entry_price: float, row) -> float:
        # Take profit 1:2 respecto al stop para mantener R:R razonable.
        return entry_price * 1.04