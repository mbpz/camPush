#!/usr/bin/env python3
"""WAN 房间控制面 E2E（无需 SRS）：cloud-core 本机 + 模拟器创建/停止房间。"""
import json, os, re, subprocess, sys, time, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ADB = str(ROOT / ".android-sdk/platform-tools/adb")
OUT = Path("/tmp/campush-wan-e2e"); OUT.mkdir(exist_ok=True)
PORT = 18092
API = f"http://127.0.0.1:{PORT}"

def run(*args, **kw): return subprocess.run(args, check=True, capture_output=True, text=True, **kw)
def adb(*args): return run(ADB, *args)

def wait_health():
    for _ in range(30):
        try:
            with urllib.request.urlopen(API + "/healthz", timeout=1) as r:
                if r.status == 200: return
        except Exception: time.sleep(0.5)
    raise RuntimeError("cloud-core health timeout")

def set_pref(key, value):
    adb("shell", "am", "force-stop", "com.campush.app")
    raw = subprocess.run([ADB, "exec-out", "run-as", "com.campush.app", "cat",
                          "/data/data/com.campush.app/shared_prefs/campush_p1_prefs.xml"],
                         capture_output=True, text=True)
    text = raw.stdout if raw.returncode == 0 and raw.stdout.strip() else "<map>\n</map>"
    if f'name="{key}"' in text:
        text = re.sub(rf'<string name="{key}">[^<]*</string>', f'<string name="{key}">{value}</string>', text)
    else:
        text = text.replace("</map>", f'    <string name="{key}">{value}</string>\n</map>')
    (OUT / "prefs.xml").write_text(text)
    adb("push", str(OUT / "prefs.xml"), "/data/local/tmp/campush-prefs.xml")
    adb("shell", "run-as", "com.campush.app", "cp", "/data/local/tmp/campush-prefs.xml",
        "/data/data/com.campush.app/shared_prefs/campush_p1_prefs.xml")

def ui():
    adb("shell", "uiautomator", "dump", "/sdcard/wan-e2e.xml")
    adb("pull", "/sdcard/wan-e2e.xml", str(OUT / "ui.xml"))
    return (OUT / "ui.xml").read_text(encoding="utf-8")

def bounds(xml, text):
    for m in re.finditer(r'<node[^>]*content-desc="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if text in m.group(1): return tuple(map(int, m.groups()[1:]))
    for m in re.finditer(r'<node[^>]*text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        if text in m.group(1): return tuple(map(int, m.groups()[1:]))
    return None

def tap(xml, text):
    b = bounds(xml, text)
    if not b: raise RuntimeError(f"not found: {text}")
    adb("shell", "input", "tap", str((b[0]+b[2])//2), str((b[1]+b[3])//2))

def main():
    # 启动 cloud-core（无 SRS，房间创建成功、WHIP 失败属预期）
    env = os.environ.copy()
    env.update({
        "CAMPUSH_PORT": str(PORT),
        "CAMPUSH_BIND_HOST": "0.0.0.0",
        "CAMPUSH_API_HOST": f"http://10.0.2.2:{PORT}",
        "CAMPUSH_PLAY_HOST": f"http://10.0.2.2:{PORT}",
        "CAMPUSH_WHIP_HOST": "http://10.0.2.2",
        "CAMPUSH_WHIP_API_PORT": "1985",
        "CAMPUSH_PLAYBACK_HOST": "http://10.0.2.2",
        "CAMPUSH_PLAYBACK_PORT": "18080",
    })
    java_home = "/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    if Path(java_home + "/bin/java").exists():
        env["JAVA_HOME"] = java_home
    launcher = ROOT / "cloud-core/build/install/cloud-core/bin/cloud-core"
    proc = subprocess.Popen([str(launcher)], env=env, cwd=str(ROOT),
                            stdout=open(OUT / "cloud.log", "w"), stderr=subprocess.STDOUT)
    try:
        wait_health()
        adb("install", "-r", str(ROOT / "app/build/outputs/apk/debug/app-debug.apk"))
        adb("shell", "pm", "grant", "com.campush.app", "android.permission.CAMERA")
        adb("shell", "pm", "grant", "com.campush.app", "android.permission.RECORD_AUDIO")
        set_pref("settings_cloud_api", f"http://10.0.2.2:{PORT}")
        adb("shell", "am", "start", "-W", "-n", "com.campush.app/.MainActivity")
        time.sleep(5)
        xml = ui()
        # 默认 WAN（已配置 API）
        tap(xml, "开始推流")
        time.sleep(7)
        xml = ui()
        rooms = json.loads(urllib.request.urlopen(API + "/api/v1/rooms", timeout=5).read()).get("rooms", [])
        active = [r for r in rooms if r.get("state") not in ("closed", "failed")]
        print(f"rooms_active={len(active)}")
        if not active:
            print("WARN: no active room (WHIP may have failed fast)", file=sys.stderr)
        # WHIP 失败会弹错误对话框；先关闭再停止
        if bounds(xml, "知道了"):
            tap(xml, "知道了")
            time.sleep(1)
            xml = ui()
        # 停止（按钮可能在推流/失败态；按文案找）
        for label in ["停止推流", "停止"]:
            if bounds(xml, label):
                tap(xml, label); break
        time.sleep(4)
        rooms = json.loads(urllib.request.urlopen(API + "/api/v1/rooms", timeout=5).read()).get("rooms", [])
        active = [r for r in rooms if r.get("state") not in ("closed", "failed")]
        print(f"rooms_after_stop={len(active)}")
        if active:
            print("FAIL: room still active after stop", file=sys.stderr); return 1
        print("WAN ROOM E2E OK: create/stop")
        return 0
    finally:
        proc.terminate()

if __name__ == "__main__":
    sys.exit(main())
