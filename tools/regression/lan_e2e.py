#!/usr/bin/env python3
"""LAN 推流/停止/FLV 抽帧冒烟（模拟器）。"""
import json, os, re, subprocess, sys, time, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ADB = str(ROOT / ".android-sdk/platform-tools/adb")
DEVICE_PORT = int(os.environ.get("LAN_PORT", "8090"))
HOST_PORT = int(os.environ.get("LAN_HOST_PORT", "18098"))
OUT = Path("/tmp/campush-lan-e2e"); OUT.mkdir(exist_ok=True)

def run(*args, **kw):
    return subprocess.run(args, check=True, capture_output=True, text=True, **kw)

def adb(*args):
    return run(ADB, *args)

def ui_dump():
    adb("shell", "uiautomator", "dump", "/sdcard/lan-e2e.xml")
    adb("pull", "/sdcard/lan-e2e.xml", str(OUT / "ui.xml"))
    return (OUT / "ui.xml").read_text(encoding="utf-8")

def bounds(xml, text):
    # 优先 content-desc（Compose 按钮的可点击节点），再退回 text
    for m in re.finditer(r'<node[^>]*content-desc="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if text in m.group(1):
            return tuple(map(int, m.groups()[1:]))
    for m in re.finditer(r'<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if text in m.group(1):
            return tuple(map(int, m.groups()[1:]))
    return None

def tap_bounds(b):
    x, y = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
    adb("shell", "input", "tap", str(x), str(y))

def set_prefs(values):
    adb("shell", "am", "force-stop", "com.campush.app")
    prefs = OUT / "prefs.xml"
    raw = subprocess.run([ADB, "exec-out", "run-as", "com.campush.app", "cat",
                          "/data/data/com.campush.app/shared_prefs/campush_p1_prefs.xml"],
                         capture_output=True, text=True)
    text = raw.stdout if raw.returncode == 0 and raw.stdout.strip() else "<map>\n</map>"
    for key, value in values.items():
        entry = f'name="{key}" value="{value}"'
        if f'name="{key}"' in text:
            text = re.sub(rf'<boolean name="{key}" value="[^"]*" />', f'<boolean {entry} />', text)
        else:
            text = text.replace("</map>", f'    <boolean {entry} />\n</map>')
    prefs.write_text(text, encoding="utf-8")
    adb("push", str(prefs), "/data/local/tmp/campush-prefs.xml")
    adb("shell", "run-as", "com.campush.app", "cp", "/data/local/tmp/campush-prefs.xml",
        "/data/data/com.campush.app/shared_prefs/campush_p1_prefs.xml")

def set_string_pref(key, value):
    adb("shell", "am", "force-stop", "com.campush.app")
    prefs = OUT / "prefs.xml"
    raw = subprocess.run([ADB, "exec-out", "run-as", "com.campush.app", "cat",
                          "/data/data/com.campush.app/shared_prefs/campush_p1_prefs.xml"],
                         capture_output=True, text=True)
    text = raw.stdout if raw.returncode == 0 and raw.stdout.strip() else "<map>\n</map>"
    if f'name="{key}"' in text:
        text = re.sub(rf'<string name="{key}">[^<]*</string>', f'<string name="{key}">{value}</string>', text)
    else:
        text = text.replace("</map>", f'    <string name="{key}">{value}</string>\n</map>')
    prefs.write_text(text, encoding="utf-8")
    adb("push", str(prefs), "/data/local/tmp/campush-prefs.xml")
    adb("shell", "run-as", "com.campush.app", "cp", "/data/local/tmp/campush-prefs.xml",
        "/data/data/com.campush.app/shared_prefs/campush_p1_prefs.xml")

def main():
    adb("install", "-r", str(ROOT / "app/build/outputs/apk/debug/app-debug.apk"))
    adb("shell", "pm", "grant", "com.campush.app", "android.permission.CAMERA")
    adb("shell", "pm", "grant", "com.campush.app", "android.permission.RECORD_AUDIO")
    set_prefs({"settings_watermark_enabled": "true", "settings_record_enabled": "true"})
    set_string_pref("access_scope", "LAN")
    set_string_pref("settings_cloud_api", "")
    adb("shell", "am", "start", "-W", "-n", "com.campush.app/.MainActivity")
    time.sleep(4)

    xml = ui_dump()
    b = bounds(xml, "开始推流")
    if not b:
        print("FAIL: start button not found", file=sys.stderr); return 1
    tap_bounds(b)
    time.sleep(7)
    xml = ui_dump()
    if "局域网推流中" not in xml:
        print("FAIL: LAN streaming not started", file=sys.stderr); return 1
    m = re.search(r'http://10\.0\.2\.\d+:(\d+)/s/([A-Za-z0-9._-]+)', xml)
    if not m:
        print("FAIL: play URL not found", file=sys.stderr); return 1
    port, page = int(m.group(1)), m.group(2)
    adb("forward", f"tcp:{HOST_PORT}", f"tcp:{port}")
    req = urllib.request.Request(
        f"http://127.0.0.1:{HOST_PORT}/api/v1/play-session",
        data=b"{}", headers={"X-Page-Token": page, "Content-Type": "application/json"}, method="POST",
    )
    session = json.loads(urllib.request.urlopen(req, timeout=5).read())
    media = session["mediaToken"]
    flv = OUT / "live.flv"
    with urllib.request.urlopen(f"http://127.0.0.1:{HOST_PORT}/live.flv?mt={media}", timeout=6) as r:
        flv.write_bytes(r.read(2_000_000))
    print(f"FLV bytes={flv.stat().st_size}")
    # 抽一帧留证据（ffmpeg 可选）
    for candidate in [ROOT / ".tooling/pylibs", Path(sys.executable).parent]:
        pass
    ffmpeg = os.environ.get("FFMPEG", "")
    if ffmpeg:
        subprocess.run([ffmpeg, "-y", "-loglevel", "error", "-i", str(flv), "-frames:v", "1", str(OUT / "frame.png")], check=False)
    if flv.stat().st_size < 10_000:
        print("FAIL: FLV too small", file=sys.stderr); return 1

    if os.environ.get("FFMPEG"):
        subprocess.run([os.environ["FFMPEG"], "-y", "-loglevel", "error", "-i", str(flv),
                        "-frames:v", "1", str(OUT / "frame.png")], check=False)
        try:
            from PIL import Image
            im = Image.open(OUT / "frame.png").convert("RGB")
            w, h = im.size
            crop = im.crop((w - 360, h - 140, w, h))
            bright = sum(1 for px in crop.getdata() if px[0] > 200 and px[1] > 200 and px[2] > 200)
            print(f"watermark bright pixels bottom-right={bright}")
        except Exception as e:
            print(f"watermark check skipped: {e}")

    b = bounds(xml, "停止推流")
    if not b:
        print("FAIL: stop button not found", file=sys.stderr); return 1
    tap_bounds(b)
    time.sleep(4)
    xml = ui_dump()
    if "局域网推流中" in xml:
        print("FAIL: stream still active after stop", file=sys.stderr); return 1
    rec = subprocess.run([ADB, "shell", "run-as", "com.campush.app", "ls", "-t", "files/recordings"],
                         capture_output=True, text=True)
    names = [n for n in rec.stdout.strip().splitlines() if n.endswith(".mp4")]
    if names:
        remote = f"/data/data/com.campush.app/files/recordings/{names[0]}"
        local = OUT / names[0]
        with open(local, "wb") as f:
            subprocess.run([ADB, "exec-out", "run-as", "com.campush.app", "cat", remote], stdout=f, check=False)
        print(f"recording={local} bytes={local.stat().st_size}")
    else:
        print("WARN: no recording file found")
    print("LAN E2E OK: start/FLV/stop")
    return 0

if __name__ == "__main__":
    sys.exit(main())
