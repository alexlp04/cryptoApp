import json
import msgpack
import struct
import subprocess
import sys
from pathlib import Path


def build_frame(message_type: str, payload: dict) -> bytes:
    envelope = {
        "protocol_version": "1.0",
        "message_type": message_type,
        "correlation_id": "runtime-test-correlation-id",
        "payload": payload,
    }
    body = msgpack.packb(envelope, use_bin_type=True)
    return struct.pack(">I", len(body)) + body


def extract_signal_payload(stdout: bytes) -> dict:
    text = stdout.decode("utf-8", errors="ignore")
    for line in text.splitlines():
        if line.startswith("SIGNAL\t"):
            return json.loads(line.split("\t", 1)[1])
    raise AssertionError(f"No SIGNAL line found in stdout. Output was:\n{text}")


def test_engine_rt_emits_signal_contract_line_from_framed_request():
    repo_root = Path(__file__).resolve().parents[3]
    scripts_dir = repo_root / "code" / "scripts"

    frame = build_frame(
        "RUNTIME_REQUEST",
        {
            "symbols": ["BTCUSDT"],
            "timeframe": "1m",
            "strategy_path": str(repo_root / "code" / "strategies" / "ToggleStrategy.py"),
            "capital": 1000,
            "risk_per_trade": 0.02,
            "is_real": False,
        },
    )

    bootstrap = f'''
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path({str(scripts_dir)!r})))
import engine_rt as runtime

async def _fake_run_all(symbols, timeframe, strategy_path, capital, risk_per_trade, is_real):
    print("SIGNAL\\t" + json.dumps({{
        "symbol": symbols[0],
        "action": "BUY",
        "timeframe": timeframe,
        "price": 42000.0,
        "timestamp": 1704067200000,
        "is_real": is_real
    }}), flush=True)

runtime.run_all = _fake_run_all
runtime.main()
'''

    process = subprocess.run(
        [sys.executable, "-c", bootstrap],
        input=frame,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
        timeout=20,
    )

    assert process.returncode == 0, process.stderr.decode("utf-8", errors="ignore")

    payload = extract_signal_payload(process.stdout)
    assert payload["symbol"] == "BTCUSDT"
    assert payload["action"] == "BUY"
    assert payload["timeframe"] == "1m"
    assert payload["is_real"] is False


def test_engine_ai_rt_emits_signal_contract_line_from_framed_request():
    repo_root = Path(__file__).resolve().parents[3]
    scripts_dir = repo_root / "code" / "scripts"

    frame = build_frame(
        "RUNTIME_REQUEST",
        {
            "symbols": ["BTCUSDT"],
            "timeframe": "1m",
            "strategy_path": str(repo_root / "code" / "strategies" / "ToggleStrategy.py"),
            "model_name": "dummy-model",
            "capital": 1000,
            "risk_per_trade": 0.02,
            "is_real": False,
        },
    )

    bootstrap = f'''
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path({str(scripts_dir)!r})))
import engine_ai_rt as runtime

async def _fake_run_all(symbols, timeframe, strategy_path, model_name, capital, risk_per_trade, is_real):
    print("SIGNAL\\t" + json.dumps({{
        "symbol": symbols[0],
        "action": "SELL",
        "timeframe": timeframe,
        "price": 42000.0,
        "timestamp": 1704067200000,
        "is_real": is_real,
        "source": "AI_" + model_name.upper()
    }}), flush=True)

runtime.run_all = _fake_run_all
runtime.main()
'''

    process = subprocess.run(
        [sys.executable, "-c", bootstrap],
        input=frame,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
        timeout=20,
    )

    assert process.returncode == 0, process.stderr.decode("utf-8", errors="ignore")

    payload = extract_signal_payload(process.stdout)
    assert payload["symbol"] == "BTCUSDT"
    assert payload["action"] == "SELL"
    assert payload["timeframe"] == "1m"
    assert payload["source"] == "AI_DUMMY-MODEL"
