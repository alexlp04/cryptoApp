# Evidence Table Template

Usa esta estructura antes de redactar:

| Afirmacion | Estado | Evidencia exacta | Motivo |
| --- | --- | --- | --- |
| El sistema usa Spring Boot 3.3.5 | verificada | `code/backendBotTrading/pom.xml` | La version esta declarada en el descriptor Maven |

## Estados permitidos

- `verificada`: la afirmacion queda sustentada por codigo, `.tex` o cita existente.
- `parcial`: hay indicios, pero falta una prueba suficiente o una parte de la afirmacion.
- `no verificada`: no hay respaldo suficiente o la afirmacion depende de una suposicion.

## Criterios de corte

- Si el parrafo depende de una afirmacion `no verificada`, no se redacta.
- Si el parrafo puede sobrevivir quitando la parte dudosa, se reescribe sin ella.
- Si la afirmacion solo aparece en memoria o en conocimiento general del modelo, se considera `no verificada`.