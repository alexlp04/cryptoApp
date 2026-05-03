---
name: tfg-evidence-check
description: 'Verifica afirmaciones para la documentacion del TFG contra codigo del repositorio, archivos .tex existentes o referencias ya presentes en docs/referencias.bib. Usala para contrastar frases, construir trazabilidad y descartar contenido no sustentado.'
argument-hint: 'Afirmacion, parrafo o apartado a verificar'
---

# TFG Evidence Check

## Regla central

- Ninguna afirmacion tecnica entra en el documento si no queda marcada
  como `verificada`.

## Fuentes validas

- Codigo fuente del repositorio.
- Texto existente en `docs/*.tex` y `docs/diagramas/*.md`.
- Claves ya existentes en `docs/referencias.bib`.
- Una fuente explicita aportada por el usuario.

La memoria del repositorio sirve para orientarte, pero no cuenta como
prueba final por si sola.

## Procedimiento

1. Divide la solicitud en afirmaciones atomicas.
2. Usa la skill `tfg-source-locator` para obtener los ficheros semilla.
3. Lee solo el minimo necesario para cada afirmacion.
4. Rellena la tabla de [evidence table template](./references/evidence-table-template.md).
5. Marca cada afirmacion como `verificada`, `parcial` o `no verificada`.
6. Si alguna afirmacion necesaria queda fuera de `verificada`, detente y
   devuelve el hueco en vez de redactar como si estuviera resuelto.

## Reglas practicas

- Prefiere evidencia de codigo frente a descripciones derivadas.
- Para versiones o dependencias, prioriza `pom.xml`, `requirements.txt` o
  configuracion equivalente.
- Para afirmaciones bibliograficas, no inventes claves. Si la clave no
  existe en `docs/referencias.bib`, la afirmacion no esta lista para ser
  insertada en LaTeX.
- No uses artefactos generados como fuente principal salvo que la pregunta
  trate precisamente sobre ellos.

## Resultado minimo

- Tabla de afirmaciones y estado.
- Lista de pruebas exactas por cada afirmacion verificada.
- Lista separada de huecos y preguntas abiertas.