# Flujo de trabajo con ramas

Este es el modelo estándar del proyecto. La idea de fondo: **nada llega a producción sin haber
convivido antes con el resto de cambios pendientes**.

## Las cuatro capas

```
main ──────────────────────────────────────────────────────────►  tronco estable
  │
  └──► epic/<tema> ─────────────────────────────────────────────►  integración
          │   ▲   ▲   ▲
          │   │   │   └── fix/<nº>-<slug>     una rama por issue
          │   │   └────── feat/<nº>-<slug>
          │   └────────── fix/<nº>-<slug>
          │
          └──► prod ──────────────────────────────────────────►  promoción verificada
```

| Rama | Sale de | Vuelve a | Para qué |
|---|---|---|---|
| `main` | — | — | Tronco. No se commitea directamente. |
| `epic/<tema>` | `main` | `prod` | Agrupa issues correlacionados. Aquí conviven los cambios. |
| `feat/<nº>-<slug>` `fix/<nº>-<slug>` | la épica | la épica | Un issue, una rama. El número es el del issue. |
| `prod` | la épica | — | Solo cuando la épica está entera en verde. |

## El ciclo

1. **Se abre una épica** desde `main` agrupando issues que se tocan entre sí (mismo subsistema,
   mismos ficheros, o que se validan con los mismos tests). El nombre describe el tema, no el
   sprint: `epic/estabilizacion`, `epic/cobertura`, `epic/trading-real`.

2. **Cada issue se resuelve en su propia rama**, sacada de la épica y con su número delante:
   `fix/49-codigo-muerto`. Así el historial dice a qué issue responde cada commit sin abrir GitHub.

3. **Vuelve a la épica con `--no-ff`**, siempre. El merge commit es lo que deja constancia de qué
   entró y cuándo; con fast-forward esa información se pierde.

4. **La épica se verifica entera** (ver más abajo). No basta con que cada rama pasara por separado:
   es justo ahí donde aparecen los conflictos que ninguna rama ve sola.

5. **Cuando la épica está en verde se promociona a `prod`.**

## Por qué la integración va antes que producción

No es burocracia. En la primera aplicación de este modelo, `fix/c4-durable-retry-queue` y
`feat/calidad-y-ci` estaban **ambas en verde por separado**, y al juntarlas el build se rompió:

```
Schema-validation: missing table [senal_fallida_pendiente]
```

C4 había añadido una entidad JPA sin su migración Flyway. Nadie lo vio porque el test que lo
detecta (`SchemaMigrationValidationTest`) venía en la *otra* rama. Sin una capa donde ambas
convivan antes de producción, eso habría llegado a `prod` y habría reventado al arrancar contra
una base real.

Esa es toda la justificación del modelo.

## Verificación antes de promocionar

Las cuatro comprobaciones deben pasar **sobre la épica**, no sobre las ramas sueltas:

```bash
# Python
ruff check code/
mypy code/scripts
pytest code/scripts/tests -q

# Java: encadena Checkstyle, PMD, SpotBugs y la suite JUnit
cd code/backendBotTrading && mvn verify
```

El CI (`.github/workflows/ci.yml`) las ejecuta en cada PR, más la validación del esquema contra
MySQL 8 real. La auditoría de dependencias va aparte, en `security-audit.yml`.

## Reglas que evitan sorpresas

- **Una entidad JPA nueva o modificada exige una migración** `V<n>__descripcion.sql`. Es la lección
  del ejemplo de arriba, y `SchemaMigrationValidationTest` lo comprueba.
- **Al resolver un conflicto, decide con criterio, no por comodidad.** Cuando dos ramas tocan lo
  mismo suele ser una *unión* (ambos cambios hacen falta), no una elección. Si de verdad hay que
  descartar una versión, dilo en el mensaje del merge y por qué.
- **Los merges a la épica llevan el número de issue** en el mensaje: `merge: … (#18)`.
- **Nunca se commitea directo a `main` ni a `prod`.**

## Mensajes de commit

Máximo dos líneas, formato `feat|fix|wip|docs|data|style|merge: <resumen>`.
