"""Package distributable outputs without local accounts, samples or build caches."""
from pathlib import Path
from zipfile import ZipFile, ZIP_DEFLATED
import argparse
import hashlib
import shutil
from check_version import ROOT, version

def checksum(path, platform):
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    (path.parent / f"SHA256SUMS-{platform}.txt").write_text(f"{digest}  {path.name}\n", encoding="ascii")
    print(f"{path.name}: {path.stat().st_size} bytes; sha256 {digest}")

def android(variant):
    folder = ROOT / "androidApp/build/outputs/apk" / variant
    candidates = list(folder.glob("*.apk"))
    if len(candidates) != 1 or "unsigned" in candidates[0].name:
        raise SystemExit("Expected exactly one signed Android APK")
    suffix = "-debug" if variant == "debug" else ""
    target = ROOT / "outputs" / f"QihuangSchedule-Android-{version()}{suffix}.apk"
    target.parent.mkdir(exist_ok=True)
    shutil.copy2(candidates[0], target)
    with ZipFile(target) as archive:
        if archive.testzip() is not None:
            raise SystemExit("APK CRC check failed")
        required = "META-INF/xposed/"
        assert archive.read(required + "java_init.list").decode().strip() == "com.yuzakikokuban.ahucm.integration.ScheduleHook"
        assert archive.read(required + "scope.list").decode().strip() == "com.miui.voiceassist"
        props = dict(line.split("=", 1) for line in archive.read(required + "module.prop").decode().splitlines() if "=" in line)
        assert props["targetApiVersion"] == props["minApiVersion"] == "102"
        assert "assets/xposed_init" not in archive.namelist()
        assert "assets/third-party-notices.txt" in archive.namelist()
        assert not any(name.lower().endswith((".xls", ".xlsx", ".p12", ".jks", ".keystore")) for name in archive.namelist())
    checksum(target, "Android")

def windows():
    source = ROOT / "composeApp/build/compose/binaries/main/app/QihuangSchedule"
    if not (source / "QihuangSchedule.exe").is_file():
        raise SystemExit("Windows application image is missing")
    target = ROOT / "outputs" / f"QihuangSchedule-Windows-{version()}.zip"
    target.parent.mkdir(exist_ok=True)
    with ZipFile(target, "w", ZIP_DEFLATED, compresslevel=6) as archive:
        for path in sorted(source.rglob("*")):
            if path.is_file():
                archive.write(path, Path("QihuangSchedule") / path.relative_to(source))
        archive.write(ROOT / "LICENSE", "QihuangSchedule/LICENSE")
        archive.write(ROOT / "THIRD_PARTY_NOTICES.md", "QihuangSchedule/THIRD_PARTY_NOTICES.md")
        archive.write(ROOT / "androidApp/src/main/assets/third-party-notices.txt", "QihuangSchedule/third-party-notices.txt")
    with ZipFile(target) as archive:
        assert archive.testzip() is None
        assert any("runtime/legal/" in name for name in archive.namelist())
        assert not any(name.lower().endswith((".xls", ".xlsx", ".p12", ".jks", ".keystore")) for name in archive.namelist())
    checksum(target, "Windows")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("platform", choices=("android", "windows"))
    parser.add_argument("--variant", choices=("debug", "release"), default="debug")
    args = parser.parse_args()
    android(args.variant) if args.platform == "android" else windows()
