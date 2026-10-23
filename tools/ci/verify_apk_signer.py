"""Verify that a release APK uses the existing public signing identity."""
from pathlib import Path
import os
import re
import subprocess
import sys
from check_version import ROOT

apk = Path(sys.argv[1])
sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
tool = sdk / "build-tools/36.0.0" / ("apksigner.bat" if os.name == "nt" else "apksigner")
command = [str(tool), "verify", "--print-certs", str(apk)]
if os.name == "nt":
    command = ["cmd", "/c", *command]
report = subprocess.check_output(command, text=True, encoding="utf-8", errors="replace")
match = re.search(r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", report)
expected = (ROOT / "release-signing-certificate.sha256").read_text().strip().lower()
if match is None or match[1].lower() != expected:
    raise SystemExit("Release signing certificate does not match the existing application")
print("Release APK uses the expected public signing certificate")
