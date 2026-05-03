# Search Anchors

## Documento del TFG

- `docs/main.tex`: documento raiz y orden de inclusion.
- `docs/1.Resumen.tex` a `docs/9.Glosario.tex`: capitulos principales.
- `docs/6.AnalisisMetodologia/*.tex`: metodologia y apartados tecnicos.
- `docs/referencias.bib`: claves bibliograficas reutilizables.
- `docs/diagramas/*.md`: apoyo descriptivo de clases y modulos.

## Backend Java

- `code/backendBotTrading/pom.xml`: stack, dependencias y versiones.
- `code/backendBotTrading/src/main/java/com/bottrading/config`: configuracion.
- `code/backendBotTrading/src/main/java/com/bottrading/interfaces/cli`: CLI.
- `code/backendBotTrading/src/main/java/com/bottrading/*/application`: casos de uso.
- `code/backendBotTrading/src/main/java/com/bottrading/*/domain`: entidades y contratos.
- `code/backendBotTrading/src/main/java/com/bottrading/*/infrastructure`: adaptadores tecnicos.

## Temas frecuentes y anclajes

- Arquitectura y capas: `shared`, `interfaces`, los paquetes `application`,
  `domain` e `infrastructure`, y `docs/6.AnalisisMetodologia/6.3.ArquitecturaGeneral.tex`.
- CLI y flujo interactivo: `interfaces/cli` y `docs/7.DesarrolloTrabajo.tex`.
- Mercado e indicadores: `market/*`, `code/scripts/engine_fetch.py`,
  `code/scripts/engine_indicators.py`.
- Backtesting: `backtesting/*`, `code/scripts/engine_backtest.py`,
  `code/scripts/backtest_engine.py`.
- Trading en tiempo real: `trading/*`, `code/scripts/engine_rt.py`,
  `code/scripts/engine_ai_rt.py`.
- Entrenamiento y optimizacion: `training/*`, `code/scripts/engine_train.py`,
  `code/scripts/engine_optimize.py`.
- Estrategias Python: `code/strategies/BaseStrategy.py` y estrategias concretas.
- IPC Java-Python: `trading/infrastructure/bridge`,
  `training/infrastructure/bridge`, `code/scripts/ipc_protocol.py` y
  `docs/6.AnalisisMetodologia/6.7.ProtocoloIPC.tex`.
- Persistencia y ETL: `*/infrastructure/persistence`, `pom.xml` y
  `docs/6.AnalisisMetodologia/6.9.PipelineETLPersistenciaMasiva.tex`.

## Tests utiles como apoyo

- `code/backendBotTrading/src/test/java/com/bottrading/**`: contratos de servicios.
- `code/scripts/tests/*.py`: contrato del motor Python y del protocolo IPC.

## Evitar por defecto

- `code/backendBotTrading/target/**`
- `code/scripts/__pycache__/**`
- `code/scripts/.pytest_cache/**`
- artefactos auxiliares generados por LaTeX en `docs/`