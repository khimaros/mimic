"""shared adb + surface helpers for the e2e tests and verify_device.py.

drives the device over `adb`: install, enable accessibility, reveal the token and
toggle surfaces through the ui, then exercise the intents, http, and mcp surfaces.
"""

import json
import os
import pathlib
import re
import shlex
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

PKG = "com.khimaros.mimic"
ACTIVITY = f"{PKG}/{PKG}.MainActivity"
SERVICE = f"{PKG}/{PKG}.MimicService"
RECEIVER = f"{PKG}/.CommandReceiver"
ACTION = PKG + ".action."
PORT = 8473
BASE = f"http://127.0.0.1:{PORT}"

APK = pathlib.Path(__file__).resolve().parents[1] / "app/build/outputs/apk/debug/app-debug.apk"
CLI = pathlib.Path(__file__).resolve().parents[1] / "cli/mimic"

_DATA_RE = re.compile(r'data="([^"]*)"')
_CODE_RE = re.compile(r"(\d{6})")
_TOKEN_RE = re.compile(r"([A-Za-z0-9_-]{24,})")


def adb(*args):
    return subprocess.run(["adb", *args], capture_output=True, text=True)


def shell(*parts):
    return adb("shell", " ".join(shlex.quote(p) for p in parts)).stdout


def device_available():
    try:
        out = subprocess.run(["adb", "get-state"], capture_output=True, text=True)
    except FileNotFoundError:
        return False
    return out.returncode == 0 and out.stdout.strip() == "device"


def wake():
    """turn the screen on if it slept. does not dismiss a keyguard -- see locked()."""
    if "mScreenState=OFF" in shell("dumpsys", "nfc"):
        shell("input", "keyevent", "KEYCODE_WAKEUP")
        time.sleep(1.0)


def locked():
    """is a keyguard covering the screen? the ui harness drives the app through
    uiautomator, which sees the lock screen rather than the app, so every fixture
    would otherwise fail with an unrelated 'widget not found'."""
    return "isKeyguardShowing=true" in shell("dumpsys", "window")


# ---- setup ----

def install():
    adb("install", "-r", "-g", str(APK))
    # grant the optional draw-over permission so the approval prompt uses the
    # application overlay, which uiautomator can dump and tap.
    shell("appops", "set", PKG, "SYSTEM_ALERT_WINDOW", "allow")


def enable_accessibility():
    current = shell("settings", "get", "secure", "enabled_accessibility_services").strip()
    if SERVICE not in current:
        merged = SERVICE if current in ("", "null") else f"{current}:{SERVICE}"
        shell("settings", "put", "secure", "enabled_accessibility_services", merged)
    shell("settings", "put", "secure", "accessibility_enabled", "1")
    time.sleep(1.5)


def launch():
    shell("am", "start", "-n", ACTIVITY)
    time.sleep(1.5)


def forward():
    """map the host port to the device's, and prove THIS device got it.

    the host side of a forward is global across adb servers, but `adb forward`
    reports success either way -- so when another server (another phone, or an
    emulator on another lane) already holds the port, every http request quietly
    goes to THAT device. the suite then installs onto one device and tests
    another, which shows up as unauthorized-everywhere if the tokens differ and,
    far worse, as a green run if they happen not to."""
    adb("forward", f"tcp:{PORT}", f"tcp:{PORT}")
    # match on the serial, not just the port: a server adopts every running
    # emulator whatever --one-device says, so "some device here holds it" is not
    # "the device under test holds it".
    serial = adb("get-serialno").stdout.strip()
    held = [ln.split() for ln in adb("forward", "--list").stdout.splitlines()]
    port = f"tcp:{PORT}"
    if not any(len(p) >= 2 and p[0] == serial and p[1] == port for p in held):
        owner = next((p[0] for p in held if len(p) >= 2 and p[1] == port), "another adb server")
        raise AssertionError(
            f"host port {PORT} is forwarded to {owner}, not to {serial}, so http "
            f"requests would reach a different device than the one under test. "
            f"free it with `adb forward --remove {port}` on whichever server holds it."
        )


# ---- ui automation ----

def ui():
    shell("uiautomator", "dump", "/sdcard/mimic_e2e.xml")
    return ET.fromstring(adb("shell", "cat", "/sdcard/mimic_e2e.xml").stdout)


def select_tab(name):
    """tap a bottom tab (general|clients|surfaces|permissions). the tab buttons are
    the only Buttons whose text exactly equals the tab name, so match precisely."""
    launch()
    root = ui()
    target = None
    for n in root.iter("node"):
        if (n.get("class") or "").endswith("Button") and (n.get("text") or "").strip().lower() == name.lower():
            target = n
    assert target is not None, f"tab not found: {name}"
    tap_node(target)
    time.sleep(0.4)


def node_with(root, needle):
    # case-insensitive: platform buttons render their text upper-cased.
    needle = needle.lower()
    for n in root.iter("node"):
        blob = ((n.get("text") or "") + " " + (n.get("content-desc") or "")).lower()
        if needle in blob:
            return n
    return None


def _center(node):
    l, t, r, b = map(int, re.findall(r"\d+", node.get("bounds")))
    return (l + r) // 2, (t + b) // 2


def tap_node(node):
    x, y = _center(node)
    shell("input", "tap", str(x), str(y))
    time.sleep(0.6)


def find_scrolling(needle):
    """scroll the screen (top first) until a node matching needle is on-screen."""
    w, h = screen_size()
    for _ in range(5):  # to the top
        shell("input", "swipe", str(w // 2), str(int(h * 0.3)), str(w // 2), str(int(h * 0.85)), "150")
        time.sleep(0.15)
    for _ in range(10):
        node = node_with(ui(), needle)
        if node is not None:
            t, b = (lambda v: (v[1], v[3]))(list(map(int, re.findall(r"\d+", node.get("bounds")))))
            if 0 <= (t + b) // 2 <= h - 120:
                return node
        shell("input", "swipe", str(w // 2), str(int(h * 0.7)), str(w // 2), str(int(h * 0.3)), "150")
        time.sleep(0.3)
    return None


def ensure_switch(label, desired=True):
    node = find_scrolling(label)
    assert node is not None, f"switch not found: {label}"
    if (node.get("checked") == "true") != desired:
        tap_node(node)


def scroll_top():
    """scroll the onboarding screen to the top so top widgets are in the dump."""
    w, h = screen_size()
    for _ in range(3):
        shell("input", "swipe", str(w // 2), str(int(h * 0.3)), str(w // 2), str(int(h * 0.85)), "200")
        time.sleep(0.2)


def reveal_token():
    """reveal a legacy token in the ui (the manual-config path) and return it.
    first clears any clients/grants accumulated across prior runs (data survives
    a -r reinstall) so the onboarding screen stays short and predictable."""
    select_tab("clients")
    clear = find_scrolling("revoke all")
    if clear is not None:
        tap_node(clear)
        time.sleep(0.4)
    scroll_top()
    before = _creds_text()
    tap_node(node_with(ui(), "reveal legacy token"))
    text = _await_creds_change(before)
    m = _TOKEN_RE.search(text)
    assert m, f"token not found in ui: {text!r}"
    # tokens default to 'ask'; flip this (now lone) client to allow-all so the
    # suite never triggers an on-device prompt that would need a manual tap.
    _make_lone_client_allow_all()
    return m.group(1)


def _creds_text():
    """the creds field uniquely contains "x-mimic-token"; the token is the only
    long base64url run in it."""
    node = node_with(ui(), "x-mimic-token")
    return (node.get("text") if node is not None else "") or ""


def _await_creds_change(before, timeout=5.0):
    """wait for the creds field to show a token the reveal tap actually produced.

    the field can still be showing a token from an earlier reveal -- one that the
    "revoke all" above has just invalidated. reading it without requiring a CHANGE
    cannot tell the new token from the stale one, so a tap that missed returns a
    revoked token and every later test fails as an unexplained 401."""
    deadline = time.monotonic() + timeout
    while True:
        text = _creds_text()
        if text and text != before:
            return text
        assert time.monotonic() < deadline, (
            f"credentials field never changed after tapping reveal (still {before!r}); "
            "the tap likely missed -- returning this token would fail every later "
            "test as unauthorized"
        )
        time.sleep(0.3)


def _make_lone_client_allow_all():
    for _ in range(3):
        btn = find_scrolling("mode:")  # the single client's mode button
        if btn is None or "allow_all" in (btn.get("text") or "").lower():
            return
        tap_node(btn)
        time.sleep(0.3)


def start_pairing():
    """open a pairing window in the ui and return the one-time code."""
    select_tab("clients")
    scroll_top()
    tap_node(node_with(ui(), "start pairing"))
    creds = node_with(ui(), "pairing code")
    text = creds.get("text") if creds is not None else ""
    m = _CODE_RE.search(text or "")
    assert m, f"pairing code not found in ui: {text!r}"
    return m.group(1)


def http_pair(code, label="e2e"):
    """exchange a one-time code for a per-client token over the unauthenticated
    POST /pair route. returns (status, json)."""
    return http("POST", "/pair", "", {"code": code, "label": label})


def screen_size():
    m = re.search(r"(\d+)x(\d+)", shell("wm", "size"))
    return (int(m.group(1)), int(m.group(2))) if m else (1080, 2400)


def set_approval(value):
    """toggle the 'enable fine-grained permissions' switch (general tab)."""
    select_tab("general")
    ensure_switch("fine-grained permissions", value)


def set_auth(value):
    """toggle the 'require authentication' switch (general tab)."""
    select_tab("general")
    ensure_switch("require authentication", value)


def set_master(value):
    """toggle the global 'enable mimic' kill switch (general tab); off disables
    every surface."""
    select_tab("general")
    ensure_switch("enable mimic", value)


def host_notifications():
    """count active mimic host-service notifications (0 when the server is off)."""
    return shell("dumpsys", "notification", "--noredact").count("pkg=" + PKG)


def revoke_in_ui(token_id):
    """tap the revoke button for a given client id (gui-only token management).
    the clients list updates live when a client pairs over http, so just open the
    clients tab and scroll the row into view."""
    select_tab("clients")
    needle = f"revoke {token_id}"
    w, h = screen_size()
    for _ in range(8):
        node = node_with(ui(), needle)
        if node is not None:
            l, t, r, b = map(int, re.findall(r"\d+", node.get("bounds")))
            if 0 <= (t + b) // 2 <= h - 100:  # comfortably on-screen
                tap_node(node)
                return
        shell("input", "swipe", str(w // 2), str(int(h * 0.7)), str(w // 2), str(int(h * 0.3)), "300")
        time.sleep(0.4)
    raise AssertionError(f"revoke button not reachable for client {token_id}")


# ---- surfaces ----

def broadcast(action, **extras):
    """intents surface over `adb shell am` (shell uid returns results)."""
    parts = ["am", "broadcast", "-n", RECEIVER, "-a", ACTION + action]
    for k, v in extras.items():
        parts += ["--es", k, str(v)]
    out = shell(*parts)
    m = _DATA_RE.search(out)
    if not m:
        return None
    import base64
    return json.loads(base64.b64decode(m.group(1)).decode())


def cli(args, token, timeout=30):
    """run the real cli/mimic shell script over the http surface and return
    (returncode, stdout). exercises the script itself, not just the surfaces."""
    home = tempfile.mkdtemp()
    (pathlib.Path(home) / "token").write_text(token)
    env = {**os.environ, "MIMIC_HOME": home, "MIMIC_TRANSPORT": "http", "MIMIC_HOST": f"127.0.0.1:{PORT}"}
    p = subprocess.run(["sh", str(CLI), *args], capture_output=True, text=True, env=env, timeout=timeout)
    return p.returncode, p.stdout


def http(method, path, token, body=None, timeout=5):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("x-mimic-token", token)
    if data:
        req.add_header("content-type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def mcp(token, method, params=None, rid=1):
    body = {"jsonrpc": "2.0", "id": rid, "method": method}
    if params is not None:
        body["params"] = params
    return http("POST", "/mcp", token, body)


def top_activity():
    out = adb("shell", "dumpsys activity activities").stdout
    m = re.search(r"topResumedActivity=\S+ u\d+ (\S+)", out)
    return m.group(1) if m else ""


def http_text(path):
    """GET a static (unauthenticated) route, returning (status, body text)."""
    req = urllib.request.Request(BASE + path, method="GET")
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def http_raw(path, token, body):
    """POST and return (status, content_type, raw bytes) -- for binary responses."""
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), method="POST")
    req.add_header("x-mimic-token", token)
    req.add_header("content-type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, r.headers.get("content-type", ""), r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.headers.get("content-type", ""), e.read()
