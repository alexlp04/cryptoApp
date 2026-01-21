import pandas as pd
from ta.momentum import RSIIndicator # Usamos la librería 'ta' en vez de 'pandas_ta'
from strategies.BaseStrategy import BaseStrategy

class ScalpingRSIStrategy(BaseStrategy):
    def __init__(self, capital=1000, risk_per_trade=0.02):
        super().__init__(capital, risk_per_trade)
        self.rsi_period = 7

    def populate_indicators(self, df):
        # Cálculo del RSI con la librería 'ta'
        rsi_intance = RSIIndicator(close=df['close'], window=self.rsi_period)
        df['rsi'] = rsi_intance.rsi()
        df['rsi'] = df['rsi'].fillna(50)
        return df

    def should_buy(self, row):
        return row['rsi'] < 45

    def should_sell(self, row):
        return row['rsi'] > 55