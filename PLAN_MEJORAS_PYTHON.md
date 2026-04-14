# Plan de Mejoras — Scripts Python (`code/scripts/` + `code/strategies/`)

**Fecha del análisis**: 14 de abril de 2026  
**Cobertura**: 16 ficheros, ~3.700 líneas de código  
**Herramientas utilizadas**: SonarQube, Pylance MCP, análisis manual

---

## Resumen Ejecutivo

### Hallazgos por fuente

| Fuente | Hallazgos | Severidad |
|--------|-----------|-----------|
| **SonarQube** | 15+ issues (complejidad cognitiva, `inplace=True`, falta de `random_state`/seeds, naming PEP8, literal duplicado) | 🔴 Alta |
| **Pylance/imports** | `tensorflow` no encontrado en entorno (import diferido OK, pero no instalable) | 🟡 Media |
| **Revisión manual** | Logging duplicado (10-15 líneas replicadas en 6 ficheros), falta de type hints, código duplicado entre engines, `__init__.py` ausente, tests escasos (~30% cobertura) | 🔴 Alta |

### Estadísticas de código

| Métrica | Valor |
|---------|-------|
| **Total de líneas** | 3.678 |
| **Ficheros Python** | 16 |
| **Funciones con CC > 15** | 8 |
| **Peor CC** | 84 (`engine_ai_rt.py::run_symbol()`) |
| **Duplicación de logging** | ~90 líneas repetidas |
| **Cobertura de tests** | <30% (estimado) |

---

## P1 — Bugs y Problemas de Reproducibilidad (🔴 ALTA prioridad)

### P1.1: `StratifiedKFold` sin `random_state` explícito

**Ficheros afectados**:
- [engine_optimize.py](code/scripts/engine_optimize.py#L263)
- [engine_optimize.py](code/scripts/engine_optimize.py#L351)

**Problema**:
```python
skf = StratifiedKFold(n_splits=cv_folds, shuffle=False)
```

SonarQube marca esto como no reproducible sin un `random_state` explícito. Sin embargo, esta es una decisión **intencionada y correcta** para series temporales (mantener el orden temporal sin mezclar datos), pero SonarQube no lo entiende.

**Acción recomendada**:
1. **Opción A** (mantener comportamiento actual): Añadir comentario justificativo
   ```python
   # shuffle=False preserva orden temporal para validación realista de series temporales
   # No especificamos random_state porque shuffle=False hace que sea determinístico
   skf = StratifiedKFold(n_splits=cv_folds, shuffle=False)
   ```

2. **Opción B** (ser explícito): Usar `random_state=42` aunque `shuffle=False`
   ```python
   skf = StratifiedKFold(n_splits=cv_folds, shuffle=False, random_state=42)
   ```

**Recomendación**: Opción B (más segura y SonarQube no se queja).

---

### P1.2: `GradientBoostingClassifier` sin `learning_rate` explícito

**Fichero**: [engine_train.py](code/scripts/engine_train.py#L70)

**Código actual**:
```python
return GradientBoostingClassifier(**params)
```

**Problema**: SonarQube pide que `learning_rate` sea explícito. El default existe (0.1), pero puede no ser el ideal para trading.

**Acción**: Hacer explícito el valor:
```python
params.setdefault("learning_rate", 0.1)  # Default de Scikit-learn
params.setdefault("random_state", 42)
return GradientBoostingClassifier(**params)
```

---

### P1.3: `SVC` sin hiperparámetros críticos explícitos

**Fichero**: [engine_train.py](code/scripts/engine_train.py#L75)

**Código actual**:
```python
return SVC(**params)
```

**Problema**: SonarQube pide `C`, `kernel`, `gamma` explícitos.

**Acción**:
```python
default_svc_params = {
    "kernel": "rbf",
    "C": 1.0,
    "gamma": "scale",
    "probability": True,
    "random_state": 42,
}
default_svc_params.update(params)
return SVC(**default_svc_params)
```

---

### P1.4: `df.inplace=True` con Copy-on-Write

**Ficheros afectados**:
- [engine_train.py](code/scripts/engine_train.py#L203)
- [engine_optimize.py](code/scripts/engine_optimize.py#L666)

**Código actual**:
```python
df_raw.sort_values("timestamp", inplace=True)
```

**Problema**: SonarQube recomienda no usar `inplace=True` con Copy-on-Write mode en Pandas 2.0+

**Acción**: Reemplazar por asignación:
```python
# En engine_train.py L203
df_raw = df_raw.sort_values("timestamp")

# En engine_optimize.py L666
df = df.sort_values("timestamp")
```

---

### P1.5: NaN comparison en serialización (Bug silencioso)

**Fichero**: [engine_optimize.py](code/scripts/engine_optimize.py#L858)

**Código actual**:
```python
serializable_best_params = {
    k: (v if not isinstance(v, float) or not (
        float("nan") == v or float("inf") == abs(v)
    ) else str(v))
    for k, v in best_params.items()
}
```

**Problema**: `float("nan") == v` **siempre es `False`** en Python (NaN nunca es igual a nada, ni a sí mismo). Esto es un bug silencioso.

**Acción**: Usar `math.isnan()` o `pd.isna()`:
```python
import math

serializable_best_params = {
    k: (v if not isinstance(v, float) or not (
        math.isnan(v) or math.isinf(v)
    ) else str(v))
    for k, v in best_params.items()
}
```

---

## P2 — Complejidad Cognitiva (🔴 ALTA prioridad)

### Resumen de funciones con CC > 15

| # | Fichero | Función | CC actual | CC máx | Exceso | Acción |
|---|---------|---------|-----------|--------|--------|--------|
| P2.1 | engine_ai_rt.py | `run_symbol()` | **84** | 15 | **+69** | ⚠️ **CRÍTICO**: Dividir en 5+ subfunciones |
| P2.2 | engine_optimize.py | `main()` | 28 | 15 | +13 | Extraer subfunciones |
| P2.3 | engine_optimize.py | `_train_final_model()` | 20 | 15 | +5 | Extraer lógica neural vs ML |
| P2.4 | backtest_engine.py | `run_backtest()` | 20 | 15 | +5 | Extraer cálculo de métricas |
| P2.5 | backtest_engine.py | `run_backtest_with_predictions()` | 22 | 15 | +7 | Extraer manejo de posiciones |
| P2.6 | engine_rt.py | `run_symbol()` | 19 | 15 | +4 | Extraer lógica de señales |
| P2.7 | engine_ai_rt.py | `load_model()` | 20 | 15 | +5 | Extraer búsqueda de modelo |
| P2.8 | engine_fetch.py | `obtener_datos_binance()` | 16 | 15 | +1 | Menor: pequeño ajuste |

### Refactorización de `engine_ai_rt.py::run_symbol()` (CC=84)

Esta es la función más compleja del proyecto. Propuesta de división:

```python
# Subfunción 1: Procesar candle nuevo
async def _process_candle(buffer, new_row, strategy, df_con_indicadores):
    """Valida, normaliza y prepara la fila para predicción."""
    # ... +20 líneas de lógica actual
    
# Subfunción 2: Predicción con ML Standard
def _predict_ml_standard(model, ultima_fila, feature_cols):
    """Ejecuta predicción con RandomForest/XGBoost/SVM/LightGBM."""
    # ... +30 líneas
    
# Subfunción 3: Predicción con Deep Learning
def _predict_deep_learning(model, ultima_fila, feature_cols):
    """Ejecuta predicción con TensorFlow/Keras."""
    # ... +25 líneas
    
# Subfunción 4: Emitir señal
def _emit_signal(symbol, action, price, timestamp, is_real, logger):
    """Formatea y emite señal por stdout."""
    # ... +10 líneas

# Función principal refactorizada: CC ≈ 12
async def run_symbol(symbol, timeframe, strategy_path, model_name, 
                     capital, risk_per_trade, is_real, max_candles=100):
    clean_symbol = symbol.lower().replace("/", "")
    url = f"wss://stream.binance.com:9443/ws/{clean_symbol}@kline_{timeframe}"
    
    strategy = load_strategy_by_path(strategy_path, capital, risk_per_trade)
    model, model_type = load_model(model_name, timeframe, symbol)
    df_buffer = deque(maxlen=max_candles)
    
    while True:
        try:
            async with websockets.connect(url) as ws:
                async for msg in ws:
                    data = json.loads(msg)
                    
                    await _process_candle(df_buffer, new_row, strategy, ...)
                    
                    if model_type == "ml_standard":
                        action = _predict_ml_standard(model, ...)
                    else:
                        action = _predict_deep_learning(model, ...)
                    
                    if action:
                        _emit_signal(symbol, action, price, ...)
        except Exception as e:
            # ... error handling
```

---

## P3 — Logging Duplicado y Configuración Inconsistente (🟡 MEDIA prioridad)

### P3.1: Replicación de setup de logging

**Logging duplicado**: ~90 líneas idénticas en 6 ficheros

**Situación actual**:
- `shared_utils.setup_engine_logging()` existe y funciona bien
- Solo `engine_indicators.py` y `engine_train.py` la usan
- Los demás (`engine_fetch.py`, `engine_rt.py`, `engine_ai_rt.py`, `engine_optimize.py`, `engine_backtest.py`) replican _manualmente_ el setup con 10-15 líneas cada uno

**Ejemplo de replicación innecesaria** (engine_fetch.py L20-36):
```python
# 15 líneas de setup manual → reemplazable por 1 línea
file_handler = logging.FileHandler(log_file, encoding='utf-8')
file_handler.setLevel(logging.DEBUG)
file_handler.setFormatter(logging.Formatter('%(asctime)s [%(levelname)s] %(message)s'))

stream_handler = logging.StreamHandler(sys.stderr)
stream_handler.setLevel(logging.INFO)
stream_handler.setFormatter(logging.Formatter('[%(levelname)s] %(message)s'))

logging.basicConfig(level=logging.DEBUG, handlers=[file_handler, stream_handler])
logger = logging.getLogger(__name__)
```

**Acción para cada fichero**:

| Fichero | Acción | Ahorro |
|---------|--------|--------|
| engine_fetch.py L20-36 | Reemplazar por `logger = setup_engine_logging("engine_fetch")` | 15 líneas |
| engine_rt.py L13-28 | Reemplazar por `logger = setup_engine_logging("engine_rt")` | 12 líneas |
| engine_ai_rt.py L20-40 | Reemplazar por `logger = setup_engine_logging("engine_ai_rt")` | 17 líneas |
| engine_optimize.py L50-73 | Reemplazar por `logger = setup_engine_logging("engine_optimize")` | 20 líneas |
| engine_backtest.py L13-28 | Reemplazar por `logger = setup_engine_logging("engine_backtest")` | 12 líneas |

**Total de líneas ahorradas con refactor**: ~76 líneas

---

### P3.2: Cálculo de rutas duplicado

**Situación actual**: Mismas líneas en 6 ficheros:
```python
current_dir = os.path.dirname(os.path.abspath(__file__))
code_dir = os.path.dirname(current_dir)
project_root = os.path.dirname(code_dir)
```

**Solución**: Estas constantes ya existen en `shared_utils.py`:
```python
SCRIPTS_DIR: str = os.path.dirname(os.path.abspath(__file__))
CODE_DIR: str = os.path.dirname(SCRIPTS_DIR)
PROJECT_ROOT: str = os.path.dirname(CODE_DIR)
```

**Acción**: En cada fichero, reemplazar variables locales por:
```python
from shared_utils import PROJECT_ROOT, CODE_DIR, SCRIPTS_DIR

# Eliminar las 3 líneas de cálculo manual
```

---

### P3.3: Unificar configuración de logging en `engine_optimize.py`

**Fichero**: [engine_optimize.py](code/scripts/engine_optimize.py#L50-73)

Actualmente no usa `setup_engine_logging()`. Migrar a la función estandarizada.

---

## P4 — Naming y Estilo PEP8 (🟡 MEDIA prioridad — SonarQube)

### Variables que violan PEP8

| # | Fichero | Variable | Línea | Regla violada | Cambio |
|---|---------|----------|-------|--------------|--------|
| P4.1 | engine_optimize.py | `X_tr` | L267, L372 | PEP8: `snake_case` | → `x_tr` |
| P4.2 | engine_optimize.py | `X_val` | L267, L372 | PEP8: `snake_case` | → `x_val` |
| P4.3 | engine_optimize.py | `X_df` | L690 | PEP8: `snake_case` | → `x_df` |
| P4.4 | engine_train.py | `X_df` | L218 | PEP8: `snake_case` | → `x_df` |
| P4.5 | engine_optimize.py | `X_np` | L702 | PEP8: `snake_case` | → `x_np` |
| P4.6 | engine_train.py | `X_np` | L226 | PEP8: `snake_case` | → `x_np` |
| P4.7 | engine_optimize.py | `X_train_full` | L719 | PEP8: `snake_case` | → `x_train_full` |

### Nota sobre convención ML

**⚠️ Decisión de diseño**: En Machine Learning, es **convención universal** usar `X` mayúscula para la matriz de features y `y` mayúscula para los targets. Esto se ve en:
- Scikit-learn: `X_train, y_train`
- XGBoost: `X, y`
- Papers académicos: matriz $X$ vs etiquetas $y$

**Recomendación**:
1. **Si sigues PEP8 estrictamente**: Cambiar a `x_df`, `x_tr`, etc.
2. **Si prefieres la convención ML** (más legible en contexto estadístico): Suprimir esta regla en SonarQube agregando comentario en el código:
   ```python
   # X, y son variables convencionales en ML para matriz de features y targets
   # noinspection PyPep8Naming
   X_df, y_ser = apply_strategy_features(...)
   ```

---

## P5 — Literales Duplicados (🟢 BAJA prioridad)

### P5.1: Literal `"periodo=14"` duplicado

**Fichero**: [engine_indicators.py](code/scripts/engine_indicators.py#L53)

**Código actual**:
```python
_INDICATOR_PARAMS: dict[str, str] = {
    "SMA_14": "periodo=14",
    "EMA_14": "periodo=14",
    "RSI_14": "periodo=14",
    "MACD": "fast=12,slow=26",
    "MACD_signal": "signal=9",
}
```

**Acción**: Extraer constante:
```python
_PERIODO_14 = "periodo=14"

_INDICATOR_PARAMS: dict[str, str] = {
    "SMA_14": _PERIODO_14,
    "EMA_14": _PERIODO_14,
    "RSI_14": _PERIODO_14,
    "MACD": "fast=12,slow=26",
    "MACD_signal": "signal=9",
}
```

---

## P6 — Falta de `__init__.py` y Estructura de Paquetes (🟡 MEDIA prioridad)

### P6.1: `code/scripts/` no es un paquete Python formal

**Problema**: No existe `code/scripts/__init__.py`

**Consecuencia**: No se puede hacer `from scripts import engine_fetch` desde otros módulos. Los imports funcionan solo por manipulación de `sys.path`.

**Acción**: Crear `code/scripts/__init__.py`:
```python
"""
Motores de cálculo para trading: fetch, indicadores, backtest, optimización, entrenamiento.

Cada módulo implementa un protocolo IPC MessagePack con framing 4-byte big-endian.
"""

__version__ = "1.0.0"

# Exports opcional: decorators útiles para otros Scripts
from .ipc_protocol import read_request_payload, write_response, write_error

__all__ = [
    "read_request_payload",
    "write_response", 
    "write_error",
]
```

---

### P6.2: `code/strategies/` no tiene `__init__.py` ni catálogo

**Problema**: 
- No existe `code/strategies/__init__.py`
- El plan del proyecto menciona `ESTRATEGIAS_DISPONIBLES: dict[str, BaseStrategy]` pero no existe

**Acción**: Crear `code/strategies/__init__.py`:
```python
"""
Catálogo de estrategias de trading disponibles.

Cada estrategia hereda de BaseStrategy e implementa:
  - populate_indicators(): enriquecimiento de señales técnicas
  - should_buy() / should_sell(): lógica de entrada/salida
  - get_feature_columns(): features para ML
  - get_label(): etiquetas forward-looking para entrenamientos
"""

from .BaseStrategy import BaseStrategy
from .StressTestStrategy import StressTestStrategy

# Catálogo de estrategias disponibles — autodescubrimiento
ESTRATEGIAS_DISPONIBLES: dict[str, type[BaseStrategy]] = {
    "StressTestStrategy": StressTestStrategy,
    # Añadir más estrategias aquí según se implementen
    # "RSISMAStrategy": RSISMAStrategy,
    # "AITraderStrategy": AITraderStrategy,
}

__all__ = [
    "BaseStrategy",
    "StressTestStrategy",
    "ESTRATEGIAS_DISPONIBLES",
]
```

---

### P6.3: `code/scripts/tests/` sin `__init__.py` ni `conftest.py`

**Problema**: 
- No existe `code/scripts/tests/__init__.py`
- No existe `conftest.py` con fixtures compartidas

**Acción**: Crear ambos:

**`code/scripts/tests/__init__.py`**:
```python
"""
Tests para los motores de trading (IPC, indicadores, backtesting, optimización).
"""
```

**`code/scripts/tests/conftest.py`** (fixtures compartidas):
```python
"""
Fixtures compartidas para tests de engines IPC.
"""
import pytest
import pandas as pd
import numpy as np
from datetime import datetime, timedelta


@pytest.fixture
def df_ohlcv_mock() -> pd.DataFrame:
    """DataFrame OHLCV sintético de 200 velas para tests."""
    n = 200
    rng = np.random.default_rng(seed=42)
    base_price = 40000.0
    returns = rng.normal(0, 0.001, n)
    close = base_price * np.exp(np.cumsum(returns))
    
    start = datetime(2024, 1, 1)
    timestamps = [int((start + timedelta(hours=i)).timestamp() * 1000) for i in range(n)]
    
    return pd.DataFrame({
        "timestamp": timestamps,
        "open": close * 0.999,
        "high": close * 1.002,
        "low": close * 0.998,
        "close": close,
        "volume": rng.uniform(100, 1000, n),
    })


@pytest.fixture
def ipc_payload_fetch() -> dict:
    """Payload MessagePack típico para FETCH_REQUEST."""
    return {
        "symbol": "BTCUSDT",
        "timeframe": "1h",
        "since_ms": 1704067200000,  # 2024-01-01
    }


@pytest.fixture
def ipc_payload_indicators(df_ohlcv_mock) -> dict:
    """Payload MessagePack típico para INDICATORS_REQUEST."""
    return {
        "velas": df_ohlcv_mock.to_dict(orient="records"),
    }
```

---

## P7 — Type Hints Ausentes (🟡 MEDIA prioridad)

### Funciones sin tipado

| # | Fichero | Función | Parámetros sin tipo | Retorno sin tipo |
|---|---------|---------|---------------------|------------------|
| P7.1 | BaseStrategy.py | `populate_indicators` | `df` | ✓ |
| P7.2 | BaseStrategy.py | `should_buy` | `row` | ✓ |
| P7.3 | BaseStrategy.py | `should_sell` | `row` | ✓ |
| P7.4 | BaseStrategy.py | `get_label` | `row, next_row` | ✓ |
| P7.5 | engine_rt.py | `main` | — | ✓ |
| P7.6 | engine_rt.py | `run_all` | todos | ✓ |
| P7.7 | engine_rt.py | `run_symbol` | todos | ✓ |
| P7.8 | engine_ai_rt.py | `load_model` | todos | ✓ |
| P7.9 | engine_ai_rt.py | `run_symbol` | todos | ✓ |
| P7.10 | engine_backtest.py | `main` | — | ✓ |
| P7.11 | ipc_protocol.py | `_to_text_keys` | `value` | ✓ |

### Ejemplo de refactorización (BaseStrategy.py)

**Antes**:
```python
def populate_indicators(self, df):
    pass

def should_buy(self, row) -> bool:
    pass
```

**Después**:
```python
def populate_indicators(self, df: pd.DataFrame) -> pd.DataFrame:
    pass

def should_buy(self, row: dict[str, float]) -> bool:
    pass

def get_label(self, row: dict[str, float], next_row: dict[str, float]) -> int:
    pass
```

---

## P8 — Cobertura de Tests (🔴 ALTA prioridad)

### Situación actual

| Aspecto | Estado | Cobertura |
|---------|--------|-----------|
| **Tests existentes** | 4 ficheros | ~30% |
| **Ficheros sin tests** | backtest_engine.py, engine_optimize.py, engine_train.py, engine_fetch.py, engine_rt.py, engine_ai_rt.py | 0% |
| **Módulos sin tests** | shared_utils.py (funciones críticas: `load_strategy_by_name`, `apply_strategy_features`) | 0% |
| **Estrategias sin tests** | BaseStrategy.py, StressTestStrategy.py | 0% |

### Tests a crear (por prioridad)

| # | Módulo | Fichero | Funcs a cubrir | Estimado tests |
|---|--------|---------|----------------|-----------------|
| P8.1 | backtest_engine.py | `test_backtest_engine.py` | `run_backtest`, `run_backtest_with_predictions`, `_calculate_backtest_stats` | 12-15 |
| P8.2 | shared_utils.py | `test_shared_utils.py` | `load_strategy_by_name`, `load_strategy_by_path`, `apply_strategy_features`, `setup_engine_logging` | 10-12 |
| P8.3 | BaseStrategy.py | `test_base_strategy.py` | `get_label`, `get_feature_columns`, `get_position_size`, `get_warmup_period` | 8-10 |
| P8.4 | StressTestStrategy.py | `test_stress_test_strategy.py` | `populate_indicators`, `should_buy`, `should_sell`, `get_feature_columns` | 8-10 |
| P8.5 | engine_optimize.py (core) | `test_engine_optimize_core.py` | `_compute_composite_score`, `_build_sklearn_model`, `_suggest_xgboost`, objetivo Optuna | 10-12 |
| P8.6 | engine_train.py (core) | `test_engine_train_core.py` | `get_ml_model_instance`, `build_and_train_neural_network` | 8-10 |

**Total estimado de casos de test a escribir**: 56-69 nuevos tests

### Ejemplo de test (backtest_engine.py)

```python
# test_backtest_engine.py
import pytest
import pandas as pd
from backtest_engine import run_backtest, run_backtest_with_predictions
from strategies.StressTestStrategy import StressTestStrategy


class TestRunBacktest:
    """Tests para run_backtest() con señales de estrategia."""

    def test_should_execute_backtest_with_valid_data(self, df_ohlcv_mock):
        """✓ Debe ejecutar backtest sin errores con datos válidos."""
        strategy = StressTestStrategy(capital=1000, risk_per_trade=0.02)
        trade_count, stats = run_backtest(
            strategy, df_ohlcv_mock, "BTCUSDT"
        )
        
        assert trade_count >= 0
        assert "win_rate" in stats
        assert "profit_factor" in stats
        assert stats["win_rate"] >= 0.0
        assert stats["capital_final"] >= 0.0

    def test_should_return_zero_trades_with_no_signals(self, df_ohlcv_mock):
        """✓ Debe devolver 0 trades si la estrategia nunca emite buy/sell."""
        # Estrategia dummy sin señales
        class NoSignalStrategy(BaseStrategy):
            def populate_indicators(self, df): return df
            def should_buy(self, row): return False
            def should_sell(self, row): return False
            def get_stop_loss(self, entry_price, row): return entry_price * 0.99
            def get_take_profit(self, entry_price, row): return entry_price * 1.01
        
        strategy = NoSignalStrategy(capital=1000)
        trade_count, stats = run_backtest(strategy, df_ohlcv_mock, "BTCUSDT")
        
        assert trade_count == 0
        assert stats["win_rate"] == 0.0


class TestRunBacktestWithPredictions:
    """Tests para run_backtest_with_predictions() con predicciones ML."""

    def test_should_execute_backtest_with_predictions(self, df_ohlcv_mock):
        """✓ Debe ejecutar backtest con array de predicciones ML."""
        strategy = StressTestStrategy(capital=1000)
        predictions = np.array([0, 1, -1] * (len(df_ohlcv_mock) // 3 + 1))[:len(df_ohlcv_mock)]
        
        stats = run_backtest_with_predictions(
            strategy, df_ohlcv_mock, predictions, "BTCUSDT"
        )
        
        assert isinstance(stats, dict)
        assert "win_rate" in stats
        assert stats["capital_final"] >= 0.0

    def test_should_handle_all_zero_predictions(self, df_ohlcv_mock):
        """✓ Debe manejar predicciones todas en HOLD (0)."""
        strategy = StressTestStrategy(capital=1000)
        predictions = np.zeros(len(df_ohlcv_mock), dtype=int)
        
        stats = run_backtest_with_predictions(strategy, df_ohlcv_mock, predictions, "BTCUSDT")
        
        assert stats["win_rate"] == 0.0  # Sin trades
        assert stats["capital_final"] == 1000.0  # Capital sin cambios
```

---

## P9 — Código Duplicado entre Engines (🟢 BAJA prioridad)

### P9.1: Duplicación de entrenamiento final

**Situación**: `_train_final_model()` en `engine_optimize.py` (~150 líneas) replica ~80% del código de `engine_train.py::main()` (L240-320).

**Candidatos para extracción a `shared_utils.py`**:
- Setup de LabelEncoder y distribución de labels
- Split 80/20 de train/test
- Entrenamiento ML vs Neural (if/else)
- Guardado de modelo y metadatos en `models/`

**Acción**: Crear función en `shared_utils.py`:
```python
def train_and_save_model(
    model_type: str,
    X_train: np.ndarray,
    y_train: np.ndarray,
    X_test: np.ndarray,
    y_test: np.ndarray,
    feature_cols: list[str],
    symbol: str,
    timeframe: str,
    strategy_name: str | None = None,
    hyperparams: dict | None = None,
) -> tuple[Any, str, dict[str, float]]:
    """
    Entrena modelo, lo guarda en models/ y devuelve (modelo, filename, métricas).
    Se puede llamar desde engine_train.py y engine_optimize.py.
    """
    # ... implementación única
```

**Ahorro**: ~150 líneas eliminadas de engine_optimize.py, reutilización desde engine_train.py

---

### P9.2: Preparación de DataFrame/LabelEncoder duplicada

**Código duplicado** (engine_train.py L195-235, engine_optimize.py L645-700):
1. Cargar estrategia
2. Llamar `populate_indicators()`
3. Llamar `apply_strategy_features()`
4. LabelEncoder + recodificación
5. Split 80/20

**Acción**: Extraer a `shared_utils.py`:
```python
def prepare_training_dataset(
    df_raw: pd.DataFrame,
    strategy: Any,
    warmup_candles: int = 0,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, list[str], LabelEncoder]:
    """
    Prepara dataset para entrenamiento: enriquecimiento, features, labels, split 80/20.
    Devuelve: X_train, X_test, y_train, y_test, feature_cols, label_encoder
    """
    # ... implementación de ~50 líneas
```

---

### P9.3: Construcción de red neuronal duplicada

**Código duplicado** en dos lugares:
- `engine_optimize.py::_build_and_eval_neural_network()` (~100 líneas)
- `engine_train.py::build_and_train_neural_network()` (~80 líneas)

Son esencialmente idénticas con pequeñas variaciones.

**Acción**: Centralizar en `shared_utils.py`:
```python
def build_neural_network_model(
    X_train: np.ndarray,
    y_train: np.ndarray,
    hyperparams: dict,
    n_classes: int = 2,
    cv_folds: int | None = None,  # Si None, no hace CV; si > 0, hace CV
) -> tuple[Any, np.ndarray, float | None]:
    """
    Construye y entrena red neuronal.
    Si cv_folds > 0: retorna (modelo_final, y_pred, cv_accuracy)
    Si cv_folds es None: retorna (modelo, y_pred, None)
    """
```

**Ahorro**: ~80 líneas eliminadas, lógica centralizada

---

## P10 — Seguridad y Robustez (🟢BAJA prioridad)

### P10.1: Ternario anidado difícil de leer

**Fichero**: [engine_ai_rt.py](code/scripts/engine_ai_rt.py#L91)

**Código actual**:
```python
dl_path = keras_path if os.path.exists(keras_path) else h5_path if os.path.exists(h5_path) else None
```

**Problema**: Difícil leer y mantener.

**Acción**: Extraer función:
```python
def _find_model_path(base_filename: str, models_dir: str) -> str | None:
    """Busca archivo de modelo con extensión .keras, .h5 o .pkl."""
    for ext in (".keras", ".h5", ".pkl"):
        path = os.path.join(models_dir, f"{base_filename}{ext}")
        if os.path.exists(path):
            return path
    return None

# Uso
dl_path = _find_model_path(base_filename, models_dir)
```

---

### P10.2: Condicionales anidadas innecesarias

**Fichero**: [engine_ai_rt.py](code/scripts/engine_ai_rt.py#L52)

**Código actual**:
```python
if sys.platform == "win32":
    if "posix" not in sys.modules:
        sys.modules["posix"] = types.ModuleType("posix")
```

**Problema**: SonarQube pide fusion de condiciones.

**Acción**:
```python
if sys.platform == "win32" and "posix" not in sys.modules:
    sys.modules["posix"] = types.ModuleType("posix")
```

---

## Orden de Ejecución Recomendado

### Fase 1: Bugs y Reproducibilidad (1-2 horas)
1. **P1.1 - P1.5**: Bugs reales (rápido, alto impacto)
   - Cambiar `inplace=True` por asignación
   - Arreglar NaN comparison
   - Añadir `random_state` explícito
   - Hacer hiperparámetros explícitos

### Fase 2: Logging (30 min)
2. **P3.1 - P3.3**: Unificar logging
   - Migrar 6 ficheros a `setup_engine_logging()`
   - Eliminar 76+ líneas duplicadas
   - Eliminar cálculo manual de rutas

### Fase 3: Refactorización Mayor (3-4 horas)
3. **P2**: Reducir complejidad cognitiva
   - Dividir `engine_ai_rt.run_symbol()` (CC=84 → CC≈12)
   - Extraer subfunciones en backtest_engine.py
   
4. **P6**: Estructura de paquetes
   - Crear `__init__.py` en scripts/ y strategies/
   - Implementar catálogo `ESTRATEGIAS_DISPONIBLES`

### Fase 4: Tests (4-6 horas)
5. **P8**: Coverage
   - Crear `conftest.py` con fixtures
   - Escribir tests para backtest_engine.py (~15 tests)
   - Escribir tests para shared_utils.py (~12 tests)
   - Escribir tests para estrategias (~20 tests)

### Fase 5: Cleanup gradual (2-3 horas)
6. **P4, P5, P7**: Naming, literals, type hints
   - Cambiar `X_df` → `x_df` (u omitir si se prefiere convención ML)
   - Extraer literal duplicado `"periodo=14"`
   - Añadir type hints a funciones públicas

### Fase 6: Refactorización avanzada (2-3 horas)
7. **P9**: Eliminar código duplicado
   - Extraer `train_and_save_model()` a shared_utils
   - Centralizar entrenamiento neural
   - Reutilizar desde engine_train y engine_optimize

---

## Resumen de Impacto

| Fase | Esfuerzo | Impacto | Líneas |
|------|----------|--------|--------|
| **P1** | 1h | 🔴 Crítico (fixes) | — |
| **P3** | 30min | 🔴 Alto (DRY) | -76 |
| **P2** | 3h | 🔴 Alto (mantenibilidad) | -150 |
| **P6** | 1.5h | 🔴 Alto (estructura) | +50 |
| **P8** | 5h | 🔴 Alto (confianza) | +300 tests |
| **P9** | 2.5h | 🔴 Alto (DRY) | -250 |
| **P4,P5,P7** | 2h | 🟡 Medio | -15 |
| **Total** | ~15h | 🔴 Muy Alto | -450 líneas, +300 tests |

---

## Checklist de Implementación

### Fase 1: Bugs (P1)
- [ ] P1.1: Añadir `random_state=42` a StratifiedKFold
- [ ] P1.2: Hacer `learning_rate` explícito en GradientBoosting
- [ ] P1.3: Hacer `C`, `kernel`, `gamma` explícitos en SVC
- [ ] P1.4: Reemplazar `inplace=True` por asignación (2 lugares)
- [ ] P1.5: Usar `math.isnan()` en lugar de `float("nan") == v`

### Fase 2: Logging (P3)
- [ ] P3.1: Migrar engine_fetch.py a setup_engine_logging()
- [ ] P3.1: Migrar engine_rt.py a setup_engine_logging()
- [ ] P3.1: Migrar engine_ai_rt.py a setup_engine_logging()
- [ ] P3.1: Migrar engine_optimize.py a setup_engine_logging()
- [ ] P3.1: Migrar engine_backtest.py a setup_engine_logging()
- [ ] P3.2: Reemplazar cálculo manual de rutas en todos (6 ficheros)

### Fase 3: Refactorización (P2 + P6)
- [ ] P2.1: Dividir `engine_ai_rt.run_symbol()` en subfunciones
- [ ] P2.2-P2.8: Extraer subfunciones en otros engines
- [ ] P6.1: Crear `code/scripts/__init__.py`
- [ ] P6.2: Crear `code/strategies/__init__.py` con catálogo
- [ ] P6.3: Crear `code/scripts/tests/__init__.py` y `conftest.py`

### Fase 4: Tests (P8)
- [ ] P8.1: Crear `test_backtest_engine.py` (~15 tests)
- [ ] P8.2: Crear `test_shared_utils.py` (~12 tests)
- [ ] P8.3: Crear `test_base_strategy.py` (~10 tests)
- [ ] P8.4: Crear `test_stress_test_strategy.py` (~10 tests)
- [ ] P8.5: Crear `test_engine_optimize_core.py` (~12 tests)
- [ ] P8.6: Crear `test_engine_train_core.py` (~10 tests)

### Fase 5: Cleanup (P4 + P5 + P7)
- [ ] P4.1-P4.7: Cambiar variable names (X_df → x_df, etc.)
- [ ] P5.1: Extraer `_PERIODO_14` en engine_indicators.py
- [ ] P7.1-P7.11: Añadir type hints a funciones públicas

### Fase 6: DRY (P9)
- [ ] P9.1: Crear `train_and_save_model()` en shared_utils
- [ ] P9.2: Crear `prepare_training_dataset()` en shared_utils
- [ ] P9.3: Centralizar `build_neural_network_model()` en shared_utils

### Fase 7: Robustez (P10)
- [ ] P10.1: Extraer `_find_model_path()` en engine_ai_rt.py
- [ ] P10.2: Fusionar condicionales en engine_ai_rt.py

---

## Recursos Asociados

### Convenciones del Proyecto (desde copilot-instructions.md)
- **Lenguaje**: Python 3.11+, `match`, type hints
- **Logging**: `@Slf4j` (Java) → `setup_engine_logging()` (Python)
- **IPC**: MessagePack binario con framing 4-byte big-endian
- **Datos**: `BigDecimal` (Java), `Decimal` (Python) para precios
- **Testing**: pytest + fixtures con `conftest.py`

### Referencias
- SonarQube Cognitive Complexity: https://www.sonarsource.com/docs/
- PEP 8 Python Style Guide: https://pep8.org/
- pytest Best Practices: https://docs.pytest.org/

---

**Documento generado**: 14 de abril de 2026  
**Próximo paso recomendado**: Implementar Fase 1 (P1 bugs) + Fase 2 (P3 logging) en paralelo = 1.5h
