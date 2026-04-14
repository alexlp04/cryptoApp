from __future__ import annotations

from abc import ABC, abstractmethod
import inspect
from decimal import Decimal
from typing import Union

import pandas as pd


class BaseStrategy(ABC):

    WARMUP_PERIOD: int = 300

    # Umbral de retorno (fracción) usado por get_label() para generar labels forward-looking.
    # Un movimiento > umbral en la siguiente vela genera label 1 (subida) o -1 (bajada).
    # Sobreescribir en estrategias con timeframe muy corto (1m → 0.001) o largo (1d → 0.01).
    LABEL_RETURN_THRESHOLD: float = 0.002

    def __init__(self, capital: Union[float, Decimal] = 1000, risk_per_trade: Union[float, Decimal] = 0.02):
        self.capital = Decimal(str(capital))
        self.risk_per_trade = Decimal(str(risk_per_trade))

    # ── Abstractos obligatorios ────────────────────────────────────────

    @abstractmethod
    def populate_indicators(self, df: pd.DataFrame) -> pd.DataFrame:
        pass

    @abstractmethod
    def should_buy(self, row: dict) -> bool:
        pass

    @abstractmethod
    def should_sell(self, row: dict) -> bool:
        pass

    @abstractmethod
    def get_stop_loss(self, entry_price: float, row: dict) -> float:
        pass

    @abstractmethod
    def get_take_profit(self, entry_price: float, row: dict) -> float:
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

    def should_close(self, row: dict, entry_price: float) -> bool:
        return (
            row["close"] <= self.get_stop_loss(entry_price, row)
            or row["close"] >= self.get_take_profit(entry_price, row)
        )

    def max_open_trades(self) -> int:
        return 1

    def get_feature_columns(self, df: pd.DataFrame) -> list[str]:
        base = {"timestamp", "open", "high", "low", "close", "volume"}
        return [c for c in df.columns if c not in base]

    def get_label(self, row: dict, next_row: dict) -> int:
        """
        Label forward-looking basado en el retorno REAL de la siguiente vela.

        Devuelve:
          1  si close_{t+1} sube  > LABEL_RETURN_THRESHOLD respecto a close_t
         -1  si close_{t+1} baja  > LABEL_RETURN_THRESHOLD respecto a close_t
          0  si el movimiento es menor que el umbral (mercado lateral)

        Por qué no usar should_buy/should_sell:
          Las features de get_feature_columns() son exactamente las mismas
          variables que should_buy/should_sell evalúan. Si el label = should_buy(row),
          el modelo aprende la regla booleana de memoria (F1 ≈ 99%). Ese F1
          no traduce en rentabilidad real porque la regla en sí puede ser mala.
          Con labels forward-looking el modelo tiene que predecir el mercado,
          no memorizar la lógica de la estrategia.
        """
        close_curr = float(row.get("close", 0))
        close_next = float(next_row.get("close", 0))

        if close_curr <= 0:
            return 0

        ret = (close_next - close_curr) / close_curr
        threshold = float(self.__class__.LABEL_RETURN_THRESHOLD)

        if ret > threshold:
            return 1
        elif ret < -threshold:
            return -1
        return 0