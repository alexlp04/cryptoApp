# Codigo Fuente De CryptoApp

La documentacion principal del repositorio vive en el README raiz:

[`../README.md`](../README.md)

Este directorio contiene el nucleo ejecutable del proyecto:

- `backendBotTrading/` para el backend Java, la CLI interactiva y los tests Maven. El esquema de
  base de datos se versiona con Flyway en `src/main/resources/db/migration/`.
- `scripts/` para los engines Python de fetch, indicadores, backtesting, entrenamiento y
  optimizacion, con su suite pytest en `scripts/tests/`.
- `strategies/` para las estrategias cargadas dinamicamente por los motores.

Si vas a arrancar la aplicacion, validar tests o revisar la arquitectura completa, empieza por el README raiz y usa este subdirectorio como mapa del codigo fuente.

Para trabajar sobre el codigo —contratos IPC, contrato de estrategias y reglas de estilo—
la guia es [`../CLAUDE.md`](../CLAUDE.md).
