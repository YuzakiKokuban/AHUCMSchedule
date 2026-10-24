"""Validate the committed version source (version.properties).

The release tag is authoritative for a release and is resolved separately by
release_version.py; this script only checks that the version snapshot committed
to the development branch is well formed and matches the versionCode encoding.
"""
from pathlib import Path
import argparse

from release_version import ROOT, VersionError, read_version_file


def version() -> str:
    """Returns the validated versionName from version.properties."""
    try:
        return read_version_file().version
    except VersionError as error:
        raise SystemExit(str(error)) from error


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.parse_args()
    print(version())
