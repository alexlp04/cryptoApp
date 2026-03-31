from strategies.BaseStrategy import BaseStrategy


class AlternatingTestStrategy(BaseStrategy):
    """
    Estrategia de prueba que alterna senales por vela:
    BUY en una vela, SELL en la siguiente, y asi sucesivamente.

    Dispara como maximo una senal por timestamp para evitar spam en runtime.
    """

    WARMUP_PERIOD = 1

    def __init__(self, capital=1000, risk_per_trade=0.02):
        super().__init__(capital, risk_per_trade)
        self._next_action = "BUY"
        self._last_signal_timestamp = None

    def populate_indicators(self, df):
        return df

    def _is_new_candle(self, row) -> bool:
        ts = int(row["timestamp"])
        return self._last_signal_timestamp is None or ts > self._last_signal_timestamp

    def should_buy(self, row) -> bool:
        if self._next_action != "BUY":
            return False
        if not self._is_new_candle(row):
            return False

        self._last_signal_timestamp = int(row["timestamp"])
        self._next_action = "SELL"
        return True

    def should_sell(self, row) -> bool:
        if self._next_action != "SELL":
            return False
        if not self._is_new_candle(row):
            return False

        self._last_signal_timestamp = int(row["timestamp"])
        self._next_action = "BUY"
        return True

    def get_stop_loss(self, entry_price: float, row) -> float:
        return entry_price * 0.99

    def get_take_profit(self, entry_price: float, row) -> float:
        return entry_price * 1.01
