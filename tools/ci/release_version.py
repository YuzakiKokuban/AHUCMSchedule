"""Resolve version facts from a release tag and sync them into version.properties.

The release tag is the single source of truth for a release. ``--tag v0.7.0-rc.1``
prints the normalized versionName, the Android versionCode and the GitHub
prerelease flag, so the workflow, the Gradle build and the release draft cannot
disagree. ``--write`` stores the same facts in version.properties; the release
workflow commits that file back to main after creating the draft release.

This follows the version management used by meta-hybrid_mount: semver-like tags
only, no build metadata, and a prerelease shape restricted to
alpha/beta/rc numbers so every tag maps to a unique, monotonically increasing
versionCode. A candidate always ranks below the finished release it leads to,
because a device is only offered a version with a higher code.
"""
from dataclasses import dataclass
from pathlib import Path
import argparse
import re

ROOT = Path(__file__).resolve().parents[2]
VERSION_FILE = ROOT / "version.properties"

# The low-order digits reserved for ordering inside a versionCode.
VERSION_STRIDE = 1_000
# The slot a finished release occupies: the top of its version's window, leaving
# every lower slot to the candidates that lead to it.
FINAL_SLOT = VERSION_STRIDE - 1
# The pre-release stages a versionCode can order, earliest first.
CANDIDATE_STAGES = ("alpha", "beta", "rc")
# How many numbered candidates each stage may have.
CANDIDATES_PER_STAGE = 100
# Each component has to fit the base layout without colliding with the next one.
MAX_COMPONENT = 999
# Android versionCode is a signed 32-bit int; stay below the Play limit.
MAX_VERSION_CODE = 2_100_000_000

_VERSION = re.compile(
    r"^(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})"
    r"(?:-(alpha|beta|rc)\.([1-9][0-9]?))?$"
)
_PLAIN = re.compile(r"^(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})$")


class VersionError(Exception):
    """A tag or versionName that cannot be turned into release version facts."""


@dataclass(frozen=True)
class VersionFacts:
    """The version facts a tag or versionName implies."""

    version: str
    version_code: int
    prerelease: bool


def _unsupported_prerelease(raw: str) -> VersionError:
    base = raw.split("-", 1)[0]
    return VersionError(
        f"pre-release '{raw}' cannot be ordered as a versionCode; "
        f"tag it as '{base}-<stage>.<number>' with a stage from "
        f"[{', '.join(CANDIDATE_STAGES)}] and a number from 1 to "
        f"{CANDIDATES_PER_STAGE - 1}, for example '{base}-rc.1'"
    )


def parse_version(raw: str, source: str = "release tag") -> VersionFacts:
    """Parses ``MAJOR.MINOR.PATCH[-alpha.N|beta.N|rc.N]`` into release facts."""
    trimmed = raw.strip()
    if not trimmed:
        raise VersionError(f"{source} is empty")
    if "+" in trimmed:
        without_build = trimmed.split("+", 1)[0]
        raise VersionError(
            f"{source} '{raw}' carries build metadata; tag it as '{without_build}' instead"
        )
    match = _VERSION.fullmatch(trimmed)
    if match is None:
        base = trimmed.split("-", 1)[0]
        if "-" in trimmed and _PLAIN.fullmatch(base):
            raise _unsupported_prerelease(trimmed)
        raise VersionError(
            f"{source} '{raw}' is not a release version; expected "
            "MAJOR.MINOR.PATCH[-alpha.N|beta.N|rc.N] without leading zeros"
        )

    major, minor, patch = (int(part) for part in match.group(1, 2, 3))
    for name, value in (("major", major), ("minor", minor), ("patch", patch)):
        if value > MAX_COMPONENT:
            raise VersionError(
                f"{source} '{raw}' has a {name} above {MAX_COMPONENT}; "
                "the versionCode layout cannot order it"
            )

    base = major * 100_000 + minor * 1_000 + patch
    stage = match.group(4)
    if stage is None:
        slot = FINAL_SLOT
    else:
        slot = (CANDIDATE_STAGES.index(stage) + 1) * CANDIDATES_PER_STAGE + int(match.group(5))
    version_code = base * VERSION_STRIDE + slot
    if version_code > MAX_VERSION_CODE:
        raise VersionError(
            f"{source} '{raw}' encodes as versionCode {version_code}, "
            f"above the supported Android limit of {MAX_VERSION_CODE}"
        )

    return VersionFacts(trimmed, version_code, stage is not None)


def parse_tag(tag: str) -> VersionFacts:
    """Parses a release tag with an optional leading ``v``."""
    trimmed = tag.strip()
    return parse_version(trimmed[1:] if trimmed.startswith("v") else trimmed, f"release tag '{trimmed}'")


def parse_version_name(name: str) -> VersionFacts:
    """Parses versionName from version.properties (no leading ``v``)."""
    trimmed = name.strip()
    if trimmed.startswith("v"):
        raise VersionError(f"versionName '{name}' must not carry the release tag's leading v")
    return parse_version(trimmed, "versionName")


def _version_values(path: Path) -> dict[str, str]:
    values: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


def read_version_file(path: Path = VERSION_FILE) -> VersionFacts:
    """Reads and validates the committed version.properties facts."""
    values = _version_values(path)
    missing = [key for key in ("versionName", "versionCode") if key not in values]
    if missing:
        raise VersionError(f"version.properties is missing {', '.join(missing)}")
    facts = parse_version_name(values["versionName"])
    try:
        stored_code = int(values["versionCode"])
    except ValueError as error:
        raise VersionError("version.properties versionCode is not an integer") from error
    if stored_code != facts.version_code:
        raise VersionError(
            f"version.properties versionCode must be {facts.version_code} for "
            f"versionName {facts.version}; found {stored_code}"
        )
    return facts


def write_version_file(facts: VersionFacts, path: Path = VERSION_FILE) -> None:
    """Writes the tag-derived facts into version.properties."""
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        stream.write(f"versionName={facts.version}\nversionCode={facts.version_code}\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--tag", required=True, help="release tag, with or without its leading v")
    parser.add_argument(
        "--write",
        action="store_true",
        help="write the tag version into version.properties in addition to printing it",
    )
    args = parser.parse_args()
    try:
        facts = parse_tag(args.tag)
    except VersionError as error:
        raise SystemExit(str(error)) from error
    if args.write:
        write_version_file(facts)
    print(f"version={facts.version}")
    print(f"version_code={facts.version_code}")
    print(f"prerelease={'true' if facts.prerelease else 'false'}")


if __name__ == "__main__":
    main()
