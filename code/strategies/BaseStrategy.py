from abc import ABC, abstractmethod
import inspect
from decimal import Decimal
from typing import Union

class BaseStrategy(ABC):

    WARMUP_PERIOD: int = 300

    def __init__(self, capital: Union[float, Decimal] = 1000, risk_per_trade: Union[float, Decimal] = 0.02):
        self.capital = Decimal(str(capital))
        self.risk_per_trade = Decimal(str(risk_per_trade))

    # ── Abstractos obligatorios ────────────────────────────────────────

    @abstractmethod
    def populate_indicators(self, df):
        pass

    @abstractmethod
    def should_buy(self, row) -> bool:
        pass

    @abstractmethod
    def should_sell(self, row) -> bool:
        pass

    @abstractmethod
    def get_stop_loss(self, entry_price: float, row) -> float:
        pass

    @abstractmethod
    def get_take_profit(self, entry_price: float, row) -> float:
        pass

    # ── Concretos sobreescribibles ─────────────────────────────────────

    def get_warmup_period(self) -> int:
        periods = [
            v for k, v in inspect.getmembers(self.__class__)
            if k.endswith("_PERIOD") and k != "WARMUP_PERIOD" and isinstance(v, int)
        ]
        return max(periods, default=self.WARMUP_PERIOD)

    def get_name(self) -> str:
        return self.__class__.__name__

    def get_position_size(self, price: Union[float, Decimal]) -> Decimal:
        return (self.capital * self.risk_per_trade) / Decimal(str(price))

    def should_close(self, row, entry_price: float) -> bool:
        return (
            row["close"] <= self.get_stop_loss(entry_price, row)
            or row["close"] >= self.get_take_profit(entry_price, row)
        )

    def max_open_trades(self) -> int:
        return 1

    def get_feature_columns(self, df) -> list:
        base = {"timestamp", "open", "high", "low", "close", "volume"}
        return [c for c in df.columns if c not in base]

    def get_label(self, row, next_row) -> int:
        if self.should_buy(row):
            return 1
        if self.should_sell(row):
            return -1
        return 0