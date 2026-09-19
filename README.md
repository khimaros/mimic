# mimic

an android accessibility service that exposes **view** (read the on-screen
accessibility tree) and **interact** (tap, swipe, click, type, scroll, navigate)
to local automation over three independent, token-gated surfaces:

- **intents** -- broadcast intents driven by `am` / `termux-am` / `adb`.
- **http** -- a rest api on `127.0.0.1` for `curl` and the `mimic` cli.
- **mcp** -- an in-app model context protocol server on the same localhost port,
  for agents (claude code/desktop and other mcp clients).

## what it gives you

- read the active window's accessibility tree as json, with per-node bounds,
  tap coordinates, and supported actions.
- filter and query the tree **on the device** (interactive-only, text-only,
  visible-only -- combinable, so `interactive,visible` is one dump of what is on
  screen and actionable -- plus by-text/id/class/desc and regex) so an agent
  sends and receives the minimum context.
- name the rows: a clickable container with no text of its own takes its label
  from its child `TextView`s, so a screen reads as
  `Network & internet / Mobile, Wi-Fi, hotspot` rather than a list of unnamed
  layouts.
- tap / long-press / swipe by coordinate; click or set-text on a node found by
  text or resource-id; scroll until a node appears; back / home / recents /
  notifications.
- launch an app or activity by package, component, or action/uri.
- capture a screenshot (a last resort; the text tree is preferred and far cheaper).
- a `mimic` shell cli that works from termux/proot/host, and a `SKILL.md`.

## requirements

- android 8.0+ (api 26+).
- the android sdk to build (see below). `adb` to install and to run e2e tests.

## build

```
make            # assemble the debug apk
make install    # install onto a connected device/emulator (adb)
make release    # assemble the release apk (env-gated signing; see CONTRIBUTING)
```

the build needs an android sdk. `mise` pins the toolchain; point the build at
your sdk with `local.properties` (`sdk.dir=/path/to/Android/sdk`) or the
`ANDROID_HOME` environment variable. see [CONTRIBUTING.md](CONTRIBUTING.md).

## set up on the device

the app opens to a small dark onboarding screen:

1. install the apk (`make install`).
2. open **mimic** and enable the accessibility service when it
   deep-links you to settings.
3. turn on the surfaces you want on the **surfaces** tab (**intents**, **local
   http**, **mcp**) and choose the bind interface. optionally enable **restart
   surfaces on boot**. while the http/mcp server runs a notification stays up (tap
   it to reopen the app). the **general** tab has a global **enable mimic** kill
   switch at the bottom that disables every surface at once (and restores them).

then connect a client. each client gets its own token, listed in the app and
revocable on its own:

- **cli (pairing)**: tap **start pairing** in the app, then in termux run
  `mimic pair <code>` with the 6-digit code. the code is one-time and valid only
  while the window is open; pairing works over http too, so it succeeds from
  proot/native termux where `am` cannot return a result. the minted token is saved
  at `~/.config/mimic/token`. don't have the cli yet? with the http surface on, the
  server hands it out (no token needed):
  `curl -s http://127.0.0.1:8473/cli/mimic -o mimic && chmod +x mimic` (and later
  `mimic update`).
- **mcp client**: tap **reveal legacy token** in the app, then point the client at
  `http://127.0.0.1:8473/mcp` with header `x-mimic-token: <token>` (from a host,
  first `adb forward tcp:8473 tcp:8473`). `mimic set-token <token>` saves the same
  token for the cli.

revoke a client (or all of them) from the app's **paired clients** list at any
time; minting outside pairing and revocation are gui-only, so they need physical
access to the phone.

## use it

```
mimic status                       # service enabled? which surfaces on?
mimic dump --filter interactive    # actionable nodes only, as json
mimic dump --filter interactive,visible --format compact   # the on-screen element table
mimic find login --by text         # nodes whose text contains "login"
mimic tap 540 1200                 # tap a coordinate
mimic click --id com.app:id/submit # click a node by resource-id
mimic text "hello" --id com.app:id/search
mimic text "hello"                 # no target -> the focused field
mimic back                         # global navigation
mimic scroll down "battery"        # scroll until a "battery" node appears
mimic wait Login --by text         # block until a "Login" node appears (default 10s)
mimic packages settings --fuzzy    # launchable apps matching "settings" (typo-tolerant)
mimic launch com.android.settings --wait  # launch and wait until it is foreground
mimic screenshot                   # capture screen -> /tmp file (last resort)
```

every command prints a json envelope (`{"ok":...,"data":...}`) and exits nonzero
on error -- no tools required. for human-friendly output at a terminal, prefix
any command with `--pretty`, which unwraps and formats the payload (a compact
dump becomes tab-separated lines). `--pretty` requires `jq` and errors clearly if
it is missing:

```
mimic --pretty dump --filter interactive,visible --format compact
```

see [SKILL.md](SKILL.md) for the full `mimic` cli reference and guidance for
agents on keeping context small; the rest/mcp/intent protocols behind it are in
[DESIGN.md](DESIGN.md).

## security

two independent gates protect the device: the accessibility service must be
enabled by you in settings, and every command on every surface must carry a valid
token. tokens are per-client and stored in app-private storage (unreadable by
other apps without root). revoke a single client by its id, or **revoke all**, from
the app; a revoked client is rejected immediately while the rest keep working.

a token is only mintable from a one-time code during an open pairing window, or in
the app itself (the legacy token) -- both need physical access, so a remote client
cannot mint extra tokens or revoke peers. the localhost socket is reachable by any
local app and the intents receiver is exported, so the token is what makes
unauthorized attempts fail. treat it as a device secret, and turn off surfaces you
are not using.

the http/mcp server binds loopback (127.0.0.1) by default, so it is reachable only
on-device (or via `adb forward`). choosing a lan address or all interfaces in the
app exposes it to your network -- still token-gated, but a larger attack surface;
the app flags the choice.

for tighter control, turn on **enable fine-grained permissions**. each client
(token) is then authorized per action class (read, interact, type, launch,
screenshot, packages) and target app: the first time a client tries something new, a
prompt appears over the foreground app ("`<client>` wants to tap in `<app>`") with
allow, deny, and a remember scope (once / this app / all apps). the request waits
for your answer. every client defaults to asking; set a headless client that cannot
answer a prompt to allow-all from its row in the app. each client's grants and mode
are listed there and revocable individually. the prompt uses an accessibility
overlay; granting the optional "draw over other apps" permission makes it more
robust.

## license

see [LICENSE](LICENSE).
