---
name: tfg-source-locator
description: 'Localiza evidencia en docs/ y code/ para el TFG. Usala cuando necesites encontrar rapido donde buscar arquitectura, metodologia, IPC, estrategias, tests, bibliografia o cualquier afirmacion antes de verificarla o redactarla.'
argument-hint: 'Tema, afirmacion o seccion a localizar'
---

# TFG Source Locator

## Cuando usarla

- Cuando la peticion aun no apunta a archivos concretos.
- Cuando la pregunta mezcla documentacion existente y codigo.
- Cuando necesitas decidir primero en que carpeta buscar para no abrir
  archivos al azar.

## Procedimiento

1. Lee `/memories/repo/tfg-docs-structure.md` y
   `/memories/repo/tfg-code-structure.md`.
2. Usa [search anchors](./references/search-anchors.md) para mapear el
   tema a carpetas y ficheros semilla.
3. Prioriza siempre fuentes primarias:
   - `.tex` para el estado actual del TFG;
   - codigo fuente en `code/backendBotTrading/src/main`, `code/scripts`
     y `code/strategies`;
   - tests cuando sirvan como evidencia secundaria de comportamiento;
   - `docs/referencias.bib` para bibliografia ya existente.
4. Excluye artefactos generados salvo que el usuario los pida de forma
   explicita:
   - `target/`
   - `__pycache__/`
   - `.pytest_cache/`
   - artefactos auxiliares de LaTeX en `docs/`
5. Devuelve una ruta de busqueda minima y ordenada. No redactes el texto
   final en esta fase.

## Formato de salida

- `Tema`
- `Archivos prioritarios`
- `Motivo de cada archivo`
- `Siguientes busquedas`