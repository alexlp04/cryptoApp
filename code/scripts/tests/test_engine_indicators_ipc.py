import msgpack
import struct
import subprocess
import sys
from pathlib import Path


def build_frame(message_type: str, payload: dict) -> bytes:
    envelope = {
        "protocol_version": "1.0",
        "message_type": message_type,
        "correlation_id": "test-correlation-id",
        "payload": payload,
    }
    body = msgpack.packb(envelope, use_bin_type=True)
    return struct.pack(">I", len(body)) + body


def parse_frame(raw: bytes) -> dict:
    if len(raw) < 4:
        raise AssertionError("No frame header in output")
    length = struct.unpack(">I", raw[:4])[0]
    body = raw[4 : 4 + length]
    return msgpack.unpackb(body, raw=False)


def test_engine_indicators_returns_framed_response_for_empty_payload():
    repo_root = Path(__file__).resolve().parents[3]
    script_path = repo_root / "code" / "scripts" / "engine_indicators.py"

    request = build_frame("INDICATORS_REQUEST", {"velas": []})

    process = subprocess.run(
        [sys.executable, str(script_path)],
        input=request,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
        timeout=20,
    )

    assert process.returncode == 0, process.stderr.decode("utf-8", errors="ignore")

    envelope = parse_frame(process.stdout)
    assert envelope["protocol_version"] == "1.0"
    assert envelope["message_type"] == "INDICATORS_RESPONSE"
    assert "payload" in envelope
    assert envelope["payload"].get("indicadores") == []
