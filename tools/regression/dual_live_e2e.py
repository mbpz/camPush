#!/usr/bin/env python3
"""双摄合成直播 E2E：模拟器合成画面 -> RTMP，ffmpeg 本机监听落盘。"""
import os, re, subprocess, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ADB = str(ROOT / ".android-sdk/platform-tools/adb")
OUT = Path("/tmp/campush-dual-e2e"); OUT.mkdir(exist_ok=True)
FFMPEG = os.environ.get("FFMPEG", "ffmpeg")
RTMP = "rtmp://10.0.2.2:1935/live/dual"

def run(*args, **kw): return subprocess.run(args, check=True, capture_output=True, text=True, **kw)
def adb(*args): return run(ADB, *args)

def set_pref(key, value, xml_type="string"):
    adb("shell", "am", "force-stop", "com.campush.app")
    raw = subprocess.run([ADB, "exec-out", "run-as", "com.campush.app", "cat",
                          "/data/data/com.campush.app/shared_prefs/campush_p1_prefs.xml"],
                         capture_output=True, text=True)
    text = raw.stdout if raw.returncode == 0 and raw.stdout.strip() else "<map>\n</map>"
    if f'name="{key}"' in text:
        text = re.sub(rf'<{xml_type} name="{key}">[^<]*</{xml_type}>',
                      f'<{xml_type} name="{key}">{value}</{xml_type}>', text)
    else:
        text = text.replace("</map>", f'    <{xml_type} name="{key}">{value}</{xml_type}>\n</map>')
    (OUT / "prefs.xml").write_text(text)
    adb("push", str(OUT / "prefs.xml"), "/data/local/tmp/campush-prefs.xml")
    adb("shell", "run-as", "com.campush.app", "cp", "/data/local/tmp/campush-prefs.xml",
        "/data/data/com.campush.app/shared_prefs/campush_p1_prefs.xml")

def ui():
    adb("shell", "uiautomator", "dump", "/sdcard/dual-e2e.xml")
    adb("pull", "/sdcard/dual-e2e.xml", str(OUT / "ui.xml"))
    return (OUT / "ui.xml").read_text(encoding="utf-8")

def bounds(xml, text):
    for m in re.finditer(r'<node[^>]*content-desc="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if text in m.group(1): return tuple(map(int, m.groups()[1:]))
    for m in re.finditer(r'<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if text in m.group(1): return tuple(map(int, m.groups()[1:]))
    return None

def tap_bounds(b):
    adb("shell", "input", "tap", str((b[0]+b[2])//2), str((b[1]+b[3])//2))

def main():
    adb("install", "-r", str(ROOT / "app/build/outputs/apk/debug/app-debug.apk"))
    adb("shell", "pm", "grant", "com.campush.app", "android.permission.CAMERA")
    set_pref("settings_rtmp_url", RTMP)
    adb("shell", "am", "start", "-W", "-n", "com.campush.app/.MainActivity")
    time.sleep(4)
    xml = ui()
    if not bounds(xml, "打开设置"):
        print("FAIL: settings button not found", file=sys.stderr); return 1
    tap_bounds(bounds(xml, "打开设置")); time.sleep(2)
    for _ in range(6):
        xml = ui()
        if bounds(xml, "双摄 PiP 预览"): break
        adb("shell", "input", "swipe", "540", "1700", "540", "600", "300"); time.sleep(0.6)
    b = bounds(xml, "双摄 PiP 预览")
    if not b:
        print("FAIL: dual PiP entry not found (device may not support)", file=sys.stderr); return 1
    tap_bounds(b); time.sleep(4)
    xml = ui()
    live = bounds(xml, "开始合成直播")
    if not live:
        print("FAIL: live button not found", file=sys.stderr); return 1

    listener = subprocess.Popen([FFMPEG, "-hide_banner", "-loglevel", "error", "-listen", "1",
                                 "-i", "rtmp://0.0.0.0:1935/live/dual", "-c", "copy", "-t", "12",
                                 "-y", str(OUT / "dual.flv")],
                                stdout=open(OUT / "ffmpeg.log", "w"), stderr=subprocess.STDOUT)
    time.sleep(1)
    tap_bounds(live); time.sleep(9)
    xml = ui()
    stop = bounds(xml, "停止合成直播")
    if stop: tap_bounds(stop)
    time.sleep(4)
    listener.terminate()
    try: listener.wait(timeout=3)
    except Exception: listener.kill()
    flv = OUT / "dual.flv"
    if not flv.exists() or flv.stat().st_size < 10_000:
        print(f"FAIL: dual RTMP output too small: {flv.stat().st_size if flv.exists() else 0}", file=sys.stderr)
        return 1
    print(f"DUAL LIVE E2E OK: bytes={flv.stat().st_size}")
    return 0

if __name__ == "__main__":
    sys.exit(main())
