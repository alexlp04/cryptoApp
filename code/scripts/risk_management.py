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


def valid_stop(entry_price: float, stop_price: float | None, short: bool = False) -> bool:
    """Un stop largo es positivo y está por debajo de la entrada; el de un corto, por encima."""
    if stop_price is None or stop_price <= 0.0:
        return False
    return entry_price < stop_price if short else stop_price < entry_price


def position_size(
    equity: float,
    risk_fraction: float,
    entry_price: float,
    stop_price: float,
    max_position_fraction: float = MAX_POSITION_FRACTION,
    short: bool = False,
) -> float:
    """Unidades a operar: las que pierden equity*risk_fraction si salta el stop, con el tope por posición.

    Un corto se dimensiona igual (el tope es sobre el nocional); no hay apalancamiento.
    Devuelve 0.0 si algún dato no permite dimensionar (equity o entrada no positivos, stop inválido).
    """
    if equity <= 0 or entry_price <= 0 or not valid_stop(entry_price, stop_price, short):
        return 0.0
    by_risk = equity * risk_fraction / abs(entry_price - stop_price)
    by_cap = equity * max_position_fraction / entry_price
    return min(by_risk, by_cap)


def risk_amount(units: float, entry_price: float, stop_price: float) -> float:
    """Dinero que se pierde si salta el stop (sin comisiones)."""
    return units * abs(entry_price - stop_price)


def strategy_levels(strategy, entry_price: float, row, short: bool = False) -> tuple[float, float] | None:
    """(stop, take_profit) que la estrategia fija para esta entrada, o None si no hay un stop válido."""
    try:
        if short:
            stop = float(strategy.get_short_stop_loss(entry_price, row))
            take_profit = float(strategy.get_short_take_profit(entry_price, row))
        else:
            stop = float(strategy.get_stop_loss(entry_price, row))
            take_profit = float(strategy.get_take_profit(entry_price, row))
    except (AttributeError, TypeError, ValueError, KeyError, IndexError):
        return None  # incluye una columna de indicador que la fila no trae
    return (stop, take_profit) if valid_stop(entry_price, stop, short) else None


def signal_actions(side: str | None, buy: bool, sell: bool, short: bool = False, cover: bool = False) -> list[str]:
    """Acciones a emitir según la posición abierta (side: None, "long" o "short") y las señales de la estrategia.

    Cerrar un largo y abrir un corto (o al revés) en la misma vela es un giro: dos acciones seguidas.
    """
    if side == "long":
        if not sell:
            return []
        return ["SELL", "SHORT"] if short else ["SELL"]
    if side == "short":
        if not cover:
            return []
        return ["COVER", "BUY"] if buy else ["COVER"]
    return ["BUY"] if buy else ["SHORT"] if short else []


class PositionTracker:
    """Posición abierta (larga o corta) de un símbolo en el motor en tiempo real, con sus órdenes protectoras.

    El motor emite BUY/SELL/SHORT/COVER a Java, que es quien lleva la contabilidad; esto solo recuerda el
    stop y el objetivo para disparar la salida en cuanto el precio los toca (una plataforma real los deja
    como órdenes en el mercado; aquí se vigilan en cada mensaje del stream).

    shortcut: si Java rechaza una apertura (límite de riesgo, fondos), el tracker la da por abierta hasta su
    primera salida; la señal de cierre que emita Java la ignora por no haber posición. Subir a un canal
    de confirmación Java -> Python si esos rechazos se vuelven frecuentes.
    """

    def __init__(self, positions: dict | None = None):
        self._levels: tuple[float, float] | None = None
        self._side: str | None = None
        if positions:
            self.open(float(positions["stop"]), float(positions["take_profit"]),
                      "short" if positions.get("side") == "SHORT" else "long")

    @property
    def is_open(self) -> bool:
        return self._levels is not None

    @property
    def side(self) -> str | None:
        """None sin posición, "long" o "short"."""
        return self._side

    def open(self, stop: float, take_profit: float, side: str = "long") -> None:
        self._levels = (stop, take_profit)
        self._side = side

    def close(self) -> None:
        self._levels = None
        self._side = None

    def protective_exit(self, price: float) -> tuple[float, str] | None:
        """(precio de salida, motivo) si el precio actual toca el stop o el objetivo, si no None.

        El stop es una orden a mercado: sale al precio actual (que puede estar más allá del stop si hubo
        hueco). El objetivo es una orden límite: sale a su precio.
        """
        if self._levels is None:
            return None
        stop, take_profit = self._levels
        sign = -1.0 if self._side == "short" else 1.0  # en un corto todo se mira al revés
        if sign * price <= sign * stop:
            return price, "STOP"
        if sign * price >= sign * take_profit:
            return take_profit, "TARGET"
        return None
