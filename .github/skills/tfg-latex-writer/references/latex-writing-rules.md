# LaTeX Writing Rules

## Restricciones absolutas de contenido

- **No se incluye código fuente** (Java, Python, SQL ni ningún otro lenguaje) ni en el cuerpo del documento ni en los anexos. Los fragmentos técnicos se describen en prosa o se representan como pseudocódigo textual si resulta imprescindible.
- El único código SQL permitido es el del anexo de esquema de base de datos, que ya existe y no se amplía con código adicional.

## Idioma

- Castellano uniforme en todo el documento. No mezclar términos en inglés con sus equivalentes en español dentro del mismo párrafo ni dentro de la misma sección. Elegir uno y mantenerlo: si se usa "etiquetas", no usar "labels"; si se usa "ingeniería de características", no usar "feature engineering". Los nombres propios de bibliotecas, algoritmos y herramientas (Optuna, XGBoost, LightGBM, TensorFlow, MessagePack, BCrypt) son la única excepción y no se traducen.

## Tablas de hiperparámetros y parámetros técnicos detallados

- Las tablas con rangos de búsqueda de hiperparámetros, parámetros de configuración o valores numéricos de ajuste van exclusivamente en un anexo. En el cuerpo del texto se hace referencia al anexo mediante \ref{}. No se duplica la tabla en el cuerpo ni en forma resumida.
- Si los valores de una tabla no están respaldados por una referencia bibliográfica, se indica explícitamente que son rangos obtenidos experimentalmente durante el desarrollo del proyecto.

## Equilibrio entre subapartados y prosa

- Antes de abrir un nuevo \subsection o \subsubsection, verificar que el contenido justifica una unidad de lectura autónoma de al menos dos párrafos desarrollados. Si no los tiene, integrarlo en el apartado anterior.
- El control del desbalance de clases, la descripción de la ingeniería de características y el detalle de cada partición de validación cruzada se redactan como prosa argumental, no como listas de ítems.


## Estilo

- Tercera persona y tono académico.
- Frases sobrias y verificables.
- Prioriza claridad tecnica sobre retorica.
- Prioriza desarrollo narrativo sobre esquemas, listas y subtitulos
	encadenados.
- Cada subseccion debe sostener una unidad de lectura real, no una idea
	minima ni un parrafo aislado.
- Usa transiciones explicitas para que el lector no tenga que reconstruir el
	hilo entre apartados.
- Si una explicacion cabe mejor dentro del parrafo anterior, integrala en vez
	de abrir un nuevo epigrafe.
- Reescribe cualquier pasaje que suene a inventario de elementos o a resumen
	mecanico en forma de texto argumentado.

## Trazabilidad

- Toda afirmacion tecnica debe poder rastrearse a una fila de la tabla de evidencia.
- Si una frase usa bibliografia, la clave debe existir ya en `docs/referencias.bib`.
- Si una frase describe comportamiento implementado, debe existir evidencia en codigo.

## Integracion con el documento

- Revisa primero si el tema ya aparece en el capitulo objetivo.
- Evita repetir justificaciones ya presentes; añade solo el contenido incremental.
- Respeta nombres de seccion, convenciones terminologicas y notacion ya usada.
- Evita capitulos o apartados que repitan lo dicho antes con otra estructura.
- Si el contenido de `docs/7.DesarrolloTrabajo.tex` repite capitulos previos,
	propone fusion o recorte en lugar de ampliar la repeticion.
- Si falta una explicacion de la aplicacion como producto utilizable o faltan
	resultados verificables del modulo predictivo, prioriza esos huecos frente
	a seguir subdividiendo el texto.

## Si falta respaldo

- Devuelve la frase como pendiente y explica que prueba falta.
- No sustituyas la falta de prueba con conocimiento general del modelo.