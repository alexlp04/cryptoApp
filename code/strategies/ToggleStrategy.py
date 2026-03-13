from strategies.BaseStrategy import BaseStrategy

class ToggleStrategy(BaseStrategy):
    def __init__(self, capital=1000, risk_per_trade=0.02):
        super().__init__(capital, risk_per_trade)
        self.toggle = False

    def populate_indicators(self, df):
            return df

    def should_buy(self, row):
        # Compra si el timestamp es par (solo como prueba)
        print("Evaluando compra en timestamp: " + str(row["timestamp"]))
        return int(row["timestamp"]) % 2 == 0

    def should_sell(self, row):
        # Vende si el timestamp es impar
        print("Evaluando venta en timestamp: " + str(row["timestamp"]))
        return int(row["timestamp"]) % 2 != 0