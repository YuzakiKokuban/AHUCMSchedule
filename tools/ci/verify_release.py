"""Check each downloaded asset against its build-job checksum."""
from pathlib import Path
import hashlib
import sys

root = Path(sys.argv[1])
lines = []
for platform in ("Android", "Windows"):
    entries = (root / f"SHA256SUMS-{platform}.txt").read_text(encoding="ascii").splitlines()
    if len(entries) != 1:
        raise SystemExit("Expected one distributable per platform")
    digest, name = entries[0].split("  ", 1)
    if Path(name).name != name or Path(name).suffix not in (".apk", ".zip"):
        raise SystemExit("Unexpected asset name")
    if hashlib.sha256((root / name).read_bytes()).hexdigest() != digest:
        raise SystemExit("Checksum mismatch: " + name)
    lines.append(entries[0])
(root / "SHA256SUMS.txt").write_text("\n".join(lines) + "\n", encoding="ascii")
print("Both release packages verified")
