import msgpack
import struct
import subprocess
import sys
from pathlib import Path
import pytest


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


def run_engine(script_name: str, frame: bytes, timeout: int = 30):
    repo_root = Path(__file__).resolve().parents[3]
    script_path = repo_root / "code" / "scripts" / script_name
    if not script_path.exists():
        pytest.skip(f"Optional engine not present in repository: {script_name}")
    return subprocess.run(
        [sys.executable, str(script_path)],
        input=frame,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
        timeout=timeout,
    )


def assert_error_envelope(process):
    assert process.stdout, process.stderr.decode("utf-8", errors="ignore")
    envelope = parse_frame(process.stdout)
    assert envelope["protocol_version"] == "1.0"
    assert envelope["message_type"] == "ERROR"
    payload = envelope.get("payload", {})
    assert payload.get("status") == "error"


def test_engine_train_returns_error_envelope_for_empty_dataset():
    frame = build_frame(
        "TRAIN_REQUEST",
        {
            "model_type": "random_forest",
            "symbol": "BTCUSDT",
            "timeframe": "1h",
            "dataset": [],
        },
    )
    process = run_engine("engine_train.py", frame, timeout=60)
    assert process.returncode != 0
    assert_error_envelope(process)


def test_engine_backtest_returns_error_envelope_for_invalid_strategy_path():
    frame = build_frame(
        "BACKTEST_REQUEST",
        {
            "strategy_path": "/tmp/does-not-exist.py",
            "strategy_name": "MissingStrategy",
            "timeframe": "1h",
            "velas": {},
            "capital": 1000,
            "risk_per_trade": 0.02,
            "escribir_trades": False,
        },
    )
    process = run_engine("engine_backtest.py", frame, timeout=40)
    assert process.returncode != 0
    assert_error_envelope(process)


def test_engine_backtest_returns_success_envelope_for_valid_empty_request():
    repo_root = Path(__file__).resolve().parents[3]
    strategy_path = repo_root / "code" / "strategies" / "ToggleStrategy.py"

    frame = build_frame(
        "BACKTEST_REQUEST",
        {
            "strategy_path": str(strategy_path),
            "strategy_name": "ToggleStrategy",
            "timeframe": "1h",
            "velas": {},
            "capital": 1000,
            "risk_per_trade": 0.02,
            "escribir_trades": False,
        },
    )

    process = run_engine("engine_backtest.py", frame, timeout=40)
    assert process.returncode == 0, process.stderr.decode("utf-8", errors="ignore")

    envelope = parse_frame(process.stdout)
    assert envelope["protocol_version"] == "1.0"
    assert envelope["message_type"] == "BACKTEST_RESPONSE"
    payload = envelope.get("payload", {})
    assert payload.get("status") == "success"
    assert payload.get("total_trades") == 0


def test_engine_predict_returns_error_envelope_for_empty_dataset():
    frame = build_frame(
        "PREDICT_REQUEST",
        {
            "model_type": "random_forest",
            "symbol": "BTCUSDT",
            "timeframe": "1h",
            "dataset": [],
        },
    )
    process = run_engine("engine_predict.py", frame, timeout=30)
    assert process.returncode != 0
    assert_error_envelope(process)
