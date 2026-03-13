# Contexto General del Sistema: Orquestador de BotTrading

## Naturaleza del Proyecto
Sistema de trading cuantitativo automatizado híbrido. Descarga datos históricos, ejecuta backtesting algorítmico y opera en tiempo real (Paper/Live Trading).

## Arquitectura Híbrida
- **Orquestador (Java 21 / Spring Boot 3):** Cerebro central. CLI, persistencia masiva, control de hilos.
- **Motor (Python 3):** Cálculo matemático. Descargas de Binance, algoritmos vectorizados con Pandas.
- **Persistencia (MySQL 8+):** Almacén de velas (Candlesticks), configuración y contabilidad.

## Enrutamiento de Modelos Recomendado
- **Claude Sonnet 4.6:** Refactorización de lógica de negocio (Java/Python). Sigue instrucciones al milímetro.
- **Claude Opus 4.6:** Decisiones arquitectónicas complejas y depuración de concurrencia/IPC.
- **GPT-5.2-Codex:** Estructuración de base de datos, SQL nativo y optimización de JPA.
- **GPT-5 mini / Claude Haiku 4.5:** Autocompletado rápido o explicaciones de excepciones cortas.

## Filosofía de Ingeniería
1. Tolerancia a fallos: Python no debe colapsar la JVM.
2. Eficiencia Espacial: O(1) en memoria para flujos de datos masivos.
3. IPC Estricto: Streaming asíncrono vía TSV.