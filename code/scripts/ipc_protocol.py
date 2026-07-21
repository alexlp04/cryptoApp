import msgpack
import struct
import sys
import uuid
from typing import Any, Dict

PROTOCOL_VERSION = "1.0"
HEADER_BYTES = 4


def _to_text_keys(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            (k.decode("utf-8") if isinstance(k, (bytes, bytearray)) else k): _to_text_keys(v)
            for k, v in value.items()
        }
    if isinstance(value, list):
        return [_to_text_keys(v) for v in value]
    if isinstance(value, (bytes, bytearray)):
        try:
            return value.decode("utf-8")
        except Exception:
            return value
    return value


def read_request_payload() -> Dict[str, Any]:
    """Lee el request IPC de stdin. Contrato ÚNICO y estricto (v1):

    ``[4 bytes big-endian: longitud] [cuerpo: envelope MessagePack]``

    El envelope es un objeto con ``protocol_version``, ``message_type``,
    ``correlation_id`` y ``payload`` (objeto). Devuelve el ``payload``.
    Cualquier otra forma (JSON plano, msgpack sin frame, frame truncado) se
    rechaza con ``ValueError``: no hay rutas legacy.
    """
    raw = sys.stdin.buffer.read()
    if not raw:
        raise ValueError("No input data provided")
    if len(raw) < HEADER_BYTES:
        raise ValueError(
            f"IPC frame inválido: faltan los {HEADER_BYTES} bytes de longitud "
            f"(recibidos {len(raw)})"
        )

    frame_len = struct.unpack(">I", raw[:HEADER_BYTES])[0]
    if frame_len <= 0:
        raise ValueError(f"IPC frame inválido: longitud declarada {frame_len}")
    if len(raw) < HEADER_BYTES + frame_len:
        raise ValueError(
            f"IPC frame truncado: se esperaban {frame_len} bytes de cuerpo, "
            f"se recibieron {len(raw) - HEADER_BYTES}"
        )

    body = raw[HEADER_BYTES:HEADER_BYTES + frame_len]
    try:
        envelope = msgpack.unpackb(body, raw=False)
    except Exception as exc:
        raise ValueError(f"IPC frame no es MessagePack válido: {exc}") from exc

    envelope = _to_text_keys(envelope)
    if not isinstance(envelope, dict):
        raise ValueError("IPC envelope debe ser un objeto")

    version = envelope.get("protocol_version")
    if version is not None and version != PROTOCOL_VERSION:
        raise ValueError(
            f"Versión de protocolo IPC no soportada: {version!r} "
            f"(esperada {PROTOCOL_VERSION!r})"
        )

    payload = envelope.get("payload")
    if not isinstance(payload, dict):
        raise ValueError("IPC envelope sin 'payload' de tipo objeto")
    return _to_text_keys(payload)


def write_response(message_type: str, payload: Any) -> None:
    envelope = {
        "protocol_version": PROTOCOL_VERSION,
        "message_type": message_type,
        "correlation_id": str(uuid.uuid4()),
        "payload": payload,
    }
    body = msgpack.packb(envelope, use_bin_type=True)
    sys.stdout.buffer.write(struct.pack(">I", len(body)))
    sys.stdout.buffer.write(body)
    sys.stdout.buffer.flush()


def write_error(message_type: str, message: str) -> None:
    write_response(message_type, {"status": "error", "message": message})
