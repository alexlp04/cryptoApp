import msgpack
import struct
import subprocess
import sys
from pathlib import Path


def parse_frame(raw: bytes) -> dict:
    if len(raw) < 4:
        raise AssertionError("No frame header in output")
    length = struct.unpack(">I", raw[:4])[0]
    body = raw[4 : 4 + length]
    return msgpack.unpackb(body, raw=False)


def test_engine_fetch_returns_clean_framed_response():
    repo_root = Path(__file__).resolve().parents[3]
    script_path = repo_root / "code" / "scripts" / "engine_fetch.py"
    bootstrap = f'''
import runpy
import sys
import requests
from pathlib import Path

class _FakeResponse:
    def raise_for_status(self):
        return None

    def json(self):
        return []

requests.get = lambda *args, **kwargs: _FakeResponse()
sys.path.insert(0, str(Path({str(script_path)!r}).parent))
sys.argv = [{str(script_path)!r}, "BTCUSDT", "1h", "9999999999999"]
runpy.run_path({str(script_path)!r}, run_name="__main__")
'''

    process = subprocess.run(
        [sys.executable, "-c", bootstrap],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
        timeout=20,
    )

    assert process.returncode == 0, process.stderr.decode("utf-8", errors="ignore")

    envelope = parse_frame(process.stdout)
    assert envelope["protocol_version"] == "1.0"
    assert envelope["message_type"] == "FETCH_RESPONSE"

    payload = envelope.get("payload", {})
    assert payload.get("total") == 0