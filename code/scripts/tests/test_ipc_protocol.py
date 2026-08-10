"""test_ipc_protocol.py — Tests unitarios del protocolo IPC MessagePack con framing."""
from __future__ import annotations

import io
import struct
import sys

import msgpack
import pytest
from ipc_protocol import (
    _to_text_keys,
    read_request_payload,
    write_error,
    write_response,
)

# ─── Helpers ─────────────────────────────────────────────────────────────────

def _build_framed(payload: dict, message_type: str = "TEST_REQUEST") -> bytes:
    """Construye un mensaje IPC con framing 4-byte big-endian."""
    envelope = {
        "protocol_version": "1.0",
        "message_type": message_type,
        "correlation_id": "test-uuid-123",
        "payload": payload,
    }
    body = msgpack.packb(envelope, use_bin_type=True)
    return struct.pack(">I", len(body)) + body


def _parse_stdout(buf: io.BytesIO) -> dict:
    """Decodifica la respuesta escrita en el buffer de stdout."""
    buf.seek(0)
    frame_len = struct.unpack(">I", buf.read(4))[0]
    return msgpack.unpackb(buf.read(frame_len), raw=False)


def _fake_stdout(buf: io.BytesIO):
    return type("FakeStdout", (), {"buffer": buf})()


def _fake_stdin(data: bytes):
    return type("FakeStdin", (), {"buffer": io.BytesIO(data)})()


# ─── Tests: _to_text_keys ────────────────────────────────────────────────────

class TestToTextKeys:

    def test_should_convert_bytes_keys_to_strings(self):
        # Given
        raw = {b"symbol": b"BTCUSDT"}
        # When
        result = _to_text_keys(raw)
        # Then
        assert result == {"symbol": "BTCUSDT"}

    def test_should_handle_nested_dicts_recursively(self):
        # Given
        raw = {b"outer": {b"inner": b"value"}}
        # When
        result = _to_text_keys(raw)
        # Then
        assert result == {"outer": {"inner": "value"}}

    def test_should_convert_bytes_values_in_lists(self):
        # Given
        raw = {b"items": [b"a", b"b"]}
        # When
        result = _to_text_keys(raw)
        # Then
        assert result == {"items": ["a", "b"]}

    def test_should_pass_through_str_keys_unchanged(self):
        # Given
        raw = {"key": "value"}
        # When
        result = _to_text_keys(raw)
        # Then
        assert result == {"key": "value"}

    def test_should_return_empty_dict_for_empty_input(self):
        assert _to_text_keys({}) == {}

    def test_should_handle_mixed_key_types(self):
        # Given
        raw = {b"bytes_key": "str_val", "str_key": b"bytes_val"}
        # When
        result = _to_text_keys(raw)
        # Then
        assert "bytes_key" in result
        assert "str_key" in result

    def test_should_pass_through_numeric_values(self):
        # Given
        raw = {b"count": 42, b"price": 3.14}
        # When
        result = _to_text_keys(raw)
        # Then
        assert result == {"count": 42, "price": 3.14}


# ─── Tests: read_request_payload ─────────────────────────────────────────────

class TestReadRequestPayload:

    def test_should_parse_framed_msgpack_envelope(self, monkeypatch):
        # Given
        payload = {"symbol": "BTCUSDT", "timeframe": "1h"}
        monkeypatch.setattr(sys, "stdin", _fake_stdin(_build_framed(payload)))
        # When
        result = read_request_payload()
        # Then
        assert result["symbol"] == "BTCUSDT"
        assert result["timeframe"] == "1h"

    def test_should_return_dict_with_string_keys_from_framed(self, monkeypatch):
        # Given
        payload = {"model_type": "xgboost", "n_trials": 50}
        monkeypatch.setattr(sys, "stdin", _fake_stdin(_build_framed(payload)))
        # When
        result = read_request_payload()
        # Then
        assert all(isinstance(k, str) for k in result)

    def test_should_fallback_to_json_when_no_framing(self, monkeypatch):
        # Given
        import json
        payload = {"symbol": "ETHUSDT"}
        monkeypatch.setattr(sys, "stdin", _fake_stdin(json.dumps(payload).encode()))
        # When
        result = read_request_payload()
        # Then
        assert result["symbol"] == "ETHUSDT"

    def test_should_fallback_to_legacy_msgpack_without_framing(self, monkeypatch):
        # Given
        payload = {"strategy_name": "StressTestStrategy"}
        raw = msgpack.packb(payload, use_bin_type=True)
        monkeypatch.setattr(sys, "stdin", _fake_stdin(raw))
        # When
        result = read_request_payload()
        # Then
        assert result["strategy_name"] == "StressTestStrategy"

    def test_should_wrap_legacy_list_in_velas_key(self, monkeypatch):
        # Given
        candles = [{"close": "100"}, {"close": "101"}]
        raw = msgpack.packb(candles, use_bin_type=True)
        monkeypatch.setattr(sys, "stdin", _fake_stdin(raw))
        # When
        result = read_request_payload()
        # Then
        assert "velas" in result
        assert len(result["velas"]) == 2

    def test_should_raise_value_error_when_stdin_is_empty(self, monkeypatch):
        # Given
        monkeypatch.setattr(sys, "stdin", _fake_stdin(b""))
        # When / Then
        with pytest.raises(ValueError, match="No input data"):
            read_request_payload()

    def test_should_preserve_nested_payload_structure(self, monkeypatch):
        # Given
        payload = {"velas": [{"close": "40000", "volume": "1.5"}]}
        monkeypatch.setattr(sys, "stdin", _fake_stdin(_build_framed(payload)))
        # When
        result = read_request_payload()
        # Then
        assert isinstance(result["velas"], list)
        assert result["velas"][0]["close"] == "40000"


# ─── Tests: write_response ────────────────────────────────────────────────────

class TestWriteResponse:

    def test_should_write_framed_msgpack_to_stdout(self, monkeypatch):
        # Given
        buf = io.BytesIO()
        monkeypatch.setattr(sys, "stdout", _fake_stdout(buf))
        # When
        write_response("TEST_RESPONSE", {"status": "ok"})
        # Then
        envelope = _parse_stdout(buf)
        assert envelope["message_type"] == "TEST_RESPONSE"
        assert envelope["payload"]["status"] == "ok"

    def test_should_include_correlation_id_in_envelope(self, monkeypatch):
        # Given
        buf = io.BytesIO()
        monkeypatch.setattr(sys, "stdout", _fake_stdout(buf))
        # When
        write_response("RESPONSE", {"x": 1})
        # Then
        envelope = _parse_stdout(buf)
        assert "correlation_id" in envelope
        assert len(envelope["correlation_id"]) > 0

    def test_should_include_protocol_version_1_0(self, monkeypatch):
        # Given
        buf = io.BytesIO()
        monkeypatch.setattr(sys, "stdout", _fake_stdout(buf))
        # When
        write_response("RESPONSE", {})
        # Then
        envelope = _parse_stdout(buf)
        assert envelope["protocol_version"] == "1.0"

    def test_should_write_correct_frame_length_prefix(self, monkeypatch):
        # Given
        buf = io.BytesIO()
        monkeypatch.setattr(sys, "stdout", _fake_stdout(buf))
        # When
        write_response("R", {"data": list(range(100))})
        # Then — frame_len debe coincidir exactamente con el cuerpo
        buf.seek(0)
        frame_len = struct.unpack(">I", buf.read(4))[0]
        body = buf.read()
        assert len(body) == frame_len

    def test_should_generate_unique_correlation_id_per_call(self, monkeypatch):
        # Given
        buf1, buf2 = io.BytesIO(), io.BytesIO()
        monkeypatch.setattr(sys, "stdout", _fake_stdout(buf1))
        write_response("R", {})
        monkeypatch.setattr(sys, "stdout", _fake_stdout(buf2))
        write_response("R", {})
        # Then
        env1 = _parse_stdout(buf1)
        env2 = _parse_stdout(buf2)
        assert env1["correlation_id"] != env2["correlation_id"]

    def test_should_serialise_list_payload_correctly(self, monkeypatch):
        # Given
        buf = io.BytesIO()
        monkeypatch.setattr(sys, "stdout", _fake_stdout(buf))
        # When
        write_response("INDICATORS_RESPONSE", {"indicadores": [{"id": 1, "tipo": "RSI_14"}]})
        # Then
        envelope = _parse_stdout(buf)
        assert envelope["payload"]["indicadores"][0]["tipo"] == "RSI_14"


# ─── Tests: write_error ───────────────────────────────────────────────────────

class TestWriteError:

    def test_should_write_error_status_in_payload(self, monkeypatch):
        # Given
        buf = io.BytesIO()
        monkeypatch.setattr(sys, "stdout", _fake_stdout(buf))
        # When
        write_error("ERROR", "algo falló")
        # Then
        envelope = _parse_stdout(buf)
        assert envelope["payload"]["status"] == "error"

    def test_should_include_error_message_in_payload(self, monkeypatch):
        # Given
        buf = io.BytesIO()
        monkeypatch.setattr(sys, "stdout", _fake_stdout(buf))
        # When
        write_error("ERROR", "timeout en conexión")
        # Then
        envelope = _parse_stdout(buf)
        assert "timeout en conexión" in envelope["payload"]["message"]

    def test_should_set_message_type_from_argument(self, monkeypatch):
        # Given
        buf = io.BytesIO()
        monkeypatch.setattr(sys, "stdout", _fake_stdout(buf))
        # When
        write_error("BACKTEST_ERROR", "fallo")
        # Then
        envelope = _parse_stdout(buf)
        assert envelope["message_type"] == "BACKTEST_ERROR"
