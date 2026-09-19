package com.khimaros.mimic

// the intent protocol: action names and extra keys shared by the receiver and
// (by documentation) the cli. keeping them in one place is the single source of
// truth for SKILL.md and the e2e tests.

// command short names. these are the surface-agnostic identifiers used by the
// Commands core, the rest paths (/v1/<cmd>), and the mcp tool names. the intent
// surface prefixes them to form action strings.
object Cmd {
    const val DUMP = "DUMP"
    const val FIND = "FIND"
    const val TAP = "TAP"
    const val LONG_PRESS = "LONG_PRESS"
    const val SWIPE = "SWIPE"
    const val CLICK = "CLICK"
    const val SET_TEXT = "SET_TEXT"
    const val GLOBAL = "GLOBAL"
    const val SCROLL = "SCROLL"
    const val WAIT = "WAIT"
    const val LAUNCH = "LAUNCH"
    const val SCREENSHOT = "SCREENSHOT"
    const val STATUS = "STATUS"
    const val PACKAGES = "PACKAGES"
    const val PAIR = "PAIR"
}

// the authorization class of each command. medium granularity: typing and screen
// capture are split from generic input because they are more sensitive. status and
// pair are exempt (no class) -- a client needs them to bootstrap and check state.
object ActionClass {
    const val READ = "read"
    const val INTERACT = "interact"
    const val TYPE = "type"
    const val LAUNCH = "launch"
    const val SCREENSHOT = "screenshot"
    const val PACKAGES = "packages"

    private val MAP = mapOf(
        Cmd.DUMP to READ, Cmd.FIND to READ, Cmd.WAIT to READ,
        Cmd.TAP to INTERACT, Cmd.LONG_PRESS to INTERACT, Cmd.SWIPE to INTERACT,
        Cmd.CLICK to INTERACT, Cmd.GLOBAL to INTERACT, Cmd.SCROLL to INTERACT,
        Cmd.SET_TEXT to TYPE,
        Cmd.LAUNCH to LAUNCH,
        Cmd.SCREENSHOT to SCREENSHOT,
        Cmd.PACKAGES to PACKAGES,
    )

    // null means the command is exempt from authorization.
    fun of(cmd: String): String? = MAP[cmd]

    // a human verb for the prompt ("<client> wants to <verb> <app>").
    fun verb(cls: String): String = when (cls) {
        READ -> "read the screen of"
        INTERACT -> "control"
        TYPE -> "type into"
        LAUNCH -> "launch"
        SCREENSHOT -> "screenshot"
        PACKAGES -> "list installed apps"
        else -> cls
    }
}

object Actions {
    const val PREFIX = "com.khimaros.mimic.action."

    const val DUMP = PREFIX + Cmd.DUMP
    const val FIND = PREFIX + Cmd.FIND
    const val TAP = PREFIX + Cmd.TAP
    const val LONG_PRESS = PREFIX + Cmd.LONG_PRESS
    const val SWIPE = PREFIX + Cmd.SWIPE
    const val CLICK = PREFIX + Cmd.CLICK
    const val SET_TEXT = PREFIX + Cmd.SET_TEXT
    const val GLOBAL = PREFIX + Cmd.GLOBAL
    const val SCROLL = PREFIX + Cmd.SCROLL
    const val LAUNCH = PREFIX + Cmd.LAUNCH
    const val PAIR = PREFIX + Cmd.PAIR
    const val STATUS = PREFIX + Cmd.STATUS

    fun shortName(action: String?): String = (action ?: "").removePrefix(PREFIX)
}

// the localhost host surface (rest + mcp) binds loopback only.
object Host {
    const val ADDR = "127.0.0.1"
    const val PORT = 8473
    const val TOKEN_HEADER = "x-mimic-token"
    const val MCP_PROTOCOL = "2025-06-18"
    const val SERVER_NAME = "mimic"

    // single source of truth: app/build.gradle.kts defaultConfig.versionName.
    val VERSION: String = BuildConfig.VERSION_NAME
}

object Extras {
    const val TOKEN = "token"

    // view shaping
    const val FORMAT = "format"        // tree | flat | compact
    const val FILTER = "filter"        // interactive | text | visible | all (comma-combinable, ANDed)
    const val MAX_DEPTH = "max_depth"
    const val PACKAGE = "package"
    const val FIELDS = "fields"        // comma-separated subset of NODE_FIELDS

    // query (FIND, and target of CLICK / SET_TEXT)
    const val BY = "by"                // text | id | class | desc
    const val QUERY = "query"
    const val MATCH = "match"          // exact | contains | regex

    // wait (and launch --wait): poll until present / foreground
    const val TIMEOUT = "timeout"      // seconds (float)
    const val WAIT = "wait"            // launch: block until the app is foreground

    // scroll: a swipe in a direction, optionally repeated until a query matches
    const val DIRECTION = "direction"  // up | down | left | right (content reveal)
    const val STEPS = "steps"          // scroll cap (with query) / count (without)
    const val SKIP_VISIBLE = "skip_visible"  // ignore matches already on screen

    // gestures
    const val X = "x"
    const val Y = "y"
    const val X2 = "x2"
    const val Y2 = "y2"
    const val DURATION = "duration"

    // misc
    const val TEXT = "text"            // SET_TEXT payload
    const val NAV = "nav"              // GLOBAL: back | home | recents | notifications
    const val CODE = "code"            // PAIR: 6-digit one-time pairing code
    const val LABEL = "label"          // PAIR: optional client label (e.g. hostname)
    const val ID = "id"                // a token's short handle (revoke/identify)
    const val KIND = "kind"            // a token's kind: paired | legacy

    // PACKAGES
    const val FUZZY = "fuzzy"           // approximate (edit-distance) match

    // LAUNCH: at least one of these
    const val COMPONENT = "component"  // "pkg/.Activity"
    const val ACTION = "action"        // an intent action
    const val URI = "uri"              // data uri (with action, or ACTION_VIEW)

    // SCREENSHOT: format reuses FORMAT (png | jpeg)
    const val QUALITY = "quality"      // jpeg quality 1-100
    const val SCALE = "scale"          // downscale factor 0-1
}

object Defaults {
    const val FORMAT = "tree"
    const val FILTER = "all"
    const val MATCH = "contains"
    const val MAX_DEPTH = -1           // unlimited
    const val TAP_DURATION_MS = 50L
    const val LONG_PRESS_DURATION_MS = 600L
    const val SWIPE_DURATION_MS = 300L
    // kept under the ~10s broadcast-receiver dispatch window so a slow gesture
    // cannot hang the ordered broadcast and stall `am broadcast`.
    const val GESTURE_TIMEOUT_MS = 8_000L
    const val SCREENSHOT_TIMEOUT_MS = 5_000L

    // wait / launch --wait: poll the active window until a match (or the app comes
    // to the foreground), or the timeout. capped so a long wait cannot exceed the
    // intents broadcast window (the receiver clamps to WAIT_INTENTS_MAX_S); the
    // localhost surfaces allow up to WAIT_MAX_MS.
    const val WAIT_TIMEOUT_S = 10.0
    const val WAIT_POLL_MS = 300L
    const val WAIT_MAX_MS = 60_000L
    const val WAIT_INTENTS_MAX_S = 8.0

    // scroll: each drag spans this fraction of the screen, centered, so it stays
    // clear of the edge gestures (back/notification) and -- being well under one
    // viewport -- leaves consecutive screens overlapping, so no row is skipped. the
    // drag ends with a brief HOLD (finger still before lifting) so it releases at
    // ~zero velocity and the list does not fling past content; because the hold,
    // not the speed, suppresses the fling, the drag itself can be quick. in a
    // scroll-until-found we let each step settle, then treat an unchanged screen as
    // the end; the search is also capped at MAX_STEPS so it cannot loop forever.
    const val SCROLL_FRACTION = 0.5
    const val SCROLL_DURATION_MS = 250L
    const val SCROLL_HOLD_MS = 130L
    const val SCROLL_SETTLE_MS = 300L
    // a scroll-until-found waits up to this long for the tree to reflect a drag
    // before concluding the content did not move (the end). polling adapts to the
    // device's actual settle time -- a fixed delay is too short under load.
    const val SCROLL_CHANGE_WINDOW_MS = 1_200L
    const val SCROLL_MAX_STEPS = 30
    const val SCREENSHOT_QUALITY = 90
    const val SCREENSHOT_FORMAT = "png"

    // pairing: an explicit "start pairing" opens a time-boxed window; a code is
    // valid only inside it, only once, and only for a bounded number of attempts.
    const val PAIRING_WINDOW_MS = 2 * 60_000L
    const val CODE_ATTEMPTS = 5
    const val TOKEN_BYTES = 24         // per-client secret
    const val TOKEN_ID_BYTES = 3       // short handle, 6 hex chars

    // a token's kind, recorded for display and provenance.
    const val KIND_PAIRED = "paired"   // minted by redeeming a one-time code
    const val KIND_LEGACY = "legacy"   // minted in the gui for manual config

    // authorization: per-token mode and how long a request waits for an approval
    // prompt before giving up (kept under the intents broadcast window so `am`
    // does not anr; longer for the localhost surfaces a client holds open).
    const val MODE_ASK = "ask"             // prompt on unknown (class, app)
    const val MODE_ALLOW_ALL = "allow_all" // never prompt (headless clients)
    // the localhost surfaces hold the request open long enough for a human to
    // answer the prompt, so the one call returns the real allow/deny result.
    // intents is capped under the ~10s broadcast window to avoid an anr.
    const val PROMPT_TIMEOUT_HTTP_MS = 120_000L
    const val PROMPT_TIMEOUT_INTENTS_MS = 8_000L
}

// the full set of serializable node attributes; the caller may request a subset
// via the `fields` extra. "children" is only meaningful in tree format. "label"
// is the node's name -- its own text/desc, else the text borrowed from its
// descendants when it is something you can act on.
val NODE_FIELDS = listOf(
    "class", "text", "desc", "label", "id", "bounds", "center", "actions", "children",
)

// the compact columns when the caller does not choose `fields`:
// "cx,cy<TAB>class<TAB>label<TAB>id" -- everything needed to tap, nothing else.
val COMPACT_FIELDS = listOf("center", "class", "label", "id")
