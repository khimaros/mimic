"""end-to-end coverage of the three surfaces against a real device/emulator.

verifies the requirements: view (dump/find with on-device filtering and query),
interact (tap/swipe/global/set-text), the token gate, and parity across the
intents, http, and mcp surfaces.
"""

import json
import time

import adb
import pytest


# ---- http surface ----

def test_http_status(token):
    s, b = adb.http("POST", "/v1/status", token)
    assert s == 200 and b["ok"]
    assert b["data"]["service_enabled"] is True
    assert b["data"]["http"] is True
    assert b["data"]["bind"] == "127.0.0.1"  # loopback by default
    assert b["data"]["require_auth"] is True  # token required by default
    assert b["data"]["require_approval"] is False  # fine-grained off by default


def test_http_healthz_unauthenticated(token):
    s, b = adb.http("GET", "/healthz", "")  # no token
    assert s == 200 and b["ok"]


def test_bootstrap_serves_cli_and_skill(token):
    s, body = adb.http_text("/cli/mimic")  # unauthenticated
    assert s == 200 and body.startswith("#!") and "mimic --" in body
    s, body = adb.http_text("/SKILL.md")
    assert s == 200 and "mimic" in body.lower()


def test_http_rejects_bad_token(token):
    s, b = adb.http("POST", "/v1/status", "WRONGTOKEN")
    assert s == 401 and b["ok"] is False


def test_http_dump_compact_is_terse(token):
    s, b = adb.http("POST", "/v1/dump", token, {"filter": "interactive", "format": "compact"})
    assert s == 200 and b["ok"]
    assert isinstance(b["data"], str)
    for line in b["data"].splitlines():
        # the documented default columns: cx,cy<TAB>class<TAB>label<TAB>id.
        assert len(line.split("\t")) == 4, line
        assert "," in line.split("\t", 1)[0]


# ---- the element table an agent acts on ----
# an agent surveys a screen with `dump --filter interactive --format compact` and
# taps a row by its label. these cover what that table has to carry to be usable.

# the actions a node must support to be worth offering as a target.
ACTIONABLE = {"click", "long", "edit", "scroll", "check"}


def _settings_main():
    """the settings main screen: clickable rows whose labels live in children."""
    adb.shell("am", "start", "-a", "android.settings.SETTINGS")
    time.sleep(2.5)
    if "settings" not in adb.top_activity().lower():
        pytest.skip("settings main screen unavailable")


def _compact(token, **params):
    s, b = adb.http("POST", "/v1/dump", token, dict(params, format="compact"))
    assert s == 200 and b["ok"], b
    return [line.split("\t") for line in b["data"].splitlines() if line]


def _label(cols):
    return cols[2] if len(cols) > 2 else ""


def test_http_interactive_rows_carry_their_label(token):
    # a settings row is a clickable LinearLayout holding its name in child
    # TextViews -- the dominant android pattern. unless the row inherits that
    # text, an agent sees 25 indistinguishable containers and the only named node
    # on the screen is the scroll container, so it taps that instead.
    _settings_main()
    titles = [_label(c) for c in _compact(token, filter="text")]
    titles = [t for t in titles if len(t) >= 4]
    rows = _compact(token, filter="interactive")
    adb.http("POST", "/v1/global", token, {"nav": "home"})
    assert rows, "no interactive rows on the settings main screen"
    named = [c for c in rows if _label(c)]
    assert len(named) * 2 >= len(rows), \
        f"{len(rows) - len(named)} of {len(rows)} interactive rows are unlabelled: {rows}"
    carried = [t for t in titles if any(t in _label(c) for c in rows)]
    assert len(carried) >= 3, f"row text never reached its clickable row: {titles} vs {rows}"


def test_http_interactive_is_actionable_only(token):
    # a focusable-only container is not somewhere to tap: every node offered as
    # interactive must support an action mimic can actually perform. mimic's own
    # ui is the subject because it reliably holds focusable-only nodes (a
    # ScrollView whose content fits, and a focusable TextView) -- the settings
    # main screen happens to have none, so it cannot fail this check.
    adb.shell("am", "start", "-n", adb.ACTIVITY)
    time.sleep(1.5)
    s, b = adb.http("POST", "/v1/dump", token,
                    {"filter": "all", "format": "flat", "fields": "class,actions"})
    assert s == 200 and b["ok"] and b["data"], b
    focus_only = [n for n in b["data"]
                  if n.get("actions") and not set(n["actions"]) & ACTIONABLE]
    assert focus_only, "no focusable-only node on screen; the check cannot fail here"
    s, b = adb.http("POST", "/v1/dump", token,
                    {"filter": "interactive", "format": "flat", "fields": "class,actions"})
    assert s == 200 and b["ok"] and b["data"], b
    for node in b["data"]:
        assert set(node.get("actions") or []) & ACTIONABLE, node


def test_http_compact_honours_fields(token):
    # compact is the format SKILL.md recommends to agents, so it must be able to
    # carry the field that tells a button from a scroll container.
    cols = _compact(token, filter="interactive", fields="center,label,actions")
    assert cols
    for c in cols:
        assert len(c) == 3, c
        assert "," in c[0]
        assert c[2], c  # the actions column names at least one action


def test_http_filters_combine(token):
    # the tree holds rows below the fold, whose centres are off-screen and never
    # meaningful to tap. combining filters is how an agent asks for only the rows
    # it can reach, without a second dump to join against.
    _settings_main()
    every = _compact(token, filter="interactive")
    shown = _compact(token, filter="interactive,visible")
    adb.http("POST", "/v1/global", token, {"nav": "home"})
    assert shown and len(shown) <= len(every), (len(shown), len(every))
    for c in shown:
        cx, cy = (int(v) for v in c[0].split(","))
        assert cx >= 0 and cy >= 0, c


def test_http_find_flat(token):
    adb.shell("am", "start", "-n", adb.ACTIVITY)  # our own ui is in front
    time.sleep(1.0)  # the window must actually be up, or find has nothing to read
    s, b = adb.http("POST", "/v1/find", token, {"query": "surface", "by": "text"})
    assert s == 200 and b["ok"] and isinstance(b["data"], list), b


def test_http_fields_selection(token):
    s, b = adb.http("POST", "/v1/dump", token, {"format": "flat", "fields": "center"})
    assert s == 200 and b["ok"]
    for node in b["data"]:
        assert set(node.keys()) <= {"center"}


def test_http_tap_and_global(token):
    s, b = adb.http("POST", "/v1/tap", token, {"x": 10, "y": 10})
    assert s == 200 and b["ok"] and b["data"]["performed"] is True
    s, b = adb.http("POST", "/v1/global", token, {"nav": "home"})
    assert s == 200 and b["ok"] and b["data"]["performed"] is True


def test_http_screenshot_returns_png(token):
    s, ct, data = adb.http_raw("/v1/screenshot", token, {"format": "png", "scale": "0.5"})
    assert s == 200, ct
    assert ct.startswith("image/png")
    assert data[:8] == b"\x89PNG\r\n\x1a\n"  # png magic bytes
    assert len(data) > 1000


def _launchable_third_party():
    listed = adb.shell("pm", "list", "packages", "-3").split()
    for entry in listed:
        pkg = entry.replace("package:", "").strip()
        if not pkg:
            continue
        resolved = adb.shell(
            "cmd", "package", "resolve-activity", "--brief",
            "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.LAUNCHER", pkg,
        )
        if "/" in resolved and "No activity" not in resolved:
            return pkg
    return None


def test_http_launch_third_party_app(token):
    # the visibility fix (<queries>) lets `launch` resolve non-system apps.
    pkg = _launchable_third_party()
    if not pkg:
        pytest.skip("no launchable third-party app installed")
    adb.shell("am", "start", "-n", adb.ACTIVITY)  # foreground first (bal grace)
    time.sleep(1.0)
    s, b = adb.http("POST", "/v1/launch", token, {"package": pkg})
    assert s == 200 and b["ok"] and b["data"]["launched"] is True, b
    time.sleep(1.5)
    assert pkg in adb.top_activity()
    adb.http("POST", "/v1/global", token, {"nav": "home"})


def test_http_launch_app(token):
    adb.shell("am", "start", "-n", adb.ACTIVITY)  # foreground first (bal grace)
    time.sleep(1.0)
    s, b = adb.http("POST", "/v1/launch", token, {"package": "com.android.settings"})
    assert s == 200 and b["ok"] and b["data"]["launched"] is True
    time.sleep(1.5)
    top = adb.top_activity()
    adb.http("POST", "/v1/global", token, {"nav": "home"})  # cleanup
    assert "com.android.settings" in top


def test_http_packages_lists_launchable(token):
    s, b = adb.http("POST", "/v1/packages", token)
    assert s == 200 and b["ok"] and isinstance(b["data"], list) and b["data"]
    assert {"package", "label", "component"} <= set(b["data"][0].keys())
    assert "com.android.settings" in [e["package"] for e in b["data"]]


def test_http_packages_query_filter(token):
    s, b = adb.http("POST", "/v1/packages", token, {"query": "settings"})
    assert s == 200 and b["ok"]
    assert all("settings" in (e["package"] + e["label"]).lower() for e in b["data"])


def test_http_packages_fuzzy(token):
    # a typo still finds the app via edit-distance matching.
    s, b = adb.http("POST", "/v1/packages", token, {"query": "settngs", "fuzzy": True})
    assert s == 200 and b["ok"]
    assert any("settings" in e["label"].lower() for e in b["data"]), b["data"]


def test_cli_packages(token):
    # exercises the real cli/mimic script (regression: it once sent query=PACKAGES).
    rc, out = adb.cli(["packages"], token)
    assert rc == 0, out
    data = json.loads(out)["data"]
    assert isinstance(data, list) and len(data) > 5
    rc, out = adb.cli(["packages", "settings"], token)
    data = json.loads(out)["data"]
    assert data and all("settings" in (e["package"] + e["label"]).lower() for e in data)
    rc, out = adb.cli(["packages", "settngs", "--fuzzy"], token)
    data = json.loads(out)["data"]
    assert any("settings" in e["label"].lower() for e in data), out


def test_set_text_into_focused_field(token):
    # set-text with no target should land in whatever field has input focus.
    adb.shell("am", "start", "-a", "android.intent.action.INSERT", "-t", "vnd.android.cursor.dir/contact")
    time.sleep(2.0)
    s, b = adb.http("POST", "/v1/find", token, {"query": "EditText", "by": "class"})
    fields = b.get("data") or []
    if not (s == 200 and b["ok"] and fields):
        pytest.skip("no editable field available to focus")
    cx, cy = fields[0]["center"]
    adb.http("POST", "/v1/click", token, {"x": cx, "y": cy})  # focus it
    time.sleep(0.5)
    s, b = adb.http("POST", "/v1/set_text", token, {"text": "mimicfocus"})  # no target
    assert s == 200 and b["ok"] and b["data"]["performed"] is True, b
    time.sleep(0.5)
    s, b = adb.http("POST", "/v1/find", token, {"query": "mimicfocus", "by": "text"})
    assert s == 200 and b["ok"] and len(b["data"]) >= 1, b
    adb.shell("input", "keyevent", "KEYCODE_BACK")
    adb.shell("input", "keyevent", "KEYCODE_BACK")


# ---- wait + launch --wait ----

def test_http_wait_found(token):
    adb.shell("am", "start", "-n", adb.ACTIVITY)  # our ui shows the bottom tabs
    time.sleep(1.0)
    s, b = adb.http("POST", "/v1/wait", token, {"query": "general", "by": "text", "timeout": 5}, timeout=10)
    assert s == 200 and b["ok"] and isinstance(b["data"], list) and len(b["data"]) >= 1, b


def test_http_wait_timeout(token):
    adb.shell("am", "start", "-n", adb.ACTIVITY)
    time.sleep(1.0)
    t0 = time.monotonic()
    s, b = adb.http("POST", "/v1/wait", token, {"query": "zzznope999", "by": "text", "timeout": 2}, timeout=10)
    assert s == 200 and b["ok"] is False and "wait" in (b.get("error") or "").lower(), b
    assert time.monotonic() - t0 >= 1.5  # it actually polled for the timeout


def test_http_launch_wait_foreground(token):
    adb.shell("am", "start", "-n", adb.ACTIVITY)  # foreground first (bal grace)
    time.sleep(1.0)
    s, b = adb.http("POST", "/v1/launch", token,
                    {"package": "com.android.settings", "wait": True, "timeout": 10}, timeout=15)
    assert s == 200 and b["ok"] and b["data"]["launched"] is True and b["data"]["foreground"] is True, b
    adb.http("POST", "/v1/global", token, {"nav": "home"})


def test_cli_wait(token):
    adb.shell("am", "start", "-n", adb.ACTIVITY)
    time.sleep(1.0)
    rc, out = adb.cli(["wait", "general", "--by", "text", "--timeout", "5"], token)
    assert rc == 0, out
    data = json.loads(out)["data"]
    assert isinstance(data, list) and len(data) >= 1


# ---- scroll (until found) ----

def _scroll_to_top(token):
    # a never-matching scroll-up runs to the top and stops (end of content), a
    # deterministic anchor regardless of where the list was left.
    adb.http("POST", "/v1/scroll", token,
             {"direction": "up", "query": "zz_nomatch_top", "steps": 30, "timeout": 20}, timeout=45)
    time.sleep(0.4)


def _visible_names(token):
    # on-screen app names (drop size subtitles and the sticky "All apps" header).
    s, b = adb.http("POST", "/v1/dump", token, {"format": "flat", "fields": "text", "filter": "visible"})
    if not (s == 200 and b["ok"]):
        return []
    return [e["text"] for e in b["data"]
            if e.get("text") and len(e["text"]) >= 4 and any(c.isalpha() for c in e["text"])
            and "All apps" not in e["text"]]


def test_http_scroll_once_performs(token):
    # a directional scroll with no query just performs a single swipe.
    adb.shell("am", "start", "-n", adb.ACTIVITY)
    time.sleep(1.0)
    s, b = adb.http("POST", "/v1/scroll", token, {"direction": "down"})
    assert s == 200 and b["ok"] and b["data"]["performed"] is True, b


def test_http_scroll_until_found_visible(token):
    # a target already on screen is returned immediately (zero scrolls needed).
    adb.shell("am", "start", "-n", adb.ACTIVITY)
    time.sleep(1.0)
    s, b = adb.http("POST", "/v1/scroll", token,
                    {"direction": "down", "query": "general", "by": "text", "timeout": 5}, timeout=10)
    assert s == 200 and b["ok"] and isinstance(b["data"], list) and len(b["data"]) >= 1, b


def test_http_scroll_until_not_found(token):
    adb.shell("am", "start", "-n", adb.ACTIVITY)
    time.sleep(1.0)
    s, b = adb.http("POST", "/v1/scroll", token,
                    {"direction": "down", "query": "zzznope999", "by": "text", "timeout": 3, "steps": 3}, timeout=15)
    assert s == 200 and b["ok"] is False and "not found" in (b.get("error") or "").lower(), b


def test_http_scroll_traverses_recycler(token):
    # the real traversal: a recycling list (all-apps) holds only on-screen rows in
    # the tree, so an off-screen app cannot match until scrolled into view. search
    # for "Settings" -- present on every device and sorted near the end -- so on a
    # populated list this scrolls through many screens, and on a short one it is
    # simply found. either way the returned match must be on-screen.
    adb.shell("am", "start", "-a", "android.settings.MANAGE_APPLICATIONS_SETTINGS")
    time.sleep(2.5)
    if "settings" not in adb.top_activity().lower():
        pytest.skip("all-apps settings screen unavailable")
    _scroll_to_top(token)
    s, b = adb.http("POST", "/v1/scroll", token,
                    {"direction": "down", "query": "Settings", "by": "text", "match": "exact",
                     "timeout": 30, "steps": 30}, timeout=60)
    adb.http("POST", "/v1/global", token, {"nav": "home"})  # cleanup
    assert s == 200 and b["ok"] and isinstance(b["data"], list) and b["data"], b
    # the returned match is on-screen (filter=visible), so its text equals "Settings".
    assert any(e.get("text") == "Settings" for e in b["data"]), b["data"]


def test_http_scroll_skip_visible(token):
    # default scroll-into-view returns a match already on screen without moving;
    # skip_visible ignores it and scrolls past to look further in the direction.
    adb.shell("am", "start", "-a", "android.settings.MANAGE_APPLICATIONS_SETTINGS")
    time.sleep(2.5)
    if "settings" not in adb.top_activity().lower():
        pytest.skip("all-apps settings screen unavailable")
    _scroll_to_top(token)
    names = _visible_names(token)
    if not names:
        pytest.skip("no on-screen app label to target")
    target = names[0]
    # default: returns the visible match and leaves it on screen (no scrolling).
    s, b = adb.http("POST", "/v1/scroll", token,
                    {"direction": "down", "query": target, "by": "text", "match": "exact", "timeout": 10}, timeout=30)
    assert s == 200 and b["ok"] and any(e.get("text") == target for e in b["data"]), b
    assert target in _visible_names(token), "scroll-into-view should not have moved off the visible match"
    # skip_visible: ignores the on-screen match and scrolls past it.
    _scroll_to_top(token)
    adb.http("POST", "/v1/scroll", token,
             {"direction": "down", "query": target, "by": "text", "match": "exact",
              "skip_visible": True, "timeout": 12, "steps": 12}, timeout=40)
    moved_off = target not in _visible_names(token)
    adb.http("POST", "/v1/global", token, {"nav": "home"})  # cleanup
    assert moved_off, "skip_visible should have scrolled past the visible match"


def test_cli_scroll(token):
    adb.shell("am", "start", "-n", adb.ACTIVITY)
    time.sleep(1.0)
    rc, out = adb.cli(["scroll", "down"], token)
    assert rc == 0, out
    assert json.loads(out)["data"]["performed"] is True


def test_mcp_scroll(token):
    adb.shell("am", "start", "-n", adb.ACTIVITY)
    time.sleep(1.0)
    r = _mcp_call(token, "mimic_scroll", {"direction": "down", "query": "general", "by": "text", "timeout": 5})
    assert r["isError"] is False and isinstance(r["content"][0]["text"], str)


# ---- pairing and per-client tokens ----

def _wrong_code(code):
    return ("2" if code[0] != "2" else "1") + code[1:]


def test_pair_over_http_mints_token(token):
    # the fix: a client with no token pairs over http (proot/termux can't return
    # an `am` broadcast result). the minted token then authenticates.
    code = adb.start_pairing()
    s, b = adb.http_pair(code, "pairclient")
    assert s == 200 and b["ok"], b
    minted, cid = b["data"]["token"], b["data"]["id"]
    assert minted and cid
    s, b = adb.http("POST", "/v1/status", minted)
    assert s == 200 and b["ok"]


def test_pair_code_is_one_time(token):
    code = adb.start_pairing()
    s, _ = adb.http_pair(code)
    assert s == 200
    s, b = adb.http_pair(code)  # window closed on first redeem
    assert s == 401 and b["ok"] is False


def test_pair_rejects_bad_code(token):
    code = adb.start_pairing()
    s, b = adb.http_pair(_wrong_code(code))
    assert s == 401 and b["ok"] is False
    s, b = adb.http_pair(code)  # real code still valid after a bad attempt
    assert s == 200 and b["ok"]


def test_pair_appears_in_list_without_reopen(token):
    # pairing happens over http and never touches the ui; a foreground app must
    # still show the new client live, without an exit/reopen to force onResume.
    code = adb.start_pairing()  # leaves the app in the foreground
    s, b = adb.http_pair(code, "liveclient")
    assert s == 200, b
    cid = b["data"]["id"]
    time.sleep(0.8)  # let the token-store change post back to the ui thread
    assert adb.node_with(adb.ui(), f"revoke {cid}") is not None, \
        "paired client did not appear in the list without reopening the app"


def test_gui_revoke_invalidates_one_token(token):
    c1 = adb.start_pairing()
    s, b = adb.http_pair(c1, "clientone")
    assert s == 200, b
    t1, id1 = b["data"]["token"], b["data"]["id"]
    c2 = adb.start_pairing()
    s, b = adb.http_pair(c2, "clienttwo")
    assert s == 200, b
    t2 = b["data"]["token"]
    assert adb.http("POST", "/v1/status", t1)[0] == 200
    assert adb.http("POST", "/v1/status", t2)[0] == 200
    adb.revoke_in_ui(id1)
    assert adb.http("POST", "/v1/status", t1)[0] == 401  # revoked
    assert adb.http("POST", "/v1/status", t2)[0] == 200  # untouched


# ---- mcp surface ----

def test_mcp_initialize(token):
    s, b = adb.mcp(token, "initialize", {"protocolVersion": "2025-06-18", "capabilities": {}})
    assert "result" in b
    assert b["result"]["serverInfo"]["name"] == "mimic"


def test_mcp_tools_list(token):
    s, b = adb.mcp(token, "tools/list")
    names = [t["name"] for t in b["result"]["tools"]]
    assert {"mimic_dump", "mimic_find", "mimic_wait", "mimic_tap", "mimic_status",
            "mimic_screenshot", "mimic_packages"} <= set(names)


def test_mcp_filter_schema_allows_combination(token):
    # the schema is what an mcp client shows the model, so it must not say a
    # combined filter is illegal.
    s, b = adb.mcp(token, "tools/list")
    tools = {t["name"]: t for t in b["result"]["tools"]}
    f = tools["mimic_dump"]["inputSchema"]["properties"]["filter"]
    assert "enum" not in f, f
    assert "," in f["description"], f


def test_mcp_screenshot_image_block(token):
    import base64
    time.sleep(1.2)  # screenshot is rate-limited to ~1/sec
    s, b = adb.mcp(token, "tools/call", {"name": "mimic_screenshot", "arguments": {"scale": "0.5"}})
    block = b["result"]["content"][0]
    assert block["type"] == "image" and block["mimeType"] == "image/png"
    assert base64.b64decode(block["data"])[:8] == b"\x89PNG\r\n\x1a\n"


def test_mcp_tools_call_status(token):
    s, b = adb.mcp(token, "tools/call", {"name": "mimic_status", "arguments": {}})
    assert b["result"]["isError"] is False
    assert "service_enabled" in b["result"]["content"][0]["text"]


def test_mcp_rejects_bad_token(token):
    s, b = adb.http("POST", "/mcp", "WRONGTOKEN", {"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
    assert s == 401


def _mcp_call(token, name, args=None):
    s, b = adb.mcp(token, "tools/call", {"name": name, "arguments": args or {}})
    return b["result"]


def test_mcp_launch_success_reads_as_success(token):
    # the reported bug: a successful launch must not read as a failure. isError is
    # false and the text is a plain affirmative, not raw {"launched":true}.
    adb.shell("am", "start", "-n", adb.ACTIVITY)  # foreground first (bal grace)
    time.sleep(1.0)
    r = _mcp_call(token, "mimic_launch", {"package": "com.android.settings"})
    assert r["isError"] is False, r
    text = r["content"][0]["text"].lower()
    assert "launch" in text and "no launch" not in text and "fail" not in text, text
    time.sleep(1.5)
    top = adb.top_activity()
    _mcp_call(token, "mimic_global", {"nav": "home"})
    assert "com.android.settings" in top


def test_mcp_launch_failure_is_error(token):
    r = _mcp_call(token, "mimic_launch", {"package": "com.example.nope"})
    assert r["isError"] is True
    assert "no launch intent" in r["content"][0]["text"]


def test_mcp_tap_and_global(token):
    r = _mcp_call(token, "mimic_tap", {"x": 10, "y": 10})
    assert r["isError"] is False and "perform" in r["content"][0]["text"].lower()
    r = _mcp_call(token, "mimic_global", {"nav": "home"})
    assert r["isError"] is False


def test_mcp_dump_and_find(token):
    r = _mcp_call(token, "mimic_dump", {"filter": "interactive", "format": "compact"})
    assert r["isError"] is False and isinstance(r["content"][0]["text"], str)
    r = _mcp_call(token, "mimic_find", {"query": "the", "by": "text"})
    assert r["isError"] is False


def test_mcp_packages(token):
    r = _mcp_call(token, "mimic_packages", {"query": "settings"})
    assert r["isError"] is False
    assert "com.android.settings" in r["content"][0]["text"]


def test_mcp_wait(token):
    adb.shell("am", "start", "-n", adb.ACTIVITY)
    time.sleep(1.0)
    r = _mcp_call(token, "mimic_wait", {"query": "general", "by": "text", "timeout": 5})
    assert r["isError"] is False and isinstance(r["content"][0]["text"], str)


def test_mcp_unknown_tool_is_error(token):
    r = _mcp_call(token, "mimic_nope", {})
    assert r["isError"] is True and "unknown tool" in r["content"][0]["text"]


# ---- intents surface (over adb shell am, shell uid returns results) ----

def test_intents_status(token):
    resp = adb.broadcast("STATUS")
    assert resp and resp["ok"] and resp["data"]["service_enabled"] is True


def test_intents_unauthorized(token):
    resp = adb.broadcast("DUMP")  # no token extra
    assert resp is not None and resp["ok"] is False
    assert "unauthorized" in resp["error"]


def test_intents_dump(token):
    resp = adb.broadcast("DUMP", token=token, format="tree")
    assert resp["ok"] and isinstance(resp["data"], dict)


# ---- authorization (per-token approval) -- runs last; restores approval off ----
# the suite stays non-interactive: tokens are set to allow-all so no on-device
# prompt appears. the interactive prompt (block/allow/deny/remember/grant-revoke)
# is verified by hand -- the service overlay is not visible to uiautomator.

def test_approval_allow_all_mode_bypasses(approval_on, token):
    # the session token is set to allow-all at setup, so even with approval on a
    # gated command runs without an on-device prompt.
    s, b = adb.http("POST", "/v1/packages", token, {})
    assert s == 200 and b["ok"] and isinstance(b["data"], list)


def test_auth_off_allows_no_token(token):
    # require-authentication off: a gated command runs without any token.
    adb.set_auth(False)
    try:
        s, b = adb.http("POST", "/v1/packages", "")  # no token
        assert s == 200 and b["ok"] and isinstance(b["data"], list), b
    finally:
        adb.set_auth(True)
    s, b = adb.http("POST", "/v1/packages", "")  # auth back on -> rejected
    assert s == 401


# ---- general-tab global kill switch ----
# runs last: it disables then restores every surface, so its state changes do not
# disturb the other tests.

def _http_unreachable(token):
    try:
        adb.http("POST", "/v1/status", token, timeout=3)
        return False
    except Exception:
        return True


def test_global_kill_switch(token):
    # the general-tab "enable mimic" kill switch disables every surface -- the
    # intents receiver and the http/mcp server (whose notification then clears) --
    # and restores them when turned back on.
    adb.select_tab("surfaces")
    adb.ensure_switch("intents", True)
    adb.ensure_switch("local http", True)
    adb.ensure_switch("mcp server", True)
    time.sleep(1.0)
    assert adb.broadcast("STATUS") is not None, "intents should be alive before the kill"
    s, b = adb.http("POST", "/v1/status", token)
    assert s == 200 and b["ok"] and b["data"]["http"] and b["data"]["mcp"] and b["data"]["intents"], b
    assert adb.host_notifications() > 0, "host notification absent while serving"
    # kill everything.
    adb.set_master(False)
    time.sleep(1.5)
    assert adb.broadcast("STATUS") is None, "intents receiver should be disabled after the kill"
    assert adb.host_notifications() == 0, "host notification should clear after the kill"
    assert _http_unreachable(token), "http server should be down after the kill"
    # restore: the snapshot brings the surfaces back.
    adb.set_master(True)
    time.sleep(1.5)
    assert adb.broadcast("STATUS") is not None, "intents should be restored"
    s, b = adb.http("POST", "/v1/status", token)
    assert s == 200 and b["ok"] and b["data"]["http"] and b["data"]["mcp"], b
