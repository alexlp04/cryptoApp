from strategies.BaseStrategy import BaseStrategy

class ToggleStrategy(BaseStrategy):
    def __init__(self, capital=1000, risk_per_trade=0.02):
        super().__init__(capital, risk_per_trade)
        self.toggle = False

    def populate_indicators(self, df):
        return df

    def should_buy(self, row):
        self.toggle = not self.toggle
        return self.toggle

    def should_sell(self, row):
        return not self.toggle
