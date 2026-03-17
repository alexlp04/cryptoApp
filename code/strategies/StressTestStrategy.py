from strategies.BaseStrategy import BaseStrategy

class StressTestStrategy(BaseStrategy):
    WARMUP_PERIOD = 20

    def __init__(self, capital=1000, risk_per_trade=0.02):
        super().__init__(capital, risk_per_trade)

    def populate_indicators(self, df):
        df = df.copy()

        # Features de corto plazo para provocar cambios de señal frecuentes.
        df["ret_1"] = df["close"].pct_change(1)
        df["ret_3"] = df["close"].pct_change(3)
        df["ema_fast"] = df["close"].ewm(span=3, adjust=False).mean()
        df["ema_slow"] = df["close"].ewm(span=8, adjust=False).mean()
        df["ema_gap"] = (df["ema_fast"] - df["ema_slow"]) / df["close"]

        delta = df["close"].diff()
        gain = delta.where(delta > 0, 0.0)
        loss = -delta.where(delta < 0, 0.0)
        avg_gain = gain.rolling(6).mean()
        avg_loss = loss.rolling(6).mean().replace(0, 1e-9)
        rs = avg_gain / avg_loss
        df["rsi_fast"] = 100 - (100 / (1 + rs))

        vol_mean = df["volume"].rolling(20).mean()
        vol_std = df["volume"].rolling(20).std().replace(0, 1e-9)
        df["vol_z"] = (df["volume"] - vol_mean) / vol_std

        df[["ret_1", "ret_3", "ema_gap", "rsi_fast", "vol_z"]] = (
            df[["ret_1", "ret_3", "ema_gap", "rsi_fast", "vol_z"]]
            .replace([float("inf"), float("-inf")], 0.0)
            .fillna(0.0)
        )
        return df

    def get_feature_columns(self, df):
        return ["ret_1", "ret_3", "ema_gap", "rsi_fast", "vol_z"]

    def should_buy(self, row):
        return row["ema_gap"] > 0 and row["rsi_fast"] < 65 and row["ret_1"] > -0.002

    def should_sell(self, row):
        return row["ema_gap"] < 0 and row["rsi_fast"] > 35 and row["ret_1"] < 0.002

    def get_stop_loss(self, entry_price: float, row) -> float:
        return entry_price * 0.997

    def get_take_profit(self, entry_price: float, row) -> float:
        return entry_price * 1.003