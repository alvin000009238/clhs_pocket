#!/usr/bin/env python3
"""Render the real Glance preview on Android and update previewImage."""

from __future__ import annotations

import os
from pathlib import Path
import struct
import subprocess
import time


ANDROID_DIR = Path(__file__).resolve().parents[1]
OUTPUT = (
    ANDROID_DIR
    / "app"
    / "src"
    / "main"
    / "res"
    / "drawable-nodpi"
    / "schedule_widget_preview.png"
)
DEVICE_OUTPUT = (
    "/sdcard/Android/data/com.clhs.score.demo.debug/files/schedule_widget_preview.png"
)
PACKAGE = "com.clhs.score.demo.debug"
CAPTURE_ACTIVITY = (
    f"{PACKAGE}/com.clhs.score.widget.ScheduleWidgetPreviewCaptureActivity"
)


def sdk_dir() -> Path:
    configured = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if configured:
        return Path(configured)

    for line in (ANDROID_DIR / "local.properties").read_text(encoding="utf-8").splitlines():
        if line.startswith("sdk.dir="):
            value = line.removeprefix("sdk.dir=").replace(r"\:", ":").replace(r"\\", "\\")
            return Path(value)
    raise RuntimeError("Android SDK not found; set ANDROID_SDK_ROOT or sdk.dir")


def main() -> None:
    adb = sdk_dir() / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
    gradle = ANDROID_DIR / ("gradlew.bat" if os.name == "nt" else "gradlew")
    serial = os.environ.get("ANDROID_SERIAL")
    adb_command = [str(adb), *(["-s", serial] if serial else [])]

    if not serial:
        devices = subprocess.run(
            [str(adb), "devices"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.splitlines()
        connected = [line.split("\t", 1)[0] for line in devices if line.endswith("\tdevice")]
        if len(connected) != 1:
            raise RuntimeError(
                f"Expected exactly one Android device, found {len(connected)}; "
                "set ANDROID_SERIAL when using multiple devices"
            )

    subprocess.run(
        [str(gradle), ":app:assembleDemoDebug"],
        cwd=ANDROID_DIR,
        check=True,
    )
    apk = ANDROID_DIR / "app" / "build" / "outputs" / "apk" / "demo" / "debug" / "app-demo-debug.apk"
    subprocess.run([*adb_command, "install", "-r", str(apk)], check=True)
    subprocess.run([*adb_command, "shell", "rm", "-f", DEVICE_OUTPUT], check=True)
    subprocess.run(
        [
            *adb_command,
            "shell",
            "am",
            "start",
            "-W",
            "-n",
            CAPTURE_ACTIVITY,
        ],
        check=True,
    )
    for _ in range(100):
        result = subprocess.run(
            [*adb_command, "shell", "test", "-f", DEVICE_OUTPUT],
            check=False,
        )
        if result.returncode == 0:
            break
        time.sleep(0.1)
    else:
        raise RuntimeError("Timed out waiting for the Glance preview capture")

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([*adb_command, "pull", DEVICE_OUTPUT, str(OUTPUT)], check=True)

    data = OUTPUT.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise RuntimeError(f"Generated file is not a PNG: {OUTPUT}")
    width, height = struct.unpack(">II", data[16:24])
    if (width, height) != (552, 406):
        raise RuntimeError(f"Expected 552x406 preview, got {width}x{height}")
    print(f"Updated {OUTPUT} from the actual Glance RemoteViews ({width}x{height})")


if __name__ == "__main__":
    main()
