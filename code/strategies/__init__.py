"""Estrategias de trading: cada una hereda de BaseStrategy (ver su contrato).

Los motores cargan la estrategia por ruta (`shared_utils.load_strategy_by_path`), así que no hay catálogo
que mantener aquí; importar solo la clase base no arrastra ninguna estrategia concreta ni `ta`.
"""
from __future__ import annotations

from strategies.BaseStrategy import BaseStrategy

__all__ = ["BaseStrategy"]
