from ta.volatility import AverageTrueRange, BollingerBands
from ta.momentum import ROCIndicator

from strategies.BaseStrategy import BaseStrategy


class StressTestStrategy(BaseStrategy):
    WARMUP_PERIOD = 20

    # 0.4 % por vela de 1 minuto: cubre spread + comisión típicos y reduce ruido.
    # Un threshold de 0.1 % generaba etiquetas triviales que el modelo memorizaba.
    LABEL_RETURN_THRESHOLD: float = 0.004

    def __init__(self, capital=1000, risk_per_trade=0.02):
        super().__init__(capital, risk_per_trade)

    def populate_indicators(self, df):
        df = df.copy()

        # ── Features de corto plazo (usadas en should_buy/should_sell) ────────
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

        # ── Features de contexto de mercado (NO usadas en should_buy/should_sell) ──
        # Proporcionan señales ortogonales que el modelo puede explotar.

        # ATR normalizado: volatilidad realizada reciente
        atr = AverageTrueRange(
            high=df["high"], low=df["low"], close=df["close"], window=14
        ).average_true_range()
        df["atr_norm"] = atr / df["close"].replace(0, 1e-9)

        # Bollinger %B: posición del precio dentro de las bandas (0=banda inferior, 1=superior)
        bb = BollingerBands(close=df["close"], window=20, window_dev=2)
        df["bb_pct"] = bb.bollinger_pband()

        # ROC 5 periodos: velocidad del movimiento de precio
        df["roc_5"] = ROCIndicator(close=df["close"], window=5).roc()

        # ── Limpiar NaN/inf ────────────────────────────────────────────────────
        feature_cols = ["ret_1", "ret_3", "ema_gap", "rsi_fast", "vol_z",
                        "atr_norm", "bb_pct", "roc_5"]
        df[feature_cols] = (
            df[feature_cols]
            .replace([float("inf"), float("-inf")], 0.0)
            .fillna(0.0)
        )
        return df

    def get_feature_columns(self, df):
        return ["ret_1", "ret_3", "ema_gap", "rsi_fast", "vol_z",
                "atr_norm", "bb_pct", "roc_5"]

    def should_buy(self, row):
        # Condiciones más estrictas para reducir señales falsas:
        # - EMA gap significativo (> 0.001, no solo positivo)
        # - RSI en zona media-baja (< 55, no solo < 65)
        # - Momentum positivo en el último tick
        # - Volumen por encima de la media (vol_z > 0.5)
        return (
            row["ema_gap"] > 0.001
            and row["rsi_fast"] < 55
            and row["ret_1"] > 0.0
            and row["vol_z"] > 0.5
        )

    def should_sell(self, row):
        # Condiciones simétricas y estrictas para ventas
        return (
            row["ema_gap"] < -0.001
            and row["rsi_fast"] > 45
            and row["ret_1"] < 0.0
            and row["vol_z"] > 0.5
        )

    def get_stop_loss(self, entry_price: float, row) -> float:
        return entry_price * 0.997

    def get_take_profit(self, entry_price: float, row) -> float:
        return entry_price * 1.003