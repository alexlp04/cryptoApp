from abc import ABC, abstractmethod

class BaseStrategy(ABC):
    def __init__(self, capital=1000, risk_per_trade=0.02):
        self.capital = capital
        self.risk_per_trade = risk_per_trade

    def get_position_size(self, price):
        return self.capital * self.risk_per_trade / price

    @abstractmethod
    def populate_indicators(self, df):
        pass

    @abstractmethod
    def should_buy(self, row):
        pass

    @abstractmethod
    def should_sell(self, row):
        pass
