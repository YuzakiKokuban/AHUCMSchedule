"""Reject moved release tags before publishing their immutable build assets."""
import argparse
import json
import re
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--repo", required=True)
parser.add_argument("--tag", required=True)
parser.add_argument("--commit", required=True)
args = parser.parse_args()
if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", args.tag):
    raise SystemExit("Invalid release tag")

def api(path):
    return json.loads(subprocess.check_output(["gh", "api", path]))

target = api(f"repos/{args.repo}/git/ref/tags/{args.tag}")["object"]
for _ in range(8):
    if target["type"] == "commit":
        break
    if target["type"] != "tag":
        raise SystemExit("Release tag does not identify a commit")
    target = api(f"repos/{args.repo}/git/tags/{target['sha']}")["object"]
if target["type"] != "commit" or target["sha"] != args.commit:
    raise SystemExit("Release tag moved since validation; draft was not created")
print("Release tag still identifies the verified build commit")
