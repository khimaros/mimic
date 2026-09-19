# requirements

product requirements for the mimic service. it is never
okay to regress on these in a release.

## scope

an android app that registers as an accessibility service and exposes a small set
of capabilities to external automation, over one or more local surfaces:

- **view**: read the accessibility node tree of the active window(s).
- **interact**: drive the ui (tap, long press, swipe, click, set text, and the
  global navigation actions back/home/recents/notifications).
- **launch**: start an app or activity (by package, component, or action/uri).
- **list**: enumerate launchable apps (package, label, launcher component).
- **capture**: take a screenshot (a last resort; the text tree is preferred).

nothing else: no notification reading, and no contact, phone, or storage access.
the one networking facility is a local http server, bound to loopback (127.0.0.1)
by default so data stays on-device; the user may opt to bind a chosen network
interface (or all interfaces) to reach it from another machine, still gated by the
token. the permission surface stays as small as the accessibility framework and
that local server require.

## R1 view

- R1.1 return the node tree of the active window as machine-readable json.
- R1.2 each node carries at least: a role/class, text, content-description,
  resource-id, screen bounds, center coordinates, and which actions it supports
  (clickable, long-clickable, editable, scrollable, focusable, checkable).
- R1.2a a node that supports an action but carries no text or content-description
  of its own takes a label from its descendants, so an actionable row is never
  anonymous -- android's dominant pattern puts a row's name in child text views
  of a clickable container, and a dump of unnamed containers is one an agent
  cannot choose from. the node's own text/content-description are never
  overwritten, so a borrowed label stays distinguishable from a real one.
- R1.3 support server-side filtering to reduce returned size: interactive-only,
  text-bearing-only, visible-only, depth limit, and package scope. filtering
  happens on the device so the wire payload stays small. filters combine and all
  must pass, so one dump can ask for what is interactive *and* visible without a
  second dump to join against. interactive means the node carries an action the
  service can perform; focusability alone does not make it a target.
- R1.4 support server-side query: find nodes by text, resource-id, class, or
  content-description, with exact, contains, or regex matching.
- R1.5 support output shaping: tree vs flat vs compact form, and selection of
  which fields are included. field selection applies to every format -- in
  compact the chosen fields are the columns, in the order asked for -- so the
  cheapest format can still carry whichever attribute the caller needs.
- R1.6 support waiting for a node matching a query to appear in the active window,
  polling server-side with a configurable timeout and a sensible default; return
  the matches when found, or a clear timeout failure. (preferable to a client-side
  sleep.) the timeout is capped under the broadcast window on the intents surface.

## R2 interact

- R2.1 tap, long press, and swipe by absolute screen coordinates.
- R2.2 click a node located by text, resource-id, or coordinates.
- R2.3 set the text of an editable node located by text or resource-id; with no
  target given, set the text of the node that currently holds input focus.
- R2.4 perform global navigation: back, home, recents, notifications.
- R2.5 interaction is stateless: a target is re-resolved on every call from
  coordinates, text, or resource-id. no reliance on ephemeral node ids that go
  stale when the screen changes.
- R2.6 launch an app or activity by package, explicit component, or action/uri.
  this is subject to android background-activity-launch rules. optionally block
  until the launched app owns the active window, reporting whether it reached the
  foreground within the timeout.
- R2.7 capture a screenshot (png/jpeg, optional downscale) via the accessibility
  framework. http returns raw image bytes, mcp an image content block, intents
  base64; the cli writes it to a file. it is a last resort relative to the tree,
  and is rate-limited by android to about one per second.
- R2.8 list launchable apps as {package, label, launcher component}, optionally
  filtered by a substring of either; the component is ready to pass to launch.
  only apps visible through the manifest queries are returned, so no broad
  package-visibility permission is required.
- R2.9 scroll the active window in a direction (up/down/left/right, naming the
  content reveal). optionally keep scrolling until a node matching a query is
  visible on screen (the tree may hold off-screen rows, so the match is restricted
  to visible nodes), returning the on-screen matches, and stopping at a timeout, a
  scroll cap, or when a scroll no longer changes the screen (the end of the
  content). a match already on screen satisfies it immediately (scroll-into-view),
  with an option to instead skip the already-visible matches and stop on the next
  occurrence. without a query, perform a bounded number of scrolls. the timeout is
  capped under the broadcast window on the intents surface.

## R3 surfaces and protocol

- R3.1 the same view/interact command core is reachable over three independently
  toggleable surfaces:
  - **intents**: ordered broadcast intents invoked with `am`/`termux-am`/adb
    `broadcast`; results returned in the broadcast result data as base64-json.
  - **http**: a token-gated rest api (`/v1/<cmd>`), json in/out.
  - **mcp**: an in-app model context protocol server (streamable http, json-rpc)
    on the same port (`/mcp`), exposing the commands as mcp tools.
- R3.1a the http/mcp server binds loopback (127.0.0.1) by default. the user may
  choose another interface address, or all interfaces (0.0.0.0), to reach it from
  another machine; the server rebinds on change and the token still gates every
  request. action results returned to mcp clients are unambiguous: a clear
  affirmative on success, the error text with isError on failure.
- R3.2 the intents result must survive `am`/`termux-am` output parsing and need
  no storage permission; the localhost surfaces return plain json.
- R3.3 every command reports a clear status and error message on failure
  (service not enabled, bad token, target not found, bad arguments).
- R3.4 because `am` from a non-shell uid cannot return a result, the cli must
  also work over the localhost http surface (reachable from proot, native
  termux, or the host via `adb forward`) and over adb when connected.

## R4 authentication and pairing

- R4.1 the accessibility service must be enabled by the user in system settings.
  this is the first, non-bypassable gate.
- R4.2 every command on every surface must carry a secret token. requests without
  a valid token are rejected, so other apps on the device cannot read the screen
  or inject input (the localhost socket is reachable by any local app).
- R4.3 tokens are per-client: each client holds its own secret, so one can be
  revoked without disturbing the others. a client obtains a token two ways:
  - **pairing**: the user taps "start pairing" in the app, which opens an
    explicit, time-limited window and shows a one-time, attempt-limited 6-digit
    code. the client exchanges the code -- over whichever surface is reachable,
    including http -- for a freshly minted token. the code is valid only inside an
    open window and only once; outside a window every code is rejected.
  - **legacy token**: for clients that cannot run the handshake (mcp config and
    the like), the app can reveal a long-lived token to paste in by hand.
- R4.4 tokens are stored only in app-private storage (unreadable by other apps
  without root) and compared in constant time against every issued token.
- R4.5 the user can revoke a specific client, or all of them, from the app; a
  revoked client is rejected immediately while the rest keep working. minting a
  token outside pairing (the legacy token) and all revocation are gui-only, so
  they require physical access to the device -- a remote client cannot mint extra
  tokens or revoke peers.

## R5 clients

- R5.1 a self-contained posix shell cli (`mimic`) usable from termux with no extra
  runtime. it auto-selects a working transport (localhost http, else adb, else
  termux-am/am) and stores the token mode 600.
- R5.2 a `SKILL.md` documenting the surfaces, the cli, and mcp client config, with
  explicit guidance on using filtering/query to minimize context for agents.
- R5.3 the http surface serves the cli and `SKILL.md` (unauthenticated) so a
  client can bootstrap (`curl .../cli/mimic`) or self-update (`mimic update`); the
  served copies are bundled from the repo at build time so they match the source.

## R6 build and test

- R6.1 the project builds with `make` (gradle under the hood).
- R6.2 end-to-end tests in python, run with `make test-e2e`, drive a real device
  or emulator over adb and skip gracefully when none is attached.
- R6.3 `make precommit` runs the checks expected before a commit.

## R7 app lifecycle and ui

- R7.1 a simple, dark onboarding screen walks the user through the steps that
  only a human can do: enable the accessibility service, pair a client or reveal a
  legacy token, choose the bind interface, and turn on the surfaces.
- R7.2 each surface (intents, http, mcp) has its own independent on/off toggle
  and acts as a local kill-switch. stopping the intents surface disables the
  receiver component outright; stopping http/mcp stops the localhost server. a
  fresh install has every surface off.
- R7.3 an opt-in "start on boot" restores the surfaces after a reboot.
- R7.4 the ui lists active clients (label, id, kind) and updates live as clients
  pair; it copies the code and token to the clipboard when shown and the bind
  address on demand, so the common path needs no manual selection.
- R7.5 the bind interface is chosen in the ui from loopback, each detected lan
  address, or all interfaces; a non-loopback choice is flagged as network-exposed.
- R7.6 the general tab offers a global kill switch that disables every surface at
  once (the intents receiver and the http/mcp server) and restores them when turned
  back on; it is kept in sync with the per-surface toggles. while the server runs it
  posts an ongoing notification that opens the app when tapped; the notification
  clears when the server stops or no surface is enabled.

## R8 authorization (fine-grained, per-token)

- R8.1 an optional global "fine-grained permissions" mode, off by default (the
  token is then the only gate). when on, every command is authorized per token
  before it runs.
- R8.2 authorization is keyed by (token, action class, target app). classes:
  read (dump/find/wait), interact (tap/long-press/swipe/click/scroll/global), type
  (set-text), launch, screenshot, packages; status and pair are exempt. the target
  is the launched app for launch, the foreground app for reads/input/screenshot,
  and all apps for the package listing.
- R8.3 each token has a mode: ask (prompt on an unknown class/app) or allow-all
  (never prompt). every client defaults to ask; a headless client that cannot
  answer a prompt is set to allow-all from its row in the ui.
- R8.4 on an unknown (class, app) the user is prompted over the foreground app
  (allow or deny, with remember scope: once, this app, or all apps). the request
  blocks until the answer or a per-surface timeout (the localhost surfaces wait
  long enough for a human; intents is capped under the broadcast window), then
  returns the real result or, on timeout, a clear permission_required for retry.
- R8.5 the prompt is drawn as an accessibility overlay (needs no extra
  permission); if the optional "draw over other apps" permission is granted, a
  more robust application overlay is used instead.
- R8.6 grants are stored per token and revocable individually in the ui (and drop
  with the token when it is revoked). the ui lists each client's grants and mode.
  remembering and revoking grants happen at the device (prompt or ui), never from
  a remote client.
