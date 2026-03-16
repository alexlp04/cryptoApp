from strategies.BaseStrategy import BaseStrategy

class ToggleStrategy(BaseStrategy):
    def __init__(self, capital=1000, risk_per_trade=0.02):
        super().__init__(capital, risk_per_trade)
        self.toggle = False

    def populate_indicators(self, df):
            return df

    def should_buy(self, row):
        # Compra si el timestamp es par (solo como prueba)
        return int(row["timestamp"]) % 2 == 0

    def should_sell(self, row):
        # Vende si el timestamp es impar
        return int(row["timestamp"]) % 2 != 0

    def get_stop_loss(self, entry_price: float, row) -> float:
        return entry_price * 0.99

    def get_take_profit(self, entry_price: float, row) -> float:
        return entry_price * 1.01