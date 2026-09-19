package com.khimaros.mimic

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

// the surface-agnostic command core. the intent receiver, the rest endpoint, and
// the mcp endpoint all parse their own transport, then call run() with a uniform
// string-keyed getter. it returns a structured Result; each surface formats and
// transports it. authentication is handled by the transports, not here.
object Commands {

    data class Result(val ok: Boolean, val data: Any?, val error: String?) {
        // binary payloads (screenshot bytes) are base64-encoded for json consumers;
        // transports that can send bytes directly (http) check for ByteArray first.
        fun toJson(): JSONObject = JSONObject().put("ok", ok).also { o ->
            when (val d = data) {
                null -> {}
                is ByteArray -> o.put("data", Base64.encodeToString(d, Base64.NO_WRAP))
                else -> o.put("data", d)
            }
            error?.let { o.put("error", it) }
        }
    }

    private fun ok(data: Any?) = Result(true, data, null)
    private fun fail(message: String) = Result(false, null, message)

    // run a short-named command (see Cmd). `get` resolves named arguments to
    // strings (intent extras, query params, json fields). throws nothing -- bad
    // arguments come back as a failed Result.
    fun run(ctx: Context, action: String, get: (String) -> String?): Result = try {
        when (action) {
            Cmd.STATUS -> ok(status(ctx))
            Cmd.PACKAGES -> packages(ctx, get)   // package listing does not need the mimic service
            Cmd.LAUNCH -> launch(ctx, get)       // launching does not need the mimic service
            else -> withService { service -> dispatch(service, action, get) }
        }
    } catch (e: IllegalArgumentException) {
        fail(e.message ?: "bad arguments")
    }

    // authorization wrapper around run(). with approval enforcement off (the
    // default) or for an exempt command, it falls straight through. otherwise it
    // resolves the per-token grant for this (class, target app), prompting the user
    // when unknown and waiting up to the surface's timeout.
    fun runGuarded(
        ctx: Context,
        action: String,
        get: (String) -> String?,
        record: TokenStore.Record?,
        promptTimeoutMs: Long,
    ): Result {
        if (record == null || !AppState.requireApproval(ctx)) return run(ctx, action, get)
        val cls = ActionClass.of(action) ?: return run(ctx, action, get)
        val target = targetOf(get, cls)
        return when (Permissions.decision(ctx, record.id, record.mode, cls, target)) {
            Permissions.Decision.ALLOW -> run(ctx, action, get)
            Permissions.Decision.DENY -> denied(cls, target)
            Permissions.Decision.ASK -> ask(ctx, action, get, record, cls, target, promptTimeoutMs)
        }
    }

    private fun ask(
        ctx: Context, action: String, get: (String) -> String?,
        record: TokenStore.Record, cls: String, target: String, timeoutMs: Long,
    ): Result {
        val service = MimicService.instance
            ?: return fail("permission_required: enable the mimic accessibility service to approve")
        // blocks until the user answers the on-device prompt or the window closes.
        val outcome = service.promptPermission(record.label, record.id, cls, target, timeoutMs)
            ?: return fail("permission_required: approval prompt is showing on the device -- approve it, then run this again")
        if (outcome.scope != Permissions.Scope.ONCE) {
            val t = if (outcome.scope == Permissions.Scope.ALL_APPS) Permissions.TARGET_ANY else target
            Permissions.remember(ctx, record.id, cls, t, outcome.allowed)
        }
        return if (outcome.allowed) run(ctx, action, get) else denied(cls, target)
    }

    private fun denied(cls: String, target: String): Result =
        fail("permission_denied: not allowed to ${ActionClass.verb(cls)} ${targetLabel(target)}")

    // the app a request acts on: the launched package for launch, the foreground
    // app for screen reads and input, all apps for the package listing.
    private fun targetOf(get: (String) -> String?, cls: String): String = when (cls) {
        ActionClass.LAUNCH -> get(Extras.PACKAGE)
            ?: get(Extras.COMPONENT)?.substringBefore('/')?.takeIf { it.isNotEmpty() }
            ?: "(intent)"
        ActionClass.PACKAGES -> Permissions.TARGET_ANY
        else -> MimicService.instance?.activeRoot()?.packageName?.toString() ?: "(screen)"
    }

    private fun targetLabel(target: String): String =
        if (target == Permissions.TARGET_ANY) "any app" else target

    fun status(ctx: Context): JSONObject = JSONObject()
        .put("service_enabled", MimicService.isEnabled())
        .put("paired", TokenStore.isPaired(ctx))
        .put("tokens", TokenStore.count(ctx))
        .put("pairing", TokenStore.pairingActive(System.currentTimeMillis()))
        .put("intents", AppState.intents(ctx))
        .put("http", AppState.http(ctx))
        .put("mcp", AppState.mcp(ctx))
        .put("bind", AppState.bindAddress(ctx))
        .put("require_auth", AppState.requireAuth(ctx))
        .put("require_approval", AppState.requireApproval(ctx))
        .put("port", Host.PORT)

    private inline fun withService(block: (MimicService) -> Result): Result {
        val service = MimicService.instance ?: return fail("accessibility service not enabled")
        return block(service)
    }

    private fun dispatch(service: MimicService, action: String, get: (String) -> String?): Result = when (action) {
        Cmd.DUMP, Cmd.FIND -> view(service, action, get)
        Cmd.TAP -> performed(service.tap(int(get, Extras.X), int(get, Extras.Y), dur(get, Defaults.TAP_DURATION_MS)))
        Cmd.LONG_PRESS -> performed(service.longPress(int(get, Extras.X), int(get, Extras.Y), dur(get, Defaults.LONG_PRESS_DURATION_MS)))
        Cmd.SWIPE -> performed(service.swipe(int(get, Extras.X), int(get, Extras.Y), int(get, Extras.X2), int(get, Extras.Y2), dur(get, Defaults.SWIPE_DURATION_MS)))
        Cmd.CLICK -> click(service, get)
        Cmd.SET_TEXT -> setText(service, get)
        Cmd.GLOBAL -> performed(service.globalNav(get(Extras.NAV) ?: ""))
        Cmd.SCROLL -> scrollFor(service, get)
        Cmd.WAIT -> waitFor(service, get)
        Cmd.SCREENSHOT -> screenshot(service, get)
        else -> fail("unknown command: $action")
    }

    private fun screenshot(service: MimicService, get: (String) -> String?): Result {
        val format = get(Extras.FORMAT) ?: Defaults.SCREENSHOT_FORMAT
        val quality = get(Extras.QUALITY)?.toIntOrNull() ?: Defaults.SCREENSHOT_QUALITY
        val scale = get(Extras.SCALE)?.toDoubleOrNull() ?: 1.0
        val bytes = service.captureScreenshot(format, quality, scale)
            ?: return fail("screenshot failed (unsupported, rate-limited, or capture denied)")
        return ok(bytes)
    }

    private fun view(service: MimicService, action: String, get: (String) -> String?): Result {
        val root = service.activeRoot() ?: return fail("no active window")
        var cfg = ViewConfig.from(get)
        // FIND defaults to a flat match list unless an explicit format is given.
        if (action == Cmd.FIND && get(Extras.FORMAT) == null) cfg = cfg.copy(format = "flat")
        return ok(NodeTree.render(root, cfg))
    }

    // poll the active window until a node matches the query, or the timeout. on
    // success returns the matches (like find); on timeout, a clear failure.
    private fun waitFor(service: MimicService, get: (String) -> String?): Result {
        val query = get(Extras.QUERY)
        if (query.isNullOrEmpty()) return fail("wait needs a query (text/id/class/desc)")
        var cfg = ViewConfig.from(get)
        if (get(Extras.FORMAT) == null) cfg = cfg.copy(format = "flat")
        val timeoutMs = waitTimeoutMs(get)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val root = service.activeRoot()
            if (root != null && NodeTree.firstMatch(root, cfg) != null) return ok(NodeTree.render(root, cfg))
            if (System.currentTimeMillis() >= deadline)
                return fail("wait: \"$query\" not found within ${"%.1f".format(timeoutMs / 1000.0)}s")
            Thread.sleep(Defaults.WAIT_POLL_MS)
        }
    }

    private fun waitTimeoutMs(get: (String) -> String?): Long =
        ((get(Extras.TIMEOUT)?.toDoubleOrNull() ?: Defaults.WAIT_TIMEOUT_S) * 1000).toLong()
            .coerceIn(0L, Defaults.WAIT_MAX_MS)

    private val SCROLL_DIRS = setOf("up", "down", "left", "right")

    // a text-only compact render of the screen, used as a cheap fingerprint to
    // detect that a scroll no longer moves content (the end has been reached).
    private val SIGNATURE_CFG = ViewConfig(
        format = "compact", filter = "text", maxDepth = Defaults.MAX_DEPTH, pkg = null,
        fields = null, by = null, query = null, match = Defaults.MATCH,
    )

    // scroll the active window in a direction. with a query, keep scrolling until a
    // node matches (returning the matches like find/wait), stopping at the timeout,
    // a scroll cap, or when a scroll no longer changes the screen (end of content).
    // without a query, perform `steps` scrolls (default 1) and report performed.
    private fun scrollFor(service: MimicService, get: (String) -> String?): Result {
        val dir = (get(Extras.DIRECTION) ?: "").trim().lowercase()
        if (dir !in SCROLL_DIRS) return fail("scroll needs a direction: up | down | left | right")
        val query = get(Extras.QUERY)
        if (query.isNullOrEmpty()) return scrollSteps(service, dir, get)

        // the stop condition is an *on-screen* match. the accessibility tree can
        // include off-screen rows (e.g. a settings list), so match only visible
        // nodes -- otherwise scroll would "find" a node still below the fold that
        // the user can neither see nor tap. visibility narrows the caller's filter.
        var cfg = ViewConfig.from(get).plusFilter("visible")
        if (get(Extras.FORMAT) == null) cfg = cfg.copy(format = "flat")
        val maxSteps = stepCount(get, Defaults.SCROLL_MAX_STEPS)
        val deadline = System.currentTimeMillis() + waitTimeoutMs(get)
        // by default a match already on screen satisfies the scroll (scroll-into-
        // view). with skip_visible, ignore the starting matches and keep scrolling
        // until the query reappears in content that was not already shown -- "skip
        // what is here, find the next one in this direction".
        val skipVisible = get(Extras.SKIP_VISIBLE).let { it == "true" || it == "1" }
        var armed = !skipVisible
        var steps = 0
        while (true) {
            val root = service.activeRoot()
            val match = root != null && NodeTree.firstMatch(root, cfg) != null
            if (!armed && !match) armed = true  // the starting matches have scrolled away
            if (armed && match) return ok(NodeTree.render(root, cfg))
            if (steps >= maxSteps || System.currentTimeMillis() >= deadline)
                return fail("scroll $dir: \"$query\" not found after $steps scroll(s)")
            val before = root?.let { signature(it) } ?: ""
            if (!service.scroll(dir, Defaults.SCROLL_DURATION_MS)) return fail("scroll failed (no scrollable view?)")
            steps++
            // wait for the tree to reflect the drag; if it never changes within the
            // window, the content did not move -- the end was reached. polling (vs a
            // fixed delay) tolerates a tree that updates slowly under load.
            if (!awaitChange(service, before, Defaults.SCROLL_CHANGE_WINDOW_MS))
                return fail("scroll $dir: \"$query\" not found; reached the end of the content")
        }
    }

    // a text-only compact render of the screen -- a cheap fingerprint of what is on
    // screen, which shifts as content scrolls and holds steady at the end.
    private fun signature(root: AccessibilityNodeInfo): String =
        NodeTree.render(root, SIGNATURE_CFG).toString()

    // poll until the screen differs from `before` (the drag moved content), or the
    // window elapses with no change (the content did not move -- the end).
    private fun awaitChange(service: MimicService, before: String, windowMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + windowMs
        while (true) {
            Thread.sleep(Defaults.WAIT_POLL_MS)
            if ((service.activeRoot()?.let { signature(it) } ?: "") != before) return true
            if (System.currentTimeMillis() >= deadline) return false
        }
    }

    private fun scrollSteps(service: MimicService, dir: String, get: (String) -> String?): Result {
        val steps = stepCount(get, 1)
        for (i in 0 until steps) {
            if (!service.scroll(dir, Defaults.SCROLL_DURATION_MS)) return fail("scroll failed (no scrollable view?)")
            if (i < steps - 1) Thread.sleep(Defaults.SCROLL_SETTLE_MS)
        }
        return ok(JSONObject().put("performed", true).put("scrolled", steps))
    }

    private fun stepCount(get: (String) -> String?, default: Int): Int =
        get(Extras.STEPS)?.toIntOrNull()?.coerceIn(1, Defaults.SCROLL_MAX_STEPS) ?: default

    // poll until the given package owns the active window, or the timeout.
    private fun waitForeground(pkg: String, timeoutMs: Long): Boolean {
        val service = MimicService.instance ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (service.activeRoot()?.packageName?.toString() == pkg) return true
            Thread.sleep(Defaults.WAIT_POLL_MS)
        }
        return service.activeRoot()?.packageName?.toString() == pkg
    }

    private fun click(service: MimicService, get: (String) -> String?): Result {
        val by = get(Extras.BY) ?: "coords"
        if (by == "coords") return performed(service.tap(int(get, Extras.X), int(get, Extras.Y), dur(get, Defaults.TAP_DURATION_MS)))
        val node = resolve(service, get) ?: return fail("no node matched query")
        return performed(service.clickNode(node))
    }

    // with a by/query, target the matching node; without one, target whatever node
    // currently holds input focus.
    private fun setText(service: MimicService, get: (String) -> String?): Result {
        val text = get(Extras.TEXT) ?: return fail("missing text")
        val node = if (get(Extras.QUERY).isNullOrEmpty()) {
            service.focusedInput() ?: return fail("no focused input; pass --id/--text to target a field")
        } else {
            resolve(service, get) ?: return fail("no node matched query")
        }
        return performed(service.setNodeText(node, text))
    }

    // locate the first node matching the by/query/match args in the active
    // window, fresh at call time (stateless interaction).
    private fun resolve(service: MimicService, get: (String) -> String?): AccessibilityNodeInfo? =
        if (get(Extras.QUERY).isNullOrEmpty()) null
        else NodeTree.firstMatch(service.activeRoot(), ViewConfig.from(get))

    // list launchable apps (package, label, launcher component), optionally
    // filtered by a substring of either. only apps visible through the manifest
    // <queries> (launcher activities) are returned, so no broad-visibility
    // permission is needed; the component is ready to pass to launch.
    private fun packages(ctx: Context, get: (String) -> String?): Result {
        val query = get(Extras.QUERY)?.trim()?.lowercase()?.ifEmpty { null }
        val fuzzy = get(Extras.FUZZY).let { it == "true" || it == "1" }
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val all = pm.queryIntentActivities(main, 0).mapNotNull { ri ->
            val ai = ri.activityInfo ?: return@mapNotNull null
            Triple(ri.loadLabel(pm).toString(), ai.packageName, "${ai.packageName}/${ai.name}")
        }
        val rows = when {
            query == null -> all.sortedBy { it.first.lowercase() }
            // approximate: rank by min edit distance to label words / package, keep
            // close matches so a typo ("settngs") still finds "settings".
            fuzzy -> all.map { it to score(query, it.first, it.second) }
                .filter { it.second <= maxOf(2, query.length / 2) }
                .sortedBy { it.second }
                .map { it.first }
            else -> all.filter { query in it.first.lowercase() || query in it.second.lowercase() }
                .sortedBy { it.first.lowercase() }
        }
        val arr = JSONArray()
        for ((label, pkg, component) in rows) {
            arr.put(JSONObject().put("package", pkg).put("label", label).put("component", component))
        }
        return ok(arr)
    }

    // distance of a query to an app: 0 if it is a substring of the label/package,
    // else the smallest edit distance to any label word or the package's last segment.
    private fun score(q: String, label: String, pkg: String): Int {
        val l = label.lowercase()
        val p = pkg.lowercase()
        if (q in l || q in p) return 0
        val candidates = l.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() } + p.substringAfterLast('.')
        return candidates.minOfOrNull { editDistance(q, it) } ?: editDistance(q, l)
    }

    private fun editDistance(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    // start an activity by package (its launcher), explicit component, or
    // action/uri. uses the mimic service context when available. note: android
    // background-activity-launch rules may block this unless the app is
    // foreground-recent; the screen must be unlocked.
    private fun launch(ctx: Context, get: (String) -> String?): Result {
        val component = get(Extras.COMPONENT)
        val action = get(Extras.ACTION)
        val uri = get(Extras.URI)
        val pkg = get(Extras.PACKAGE)
        val intent = when {
            component != null -> Intent().setComponent(
                ComponentName.unflattenFromString(component) ?: return fail("bad component: $component"))
            action != null -> Intent(action).also { i -> uri?.let { i.data = Uri.parse(it) }; pkg?.let { i.setPackage(it) } }
            uri != null -> Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            pkg != null -> ctx.packageManager.getLaunchIntentForPackage(pkg)
                ?: return fail("no launch intent for package: $pkg")
            else -> return fail("launch needs one of: package, component, action, uri")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            (MimicService.instance ?: ctx).startActivity(intent)
            // --wait: block until the launched app owns the active window (only
            // possible when the target package is known).
            val wantWait = get(Extras.WAIT).let { it == "true" || it == "1" }
            val targetPkg = pkg ?: component?.substringBefore('/')?.takeIf { it.isNotEmpty() }
            if (wantWait && targetPkg != null) {
                ok(JSONObject().put("launched", true).put("foreground", waitForeground(targetPkg, waitTimeoutMs(get))))
            } else {
                ok(JSONObject().put("launched", true))
            }
        } catch (e: Exception) {
            fail("launch failed: ${e.message}")
        }
    }

    private fun performed(done: Boolean): Result =
        if (done) ok(JSONObject().put("performed", true)) else fail("action failed")

    private fun int(get: (String) -> String?, key: String): Int =
        get(key)?.trim()?.toIntOrNull() ?: throw IllegalArgumentException("missing or invalid integer argument: $key")

    private fun dur(get: (String) -> String?, default: Long): Long =
        get(Extras.DURATION)?.toLongOrNull() ?: default
}
