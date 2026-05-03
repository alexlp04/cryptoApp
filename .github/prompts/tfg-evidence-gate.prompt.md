---
name: TFG Evidence Gate
description: "Usa el agente del TFG con una puerta de verificacion obligatoria: genera primero una tabla afirmacion-evidencia antes de redactar, revisar o editar cualquier archivo .tex del TFG."
argument-hint: "Tema, seccion o cambio que quieres documentar"
agent: AGENTE_TFG_EXTRACTOR
---

Aplica este flujo de forma estricta a la solicitud actual:

1. No redactes ni edites ningun archivo `docs/*.tex` al inicio.
2. Usa la skill `tfg-source-locator` para localizar las fuentes primarias
   relevantes en `docs/`, `code/` y `docs/referencias.bib`.
3. Usa la skill `tfg-evidence-check` y construye una tabla con este
   formato antes de proponer texto:

   | Afirmacion | Estado | Evidencia exacta | Motivo |
   | --- | --- | --- | --- |

4. Solo puedes seguir a redaccion o edicion si las afirmaciones necesarias
   para el cambio quedan en estado `verificada`.
5. Si hay afirmaciones `parcial` o `no verificada`, deten el flujo y
   devuelve un bloque de huecos pendientes en lugar de inventar contenido.
6. Solo despues usa la skill `tfg-latex-writer` para generar el bloque
   LaTeX o el parche solicitado.

Entrega la respuesta en este orden:

1. `Tabla de evidencia`
2. `Huecos o riesgos de verificacion`
3. `Bloque LaTeX o propuesta de edicion`, solo si todo lo necesario esta
   verificado