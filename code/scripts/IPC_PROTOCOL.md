# Protocolo IPC Java ↔ Python

CryptoApp usa **dos canales** de comunicación entre el backend Java y los
engines Python. No son el mismo protocolo — este documento fija el contrato de
cada uno para evitar la confusión de que "todo es MessagePack binario".

## Canal 1 — Request/Response (batch): MessagePack framed

Usado por: `fetch` (solo respuesta), `indicators`, `backtest`, `train`,
`optimize`, y el envío de configuración inicial a los engines RT.

Formato de cada mensaje, en ambos sentidos:

```
[4 bytes big-endian: longitud del cuerpo] [cuerpo: envelope MessagePack]
```

El envelope es un objeto con:

| Campo | Tipo | Descripción |
| --- | --- | --- |
| `protocol_version` | str | `"1.0"` |
| `message_type` | str | p.ej. `BACKTEST_REQUEST`, `TRAIN_RESPONSE`, `ERROR` |
| `correlation_id` | str | UUID por mensaje |
| `payload` | objeto | datos del mensaje |

- **Java escribe** con `IpcMessagePackCodec.writeEnvelope(...)`.
- **Python lee** con `ipc_protocol.read_request_payload()` y **responde** con
  `ipc_protocol.write_response(...)` / `write_error(...)`.

**Contrato estricto (v1):** `read_request_payload()` acepta **únicamente** frames
MessagePack válidos. Rechaza con `ValueError` cualquier otra forma (JSON plano,
msgpack sin frame, frame truncado, longitud inválida, envelope sin `payload`,
versión no soportada). No hay rutas legacy.

## Canal 2 — Señales en tiempo real (streaming): NDJSON por líneas

Usado por: `engine_rt.py` y `engine_ai_rt.py` para emitir señales a Java.

Este canal **no** es MessagePack: es texto delimitado por líneas (NDJSON) sobre
**stdout**, una línea por mensaje:

```
PREFIJO\t<JSON>
```

| Prefijo | Significado | Definición |
| --- | --- | --- |
| `SIGNAL\t` | Señal de trading (BUY/SELL) | `shared_utils.build_signal_line()` |

- **Python emite** con `build_signal_line(signal)` (fuente única del formato).
- **Java parsea** línea a línea con `SignalProtocolParser.parseLineaLog(...)`.
- Los **logs** de Python van a **stderr**, nunca a stdout, para no contaminar
  este canal.

### ¿Por qué NDJSON y no MessagePack aquí?

Decisión deliberada: el canal RT es un flujo continuo orientado a líneas donde
el salto de línea ya actúa como framing natural, es trivial de depurar (legible)
y de parsear incrementalmente. Mantenerlo en NDJSON es más simple que framear
MessagePack sobre un stream de larga duración. La configuración inicial del
engine RT sí llega por el Canal 1 (framed MessagePack).
