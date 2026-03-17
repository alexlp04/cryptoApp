# Plan: Soporte completo para `trade -v -model <nombre> -strategy <estrategia> -tf <tf> -coins <monedas>`

Objetivo final: que el usuario pueda renombrar libremente un modelo entrenado y ejecutar paper-trading con IA usando el nombre completo del archivo.

---

## Tareas

### TAREA 1 — `engine_ai_rt.py`: `load_model()` no encuentra modelos con sufijo `_{strategy}` ni nombres libres
**Fichero:** `code/scripts/engine_ai_rt.py` (~línea 79)

**Problema:**  
La función construye siempre `{model_name}_{timeframe}_{symbol}` y busca `.pkl/.keras/.h5`.  
Si el modelo se llama `random_forest_1h_BTCUSDT_AITraderStrategy.pkl` (sufijo de estrategia que añade `engine_train.py`) o el usuario lo ha renombrado a `mi_modelo_v2.pkl`, nunca lo encuentra y lanza `FileNotFoundError`.

**Cambio:**  
Añadir dos búsquedas previas antes del raise:
1. Intentar el nombre como nombre base directo: `models/<model_name>.pkl` (modelo renombrado libremente).
2. Escanear el directorio buscando archivos que empiecen por `{base_filename}_` (sufijo de estrategia).

---

### TAREA 2 — `PathConfig.existeModelo()`: no acepta nombre de archivo completo (bare filename)
**Fichero:** `code/backendBotTrading/src/main/java/com/bottrading/utils/PathConfig.java` (~línea 111)

**Problema:**  
Java construye siempre `{modelo}_{timeframe}_{symbol}` antes de buscar en disco. Si el modelo existe sólo como `mi_modelo_v2.pkl` (nombre libre, sin tf/symbol embebidos), devuelve `false` y el bot rechaza el comando.

**Cambio:**  
Antes de construir `baseName`, comprobar si `Paths.get(MODELS_DIR).resolve(modelo + ext)` existe directamente para cada extensión conocida.

---

### TAREA 3 — `TradingService.enviarPayload()`: NPE cuando `nombreEstrategia` es null
**Fichero:** `code/backendBotTrading/src/main/java/com/bottrading/services/TradingService.java` (~línea 405)

**Problema:**  
`payload.put("strategy_path", PathConfig.getValidStrategyPath(inst.getNombreEstrategia()))` se llama sin comprobar si `getNombreEstrategia()` es null. Si el usuario arranca un bot con `-model` sin `-strategy`, `NullPointerException` en runtime.

**Cambio:**  
Envolver la llamada en un guard con null-check; sólo incluir `strategy_path` en el payload si la estrategia está definida.

---

### TAREA 4 — `AppBot.java`: no exige `-strategy` cuando se usa `-model`
**Fichero:** `code/backendBotTrading/src/main/java/com/bottrading/AppBot.java` (~línea 290)

**Problema:**  
La validación actual permite arrancar con `-model` sin `-strategy`. El script `engine_ai_rt.py` requiere la estrategia para calcular los indicadores (feature engineering), así que sin ella falla en Python con un KeyError.

**Cambio:**  
Añadir validación explícita: si se pasa `-model`, es obligatorio pasar también `-strategy`.  
Adicionalmente mejorar el mensaje de error de modelo no encontrado (incluir tf y coin).

---

## Orden de Ejecución

| # | Tarea | Dónde | Riesgo |
|---|-------|-------|--------|
| 1 | `load_model()` acepta nombre libre y sufijo estrategia | Python | Bajo |
| 2 | `existeModelo()` acepta nombre libre | Java | Bajo |
| 3 | Null-check `nombreEstrategia` en `enviarPayload()` | Java | Bajo |
| 4 | Validar `-strategy` requerido con `-model` | Java | Bajo |

Sin dependencias entre tareas → se pueden aplicar todas en paralelo.

---

## Comando objetivo tras los fixes

```
trade -v -model random_forest -strategy AITraderStrategy -tf 1h -coins BTCUSDT ETHUSDT
```

O con modelo renombrado libremente:

```
trade -v -model mi_modelo_v2 -strategy AITraderStrategy -tf 1h -coins BTCUSDT
```
