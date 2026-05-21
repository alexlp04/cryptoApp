"""
Catálogo de estrategias de trading disponibles.

Cada estrategia hereda de BaseStrategy e implementa:
  - populate_indicators(df): enriquecimiento con indicadores técnicos
  - should_buy(row) / should_sell(row): lógica de entrada/salida
  - get_feature_columns(df): features para ML
  - get_label(row, next_row): etiquetas forward-looking para entrenamiento

Para registrar una nueva estrategia basta importarla aquí y añadirla
al diccionario ESTRATEGIAS_DISPONIBLES.
"""
from __future__ import annotations

from strategies.BaseStrategy import BaseStrategy
from strategies.StressTestStrategy import StressTestStrategy

# Catálogo de autodescubrimiento: nombre de clase → tipo
ESTRATEGIAS_DISPONIBLES: dict[str, type[BaseStrategy]] = {
    "StressTestStrategy": StressTestStrategy,
}

__all__ = [
    "BaseStrategy",
    "StressTestStrategy",
    "ESTRATEGIAS_DISPONIBLES",
]
