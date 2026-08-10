import json
import struct
import sys
import uuid
from typing import Any

import msgpack

PROTOCOL_VERSION = "1.0"


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


def read_request_payload() -> dict[str, Any]:
    raw = sys.stdin.buffer.read()
    if not raw:
        raise ValueError("No input data provided")

    # Framing v1: 4-byte big-endian length + msgpack envelope.
    if len(raw) >= 4:
        frame_len = struct.unpack(">I", raw[:4])[0]
        if frame_len > 0 and len(raw) >= 4 + frame_len:
            body = raw[4:4 + frame_len]
            envelope = msgpack.unpackb(body, raw=False)
            envelope = _to_text_keys(envelope)
            payload = envelope.get("payload")
            if isinstance(payload, dict):
                return _to_text_keys(payload)

    # Legacy fallback: JSON plano.
    try:
        legacy_msgpack = msgpack.unpackb(raw, raw=False)
        legacy_msgpack = _to_text_keys(legacy_msgpack)
        if isinstance(legacy_msgpack, dict):
            return legacy_msgpack
        if isinstance(legacy_msgpack, list):
            return {"velas": legacy_msgpack}
    except Exception:
        pass

    text = raw.decode("utf-8")
    parsed = json.loads(text)
    if not isinstance(parsed, dict):
        raise ValueError("Payload must be a JSON object")
    return parsed


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
