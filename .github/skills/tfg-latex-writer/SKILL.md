---
name: tfg-latex-writer
description: 'Redacta o edita secciones LaTeX del TFG solo con afirmaciones ya verificadas. Usala para ampliar metodologia, arquitectura, IPC, patrones, glosario o conclusiones sin duplicar contenido existente.'
argument-hint: 'Seccion, subseccion o fichero .tex a redactar'
---

# TFG LaTeX Writer

## Precondicion obligatoria

- La tabla de evidencia debe estar cerrada con la skill `tfg-evidence-check`.

## Procedimiento

1. Lee la seccion objetivo y sus vecinas inmediatas en `docs/`.
2. Revisa `docs/referencias.bib` para limitar las claves disponibles.
3. Usa solo afirmaciones marcadas como `verificada`.
4. Mantiene la estructura, el tono y la terminologia ya usada en el TFG,
   pero prioriza una redaccion narrativa continua frente a una estructura muy
   fragmentada.
5. Si detectas demasiados subapartados, encabezados con muy poco contenido o
   repeticiones entre secciones, propone fusionar y reescribir en parrafos
   desarrollados antes que crear mas divisiones.
6. Si detectas duplicidad con texto ya escrito, propone una insercion
   incremental en vez de rehacer todo el apartado.
7. Si falta evidencia para una frase importante, detente y devuelve el
   hueco en lugar de rellenarlo.

## Restricciones

- No inventes citas ni claves BibTeX.
- No conviertas hipotesis en hechos.
- No cambies la organizacion del documento sin una razon concreta.
- No uses una fuente web nueva si antes no ha quedado aprobada o citada.
- No abras subsecciones para contenido demasiado corto o puramente enumerativo.
- No produzcas texto con apariencia de esquema expandido si puede redactarse como argumento continuo.
- No repitas en el capitulo 7 material ya explicado en metodologia o arquitectura sin aportar una funcion nueva.
- **No incluyas codigo fuente** (Java, Python, SQL ni cualquier otro lenguaje) ni en el cuerpo del documento ni en los anexos, sin excepcion.
- **Idioma uniforme**: castellano en todo el texto. No mezclar etiquetas en ingles con sus equivalentes en espanol. Los nombres propios de herramientas (Optuna, XGBoost, TensorFlow, MessagePack, LightGBM, BCrypt) no se traducen.
- **Tablas de hiperparametros y configuracion detallada**: solo en anexos, nunca en el cuerpo. En el texto, referenciar el anexo con \ref{}. Si los valores no tienen respaldo bibliografico, indicar que son rangos experimentales del proyecto.

## Resultado minimo

- Bloque LaTeX listo para insertar o parchear.
- Nota breve con la trazabilidad usada.
- Lista de frases descartadas por falta de respaldo, si existen.

Consulta [latex writing rules](./references/latex-writing-rules.md) antes de
redactar.