"""risk_management.py — Dimensionado de posiciones y límites de riesgo, como en una plataforma de trading.

Lo comparten el backtest y el motor en tiempo real (y Java aplica las mismas reglas en
PaperTradingService), para que un backtest y la operativa en vivo dimensionen igual.

Reglas:
  - Riesgo fijo por operación: se arriesga una fracción del equity (risk_per_trade). El tamaño sale de
    la distancia al stop:  unidades = equity * risk_per_trade / (entrada - stop).
  - Tope por posición: una sola posición no supera MAX_POSITION_FRACTION del equity (spot, sin apalancamiento).
  - Riesgo abierto total ("portfolio heat"): la suma de lo arriesgado en las posiciones abiertas no supera
    MAX_OPEN_RISK_FRACTION del equity.
  - Sin stop válido no hay operación: no se puede acotar la pérdida.
"""
from __future__ import annotations

#: Una posición no puede superar este porcentaje del equity de la estrategia.
MAX_POSITION_FRACTION: float = 0.25
#: Suma del riesgo en las posiciones abiertas, como fracción del equity.
MAX_OPEN_RISK_FRACTION: float = 0.06


def valid_stop(entry_price: float, stop_price: float | None) -> bool:
    """Un stop de una posición larga debe ser positivo y estar por debajo de la entrada."""
    return stop_price is not None and 0.0 < stop_price < entry_price


def position_size(
    equity: float,
    risk_fraction: float,
    entry_price: float,
    stop_price: float,
    max_position_fraction: float = MAX_POSITION_FRACTION,
) -> float:
    """Unidades a comprar: las que pierden equity*risk_fraction si salta el stop, con el tope por posición.

    Devuelve 0.0 si algún dato no permite dimensionar (equity o entrada no positivos, stop inválido).
    """
    if equity <= 0 or entry_price <= 0 or not valid_stop(entry_price, stop_price):
        return 0.0
    by_risk = equity * risk_fraction / (entry_price - stop_price)
    by_cap = equity * max_position_fraction / entry_price
    return min(by_risk, by_cap)


def risk_amount(units: float, entry_price: float, stop_price: float) -> float:
    """Dinero que se pierde si salta el stop (sin comisiones)."""
    return units * (entry_price - stop_price)


def strategy_levels(strategy, entry_price: float, row) -> tuple[float, float] | None:
    """(stop, take_profit) que la estrategia fija para esta entrada, o None si no hay un stop válido."""
    try:
        stop = float(strategy.get_stop_loss(entry_price, row))
        take_profit = float(strategy.get_take_profit(entry_price, row))
    except (AttributeError, TypeError, ValueError, KeyError, IndexError):
        return None  # incluye una columna de indicador que la fila no trae
    return (stop, take_profit) if valid_stop(entry_price, stop) else None


class PositionTracker:
    """Posición larga abierta de un símbolo en el motor en tiempo real, con sus órdenes protectoras.

    El motor emite BUY/SELL a Java, que es quien lleva la contabilidad; esto solo recuerda el stop y el
    objetivo para disparar la salida en cuanto el precio los toca (una plataforma real los deja como
    órdenes en el mercado; aquí se vigilan en cada mensaje del stream).

    shortcut: si Java rechaza un BUY (límite de riesgo, fondos), el tracker lo da por abierto hasta su
    primera salida; la señal de venta que emita Java la ignora por no haber posición. Subir a un canal
    de confirmación Java -> Python si esos rechazos se vuelven frecuentes.
    """

    def __init__(self, positions: dict | None = None):
        self._levels: tuple[float, float] | None = None
        if positions:
            self.open(float(positions["stop"]), float(positions["take_profit"]))

    @property
    def is_open(self) -> bool:
        return self._levels is not None

    def open(self, stop: float, take_profit: float) -> None:
        self._levels = (stop, take_profit)

    def close(self) -> None:
        self._levels = None

    def protective_exit(self, price: float) -> tuple[float, str] | None:
        """(precio de salida, motivo) si el precio actual toca el stop o el objetivo, si no None.

        El stop es una orden a mercado: sale al precio actual (que puede estar por debajo del stop si hubo
        hueco). El objetivo es una orden límite: sale a su precio.
        """
        if self._levels is None:
            return None
        stop, take_profit = self._levels
        if price <= stop:
            return price, "STOP"
        if price >= take_profit:
            return take_profit, "TARGET"
        return None
