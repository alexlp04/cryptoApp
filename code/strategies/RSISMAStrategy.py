from strategies.BaseStrategy import BaseStrategy

class RSISMAStrategy(BaseStrategy):

    RSI_PERIOD    = 14
    SMA_PERIOD    = 20
    WARMUP_PERIOD = 20      # max(RSI_PERIOD, SMA_PERIOD)

    def populate_indicators(self, df):
        df = df.copy()
        df["sma"] = df["close"].rolling(self.SMA_PERIOD).mean()
        delta    = df["close"].diff()
        gain     = delta.where(delta > 0, 0.0)
        loss     = -delta.where(delta < 0, 0.0)
        df["rsi"] = 100 - (100 / (1 + gain.rolling(self.RSI_PERIOD).mean()
                                      / loss.rolling(self.RSI_PERIOD).mean()))
        return df

    def should_buy(self, row) -> bool:
        return row["rsi"] < 30 and row["close"] > row["sma"]

    def should_sell(self, row) -> bool:
        return row["rsi"] > 70

    def get_stop_loss(self, entry_price: float, row) -> float:
        return entry_price * 0.98          # 2% fijo

    def get_take_profit(self, entry_price: float, row) -> float:
        return entry_price * 1.04          # 4% fijo  →  ratio R:R = 1:2