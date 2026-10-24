"""Version management self-checks for the release workflow.

Run directly with ``python3 tools/ci/test_release_version.py`` (CI runs this
before a release is built) or through unittest discovery.
"""
from pathlib import Path
import tempfile
import unittest

import check_version
import release_version as rv


class ReleaseTagTest(unittest.TestCase):
    def test_final_release_encodes_version_and_code(self):
        facts = rv.parse_tag("v0.6.1")
        self.assertEqual("0.6.1", facts.version)
        self.assertEqual(6_001_999, facts.version_code)
        self.assertFalse(facts.prerelease)

        without_v = rv.parse_tag("0.7.0")
        self.assertEqual("0.7.0", without_v.version)
        self.assertEqual(7_000_999, without_v.version_code)

    def test_prerelease_facts_stay_inside_their_version_window(self):
        cases = {
            "v0.7.0-alpha.1": (7_000_101, True),
            "v0.7.0-beta.99": (7_000_299, True),
            "v0.7.0-rc.1": (7_000_301, True),
            "v0.7.0": (7_000_999, False),
        }
        for tag, (code, prerelease) in cases.items():
            facts = rv.parse_tag(tag)
            self.assertEqual(code, facts.version_code, tag)
            self.assertEqual(prerelease, facts.prerelease, tag)

    def test_release_outranks_its_own_prereleases(self):
        finished = rv.parse_tag("v6.2.1").version_code
        for candidate in ("v6.2.1-alpha.1", "v6.2.1-beta.2", "v6.2.1-rc.99"):
            code = rv.parse_tag(candidate).version_code
            self.assertLess(code, finished, candidate)
            self.assertGreater(code, rv.parse_tag("v6.2.0").version_code, candidate)

    def test_versions_strictly_increase(self):
        ordered = (
            "v0.6.0",
            "v0.6.1-alpha.1",
            "v0.6.1-rc.1",
            "v0.6.1",
            "v0.6.2-rc.1",
            "v0.7.0",
            "v1.0.0",
            "v6.2.1",
        )
        codes = [rv.parse_tag(tag).version_code for tag in ordered]
        self.assertEqual(codes, sorted(codes))
        self.assertEqual(len(codes), len(set(codes)))

    def test_new_codes_outrank_the_published_scheme(self):
        # Published releases used versionCode 7..11 for 0.4.1..0.6.0.
        self.assertGreater(rv.parse_tag("v0.4.1").version_code, 7)
        self.assertGreater(rv.parse_tag("v0.6.0").version_code, 11)

    def test_prerelease_stages_order_by_stage_then_number(self):
        code = lambda tag: rv.parse_tag(tag).version_code
        for earlier, later in (
            ("v6.2.1-alpha.1", "v6.2.1-alpha.2"),
            ("v6.2.1-alpha.9", "v6.2.1-beta.1"),
            ("v6.2.1-beta.9", "v6.2.1-rc.1"),
            ("v6.2.1-rc.2", "v6.2.1-rc.10"),
        ):
            self.assertLess(code(earlier), code(later), f"{earlier} < {later}")

    def test_unsupported_prerelease_shapes_are_refused_with_a_hint(self):
        for tag in (
            "v6.2.1-rc",
            "v6.2.1-rc.0",
            "v6.2.1-rc.100",
            "v6.2.1-rc.1.2",
            "v6.2.1-preview.1",
            "v6.2.1-RC.1",
        ):
            with self.assertRaises(rv.VersionError) as raised:
                rv.parse_tag(tag)
            message = str(raised.exception)
            self.assertIn("-rc.1", message)
            self.assertIn("stage", message)

    def test_invalid_semver_is_rejected(self):
        for tag in ("v0.6", "nightly", "v0.6.01", "v0.6.1-", "v0.6.1-rc..1", "v1000.0.0"):
            with self.assertRaises(rv.VersionError):
                rv.parse_tag(tag)

    def test_build_metadata_is_refused(self):
        with self.assertRaises(rv.VersionError) as raised:
            rv.parse_tag("v0.6.1+build.5")
        self.assertIn("build metadata", str(raised.exception))

    def test_oversized_version_code_is_refused(self):
        with self.assertRaises(rv.VersionError) as raised:
            rv.parse_tag("v999.999.999")
        self.assertIn("versionCode", str(raised.exception))


class VersionFileTest(unittest.TestCase):
    def test_tag_facts_round_trip_through_version_properties(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "version.properties"
            facts = rv.parse_tag("v0.7.0-rc.2")
            rv.write_version_file(facts, path)
            self.assertEqual(
                "versionName=0.7.0-rc.2\nversionCode=7000302\n",
                path.read_text(encoding="utf-8"),
            )
            self.assertEqual(facts, rv.read_version_file(path))

    def test_committed_version_properties_is_valid(self):
        facts = rv.read_version_file()
        self.assertEqual(facts, rv.parse_version_name(facts.version))
        self.assertEqual(facts.version, check_version.version())

    def test_version_code_must_match_the_encoding(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "version.properties"
            path.write_text("versionName=0.6.1\nversionCode=6001998\n", encoding="utf-8")
            with self.assertRaises(rv.VersionError) as raised:
                rv.read_version_file(path)
            self.assertIn("6001999", str(raised.exception))

    def test_version_name_rejects_tag_prefix(self):
        with self.assertRaises(rv.VersionError):
            rv.parse_version_name("v0.6.1")

    def test_missing_keys_are_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "version.properties"
            path.write_text("versionName=0.6.1\n", encoding="utf-8")
            with self.assertRaises(rv.VersionError) as raised:
                rv.read_version_file(path)
            self.assertIn("versionCode", str(raised.exception))


if __name__ == "__main__":
    unittest.main(verbosity=2)
