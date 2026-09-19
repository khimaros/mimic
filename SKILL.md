---
name: mimic
description: Drive an android device from a shell with the `mimic` cli -- read the on-screen accessibility tree (filtered/queried on-device to stay small) and tap, swipe, click, type, scroll, and navigate. Use for ui automation on a connected/termux android device. Covers setup, every subcommand, and how to keep agent context small.
---

# mimic cli

`mimic` is a shell command that reads and acts on an android device's
accessibility tree: dump/query the ui, then tap, swipe, click, type, scroll, and
navigate. it talks to the **mimic** app over whatever transport is
available and auto-selects one, so you just run `mimic <command>`.

## setup (one time)

1. install and open the **mimic** app; enable its accessibility
   service when prompted.
2. turn on at least one surface (the **local http** surface is the most portable).
3. tap **start pairing** in the app, then pair the cli with the shown code:
   ```
   mimic pair 491431     # one-time code; pairs over http or intents
   ```
   this mints a per-client token saved at `~/.config/mimic/token` and used
   automatically. (for a client that cannot pair, tap **reveal legacy token** and
   `mimic set-token <token>`.) the code is one-time and only valid while the
   pairing window is open.

check it works:
```
mimic status            # -> {"ok":true,"data":{"service_enabled":true,...}}
```

## output (json)

every command prints a json envelope on stdout and exits nonzero on error:

```
{"ok": true, "data": <payload>}
{"ok": false, "error": "..."}
```

parse it as json -- that is the intended interface, and it needs no extra tools.
`data` is the payload: an object/array for tree/flat dumps and status, a string
for `--format compact` (newline-joined lines; json-escaped, so a parser restores
the tabs/newlines), and `{"performed":true}` for actions.

don't have the cli yet? with the **local http** surface on, the server serves it
(no token needed):
```
curl -s http://127.0.0.1:8473/cli/mimic -o mimic && chmod +x mimic
```
later, `mimic update` re-downloads the latest from the same server.

the server also serves this skill doc, so you can refresh it to match the
installed app version (also no token needed) -- write it over your local copy:
```
curl -s http://127.0.0.1:8473/SKILL.md -o SKILL.md
```

## keep context small (do this for agent use)

the full tree is large. narrow it **on the device** instead of dumping
everything and filtering locally:

- **reach for `find` first.** you usually know what you want:
  ```
  mimic find "sign in"
  ```
- **filter when you must dump:** `--filter interactive` keeps nodes carrying an
  action (click, long-click, edit, scroll, check); `--filter text` keeps
  text-bearing nodes; `--filter visible` keeps what is on screen. **combine them
  with a comma** -- every one must pass:
  ```
  mimic dump --filter interactive,visible --format compact
  ```
  that is the survey to reach for: what is on screen *and* actionable. without
  `visible` the tree also holds rows below the fold, whose coordinates are
  off-screen (often negative) and never meaningful to tap.
- **use the compact format:** `data` becomes a single newline-joined string of
  `cx,cy<TAB>class<TAB>label<TAB>id` lines -- the cheapest representation, and the
  leading `cx,cy` is exactly where to tap.
- **trim further:** `--fields center,label,id`, `--max-depth N`, `--package PKG`.
  in compact, `--fields` picks the columns and their order, so
  `--fields center,label,actions` tells you where to tap, what it is called, and
  what it supports.

### labels come from the row, not just the node

android's dominant pattern puts a row's name in child `TextView`s of a clickable
container, so mimic gives a node with an action but no text of its own the text of
its descendants. a settings screen reads as:

```
504,439	LinearLayout	Network & internet / Mobile, Wi-Fi, hotspot
504,608	LinearLayout	Connected devices / Bluetooth, pairing
```

rather than a column of unnamed `LinearLayout`s. the title and its summary are
joined with ` / `, since the summary is often what tells two rows apart. in json,
`text`/`desc` stay the node's own and `label` appears only when it was borrowed --
so a node with `text` named itself, and a node with only `label` took it from its
children.

to act on a row, tap its `cx,cy` -- that is what the first column is for. a
joined label is not a queryable string (no single node holds it), so
`click --text` wants a substring that one node really has:

```
mimic click --text "Network & internet"     # the title: a real node's text
mimic tap 504 439                           # or just the coordinates
```

`click` walks up from the matched node to the nearest clickable ancestor, so
clicking the title hits the row.

## commands

```
mimic status                          service enabled? which surfaces are on?

view (filtering/query run on-device):
  mimic dump [--filter interactive|text|visible|all]   comma-combine to require
                                                       several: interactive,visible
            [--format tree|flat|compact]
            [--max-depth N] [--package PKG]
            [--fields class,text,desc,label,id,bounds,center,actions]
                                                       in compact these are the
                                                       columns, in the order given
  mimic find QUERY [--by text|id|class|desc]
                  [--match exact|contains|regex]
                  [+ any dump option]
  mimic wait QUERY [--by ...] [--match ...] [--timeout SECONDS] [--package P]
                  poll the active window until a node matches; returns the matches,
                  or fails on timeout (default 10s). prefer this over sleeping.
                  long waits need the http surface (intents is capped ~8s).

interact (stateless -- targets re-resolve every call):
  mimic tap        X Y [--duration MS]
  mimic longpress  X Y [--duration MS]
  mimic swipe      X1 Y1 X2 Y2 [--duration MS]
  mimic click      X Y | --id ID | --text T | --class C | --desc D [--match M]
  mimic text       VALUE [--id ID | --text T | --class C | --desc D] [--match M]
                   (no target -> types into the currently focused field)
  mimic scroll     up|down|left|right [QUERY] [--match M] [--timeout S] [--steps N]
                            [--skip-visible]
                   with QUERY: scroll until a node matching it is on screen, then
                   return the match (already-visible -> returns at once; this is
                   scroll-into-view). it matches only visible nodes -- scrolling
                   past off-screen tree rows -- and stops at the end of the content
                   or the timeout. --skip-visible ignores matches already shown and
                   finds the next occurrence in the scroll direction (e.g. an
                   earlier chat message). without a QUERY: one scroll (or --steps
                   N). direction is the content reveal: down reveals lower content,
                   right reveals further right.
  mimic back | home | recents | notifications

discover and launch apps:
  mimic packages [QUERY] [--fuzzy]      list launchable apps as {package,label,component};
                                        --fuzzy tolerates typos (edit distance)
  mimic launch PACKAGE [--wait]         launch an app; --wait blocks until it is foreground
  mimic launch --component PKG/.ACT     launch an explicit activity (e.g. from packages)
  mimic launch --action ACTION [--uri URI] [--package PKG]
  mimic launch --uri URI                open a uri (ACTION_VIEW)
                  (launch --wait [--timeout S] returns {"launched":true,"foreground":bool})

screenshot (LAST RESORT -- see below):
  mimic screenshot [PATH] [--format png|jpeg] [--quality 1-100] [--scale 0-1]

setup (tap "start pairing" in the app first):
  mimic pair [CODE] [LABEL]             exchange a one-time code for a per-client token
  mimic set-token [TOKEN]               save a legacy token revealed in the app
  mimic update [DEST]                   re-download this script from the server
```

## screenshot is a last resort

prefer the text tree (`dump`/`find`) for almost everything -- it is far cheaper
in tokens, gives you exact tap coordinates and ids, and is reliable. only reach
for `screenshot` when the tree is genuinely insufficient: canvas/`SurfaceView`
content, webview/game pixels, images, or visual state the accessibility tree does
not expose. `mimic screenshot` writes a png to a unique `/tmp` file (or `PATH`) and
prints the path; pass `--scale 0.5` / `--format jpeg` to shrink it. it is
rate-limited by android to about one per second.

note: `launch` is subject to android background-activity-launch rules -- it is
reliable when the device is unlocked and the app was recently foreground; a
purely background launch may be blocked by the system.

output is the json envelope on stdout (see "output (json)" above); a failed
command prints its error to stderr and exits nonzero.

## recipes

```
# what can i interact with on this screen?
mimic dump --filter interactive,visible --format compact

# the same, plus what each row supports (click? scroll?)
mimic dump --filter interactive,visible --format compact --fields center,label,actions

# log in
mimic click --id com.example.app:id/username
mimic text "alice" --id com.example.app:id/username
mimic text "secret" --id com.example.app:id/password
mimic click --text "log in" --match contains

# scroll to a control, then re-survey
mimic scroll down "battery"               # keep scrolling until "battery" appears
mimic find EditText --by class            # the editable fields now on screen

# find by regex, then tap its coordinates from the compact line
mimic find "^\\$[0-9]+" --match regex --format compact
mimic tap 712 980

# open an app, then a url
mimic launch com.android.settings
mimic launch --uri https://example.com
```

## transports (usually automatic)

the cli auto-selects, best first: the localhost **http** surface if it answers,
else **adb** if a device is connected (works over usb or wireless debugging),
else **termux-am**, else bare **am**. override if needed:

- `MIMIC_TRANSPORT=http|intents`  force a transport.
- `MIMIC_HOST=host:port`          http endpoint (default `127.0.0.1:8473`).
- `MIMIC_AM="adb shell am"`       a specific `am` for the intents transport.
- `MIMIC_HOME=/path`              token directory (default `~/.config/mimic`).

note: plain `am` run as a normal app (termux, or termux inside proot) can send a
command but cannot return its result -- if reads come back empty, turn on the
app's **http** surface and the cli will use it.

## troubleshooting

- `no token`: run `mimic pair` or `mimic set-token`.
- `unauthorized`: the token is stale; re-reveal it in the app and re-pair.
- `no response`: the selected surface is off, or on-device `am` cannot return a
  result -- enable the http surface in the app.
- `accessibility service not enabled`: enable it in accessibility settings.
- `no node matched query`: widen the query or `--match contains`; confirm with
  `mimic find`.
- `permission_required`: the device has "fine-grained permissions" on and a prompt
  is waiting on screen. the command blocks until the user answers; if it times out
  first you get this -- the user approves the prompt (optionally "remember"), then
  run the command again. `permission_denied` means the user (or a saved rule)
  rejected it; do not retry blindly.

## mcp client config

the app hosts an mcp server, so an agent can use the same view/interact commands
as mcp tools without the cli. point any mcp client at the **streamable http**
endpoint and send the auth header:

- transport / type: `http` (streamable http; some clients call it `streamable-http`)
- url: `http://127.0.0.1:8473/mcp`
- headers: `x-mimic-token: <token>`

most clients take a json entry; the exact keys vary by client, but the shape is:

```json
{
  "mcpServers": {
    "mimic": {
      "type": "http",
      "url": "http://127.0.0.1:8473/mcp",
      "headers": { "x-mimic-token": "PASTE_TOKEN" }
    }
  }
}
```

if the client runs on a different host than the phone, forward the port with
`adb forward tcp:8473 tcp:8473`, or set the bind interface to a lan address /
0.0.0.0 in the app and use the device ip (the app shows and copies the address).
the tools are `mimic_dump`, `mimic_find`, `mimic_wait`, `mimic_tap`,
`mimic_long_press`, `mimic_swipe`, `mimic_scroll`, `mimic_click`, `mimic_set_text`,
`mimic_global`, `mimic_packages`, `mimic_launch`, `mimic_status`; their arguments
mirror the cli flags. action tools answer with a plain success message and an
`isError` flag, not a raw json blob.

the rest api and raw intent protocol are documented in [DESIGN.md](DESIGN.md).
