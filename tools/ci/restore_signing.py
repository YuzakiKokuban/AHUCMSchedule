"""Restore a signing key only inside an ephemeral release runner."""
from pathlib import Path
import base64
import os

encoded = os.environ.get("AHUCM_SIGNING_STORE_B64", "")
if not encoded or not os.environ.get("AHUCM_KEYSTORE_PASSWORD") or not os.environ.get("AHUCM_KEYSTORE_ALIAS"):
    raise SystemExit("Release signing secrets are not configured")
path = Path(os.environ["RUNNER_TEMP"]) / "ahucm-release.p12"
os.umask(0o077)
path.write_bytes(base64.b64decode(encoded, validate=True))
if path.stat().st_size < 100:
    raise SystemExit("Invalid signing keystore")
with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as stream:
    stream.write("AHUCM_KEYSTORE_PATH=" + str(path) + "\n")
print("Release keystore restored into the temporary runner directory")
