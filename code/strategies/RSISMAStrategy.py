import pandas as pd
from strategies.BaseStrategy import BaseStrategy

class RSISMAStrategy(BaseStrategy):
    def __init__(self, capital=1000, risk_per_trade=0.02):
        super().__init__(capital=capital, risk_per_trade=risk_per_trade)

    RSI_PERIOD = 14
    SMA_PERIOD = 20

    def populate_indicators(self, df):
        df["sma"] = df["close"].rolling(self.SMA_PERIOD).mean()
        delta = df["close"].diff()
        gain = delta.where(delta > 0, 0.0)
        loss = -delta.where(delta < 0, 0.0)
        avg_gain = gain.rolling(self.RSI_PERIOD).mean()
        avg_loss = loss.rolling(self.RSI_PERIOD).mean()
        rs = avg_gain / avg_loss
        df["rsi"] = 100 - (100 / (1 + rs))
        return df

    def should_buy(self, row):
        return row["rsi"] < 30 and row["close"] > row["sma"]

    def should_sell(self, row):
        return row["rsi"] > 70
