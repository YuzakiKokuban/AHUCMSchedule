"""Validate the one version source and an optional release tag."""
from pathlib import Path
import argparse
import re

ROOT = Path(__file__).resolve().parents[2]

def version():
    values = dict(line.split("=", 1) for line in (ROOT / "version.properties").read_text().splitlines() if "=" in line and not line.startswith("#"))
    name = values["versionName"]
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", name) or int(values["versionCode"]) < 1:
        raise SystemExit("Invalid version.properties")
    return name

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag")
    args = parser.parse_args()
    name = version()
    if args.tag and args.tag != "v" + name:
        raise SystemExit("Release tag must match version.properties: v" + name)
    print(name)
