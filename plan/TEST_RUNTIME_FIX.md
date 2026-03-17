# Validación de Fix: Runtime Engine Timeout Issue

## Resumen del Fix
- **Problema original**: Timeout de 30 segundos causaba fallos en `-trade` con múltiples pares
- **Raíz**: Timeout global medía desde inicio hasta primeiro output; motor necesitaba >30s para inicializar
- **Solución implementada**:
  1. TradingService: timeout adaptativo (90s init + 5m inactividad)
  2. Todos los motores Python: logging a stdout para visibilidad

## Pasos para Probar

### Opción A: Test rápido (sin real-time)
```bash
# Desde directorio raíz del proyecto
cd /home/alejandro/Documentos/Informatica/cryptoapp

# 1. Compilar backend
cd code/backendBotTrading
mvn clean -q -DskipTests install

# 2. Ejecutar bot interactivo
java -jar target/backendBotTrading-1.0.0.jar

# En el CLI de bot, ejecutar:
# > trade -v -s StressTestStrategy -t 1m -c BTCUSDT ETHUSDT
# (usa menos pares para test más rápido)
```

### Opción B: Test con logs visibles
```bash
# Ejecutar con redirección de logs
java -jar ... 2>&1 | tee test_output.log

# Observar en tiempo real:
# [INFO] Arrancando Motor Estándar...
# [INFO] Cargando estrategia desde: ...
# [INFO] Iniciando stream para BTCUSDT...
# [INFO] Conectado a WebSocket...
```

## Indicadores de Éxito
✅ **Motor inicia sin timeout** (aunque tarde >30s en inicializar)
✅ **Java ve logs de progreso** en stdout
✅ **Una vez conectado, motor envía SIGNALS** sin timeouts
✅ **Salida de logs menciona**:
   - "Estrategia X superó inicialización"
   - Después: "monitoreo de inactividad (5m)"

## Cambios Exactos

### Java (TradingService.java)
- Método `escucharSalidaPythonConTimeout()` reescrito con:
  - Phase 1: 90s timeout (detectado por primer output)
  - Phase 2: 5m timeout de inactividad (líneas sin output)
  - Flag `primeraActividad` para transición entre fases

### Python (todos los motores)
- Logging bifurcado en `basicConfig()`:
  ```python
  handlers=[file_handler, stream_handler]  # archivo DEBUG + stdout INFO
  ```
- **Motores actualizados**: engine_rt.py, engine_ai_rt.py, engine_fetch.py, engine_backtest.py, engine_indicators.py

## Fallback Compatibility
- Java espera JSON directo vía stdin (no cambio en `enviarPayload`)
- Python `read_request_payload()` ya soporta fallbacks automáticos
- **Efecto**: Zero breaking changes; IPC Fase 1 no afectado

## Próximos Pasos (Optional)
1. Monitor en producción durante 48h para validar inactivity timeout (5m)
2. Ajustar INACTIVITY_TIMEOUT_MS si es necesario (actualmente 300s)
3. Considerar estadísticas de inicialización para informar sobre motor lento

## Archivos Modificados
- `/code/backendBotTrading/src/main/java/com/bottrading/services/TradingService.java`
- `/code/scripts/engine_rt.py`
- `/code/scripts/engine_ai_rt.py`  
- `/code/scripts/engine_fetch.py`
- `/code/scripts/engine_backtest.py`
- `/code/scripts/engine_indicators.py`

## Validación Pre-Testing
- ✅ Java compilation: mvn clean -q compile (success)
- ✅ Python syntax: Pylance on all 5 modified scripts (pass)
- ✅ IPC protocol: no changes (backward compatible)
