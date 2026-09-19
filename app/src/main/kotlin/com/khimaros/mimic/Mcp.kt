package com.khimaros.mimic

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

// a minimal in-app mcp server over streamable http (json-rpc 2.0). it exposes the
// view/interact commands as mcp tools backed by the same Commands core. each POST
// is handled statelessly and answered with application/json; notifications get a
// 202 with no body. enough for request/response tool use by an mcp client.
object Mcp {

    // returns (http status line, response body). the record (null when auth is off)
    // authorizes tool calls.
    fun handle(ctx: Context, body: String, record: TokenStore.Record?): Pair<String, String> {
        val req = try {
            JSONObject(body)
        } catch (_: Exception) {
            return "200 OK" to rpcError(JSONObject.NULL, -32700, "parse error")
        }
        val id = if (req.has("id") && !req.isNull("id")) req.get("id") else null
        return when (val method = req.optString("method")) {
            "initialize" -> "200 OK" to rpcResult(id, initializeResult(req.optJSONObject("params")))
            "ping" -> "200 OK" to rpcResult(id, JSONObject())
            "tools/list" -> "200 OK" to rpcResult(id, JSONObject().put("tools", TOOLS))
            "tools/call" -> "200 OK" to rpcResult(id, callTool(ctx, req.optJSONObject("params"), record))
            "" -> "400 Bad Request" to rpcError(id ?: JSONObject.NULL, -32600, "invalid request")
            else ->
                // notifications (no id) need no response; other unknowns are errors.
                if (id == null) "202 Accepted" to ""
                else "200 OK" to rpcError(id, -32601, "method not found: $method")
        }
    }

    private fun initializeResult(params: JSONObject?): JSONObject {
        val version = params?.optString("protocolVersion").takeIf { !it.isNullOrEmpty() } ?: Host.MCP_PROTOCOL
        return JSONObject()
            .put("protocolVersion", version)
            .put("capabilities", JSONObject().put("tools", JSONObject()))
            .put("serverInfo", JSONObject().put("name", Host.SERVER_NAME).put("version", Host.VERSION))
    }

    private fun callTool(ctx: Context, params: JSONObject?, record: TokenStore.Record?): JSONObject {
        val name = params?.optString("name") ?: ""
        val action = TOOL_ACTION[name]
            ?: return toolResult("unknown tool: $name", isError = true)
        val args = params?.optJSONObject("arguments") ?: JSONObject()
        val get = { k: String -> if (args.has(k) && !args.isNull(k)) args.get(k).toString() else null }
        val result = Commands.runGuarded(ctx, action, get, record, Defaults.PROMPT_TIMEOUT_HTTP_MS)
        val data = result.data
        if (result.ok && data is ByteArray) {
            return imageResult(data, get(Extras.FORMAT))
        }
        // action results carry a terse json flag ({"launched":true}); a model can
        // misread that as failure, so map success to a plain affirmative. isError
        // already distinguishes failure (where the text is the error message).
        val text = when {
            !result.ok -> result.error ?: "error"
            data is String -> data
            data is JSONObject && data.optBoolean("launched") -> "launched the app"
            data is JSONObject && data.optBoolean("performed") -> "performed"
            data != null -> data.toString()
            else -> "ok"
        }
        return toolResult(text, isError = !result.ok)
    }

    private fun toolResult(text: String, isError: Boolean): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("isError", isError)

    private fun imageResult(bytes: ByteArray, format: String?): JSONObject {
        val mime = if (format == "jpeg" || format == "jpg") "image/jpeg" else "image/png"
        val block = JSONObject()
            .put("type", "image")
            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("mimeType", mime)
        return JSONObject().put("content", JSONArray().put(block)).put("isError", false)
    }

    private fun rpcResult(id: Any?, result: Any): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("result", result)
        .toString()

    private fun rpcError(id: Any?, code: Int, message: String): String = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id ?: JSONObject.NULL)
        .put("error", JSONObject().put("code", code).put("message", message))
        .toString()

    // ---- tool catalog ----

    private val TOOL_ACTION = mapOf(
        "mimic_status" to Cmd.STATUS,
        "mimic_dump" to Cmd.DUMP,
        "mimic_find" to Cmd.FIND,
        "mimic_tap" to Cmd.TAP,
        "mimic_long_press" to Cmd.LONG_PRESS,
        "mimic_swipe" to Cmd.SWIPE,
        "mimic_click" to Cmd.CLICK,
        "mimic_set_text" to Cmd.SET_TEXT,
        "mimic_global" to Cmd.GLOBAL,
        "mimic_scroll" to Cmd.SCROLL,
        "mimic_wait" to Cmd.WAIT,
        "mimic_packages" to Cmd.PACKAGES,
        "mimic_launch" to Cmd.LAUNCH,
        "mimic_screenshot" to Cmd.SCREENSHOT,
    )

    private fun prop(type: String, desc: String, enum: List<String>? = null): JSONObject {
        val o = JSONObject().put("type", type).put("description", desc)
        enum?.let { o.put("enum", JSONArray(it)) }
        return o
    }

    private fun schema(required: List<String>, props: Map<String, JSONObject>): JSONObject {
        val p = JSONObject()
        props.forEach { (k, v) -> p.put(k, v) }
        return JSONObject().put("type", "object").put("properties", p).also {
            if (required.isNotEmpty()) it.put("required", JSONArray(required))
        }
    }

    private fun tool(name: String, description: String, schema: JSONObject): JSONObject =
        JSONObject().put("name", name).put("description", description).put("inputSchema", schema)

    private val FILTER = prop("string", "interactive | text | visible | all; comma-combine to require several at once, e.g. 'interactive,visible' for the rows you can actually reach")
    private val FORMAT = prop("string", "tree | flat | compact", listOf("tree", "flat", "compact"))
    private val BY = prop("string", "text | id | class | desc", listOf("text", "id", "class", "desc"))
    private val MATCH = prop("string", "exact | contains | regex", listOf("exact", "contains", "regex"))
    private val DEPTH = prop("integer", "max tree depth (-1 = unlimited)")
    private val PACKAGE = prop("string", "restrict to one app package")
    private val FIELDS = prop("string", "comma list: class,text,desc,label,id,bounds,center,actions. in compact format these become the columns, in the order given")

    private val TOOLS: JSONArray = JSONArray(listOf(
        tool("mimic_status", "report whether the service is enabled and which surfaces are on", schema(emptyList(), emptyMap())),
        tool("mimic_dump", "dump the active window node tree; filter on-device to keep output small",
            schema(emptyList(), mapOf("filter" to FILTER, "format" to FORMAT, "max_depth" to DEPTH, "package" to PACKAGE, "fields" to FIELDS))),
        tool("mimic_find", "find nodes matching a query; returns a flat match list by default",
            schema(listOf("query"), mapOf(
                "query" to prop("string", "the text/id/class/desc to match"),
                "by" to BY, "match" to MATCH, "format" to FORMAT, "fields" to FIELDS, "filter" to FILTER, "max_depth" to DEPTH, "package" to PACKAGE))),
        tool("mimic_tap", "tap at screen coordinates",
            schema(listOf("x", "y"), mapOf("x" to prop("integer", "x px"), "y" to prop("integer", "y px"), "duration" to prop("integer", "ms")))),
        tool("mimic_long_press", "long press at screen coordinates",
            schema(listOf("x", "y"), mapOf("x" to prop("integer", "x px"), "y" to prop("integer", "y px"), "duration" to prop("integer", "ms")))),
        tool("mimic_swipe", "swipe from (x,y) to (x2,y2)",
            schema(listOf("x", "y", "x2", "y2"), mapOf(
                "x" to prop("integer", "start x"), "y" to prop("integer", "start y"),
                "x2" to prop("integer", "end x"), "y2" to prop("integer", "end y"), "duration" to prop("integer", "ms")))),
        tool("mimic_click", "click a node found by text/id/class/desc, or tap coordinates (by=coords)",
            schema(emptyList(), mapOf(
                "by" to prop("string", "coords | text | id | class | desc", listOf("coords", "text", "id", "class", "desc")),
                "query" to prop("string", "value to match when by is not coords"),
                "x" to prop("integer", "x px when by=coords"), "y" to prop("integer", "y px when by=coords"), "match" to MATCH))),
        tool("mimic_set_text", "set the text of an editable node located by text/id/class/desc; with no by/query, type into the currently focused field",
            schema(listOf("text"), mapOf("text" to prop("string", "text to enter"), "by" to BY, "query" to prop("string", "value to match (omit to target the focused field)"), "match" to MATCH))),
        tool("mimic_global", "perform a global navigation action",
            schema(listOf("nav"), mapOf("nav" to prop("string", "back | home | recents | notifications", listOf("back", "home", "recents", "notifications"))))),
        tool("mimic_scroll", "scroll the active window in a direction; with a query, keep scrolling until a node matching it is visible on screen (returns the visible matches), stopping at the end of the content or the timeout",
            schema(listOf("direction"), mapOf(
                "direction" to prop("string", "the content-reveal direction: up | down | left | right", listOf("up", "down", "left", "right")),
                "query" to prop("string", "scroll until an on-screen node matches this text/id/class/desc; omit to scroll once"),
                "by" to BY, "match" to MATCH, "filter" to FILTER, "package" to PACKAGE,
                "steps" to prop("integer", "max scrolls with a query, or exact scrolls without one (default 1)"),
                "skip_visible" to prop("boolean", "ignore matches already on screen; keep scrolling to the next occurrence in this direction"),
                "timeout" to prop("number", "seconds to keep scrolling for a match (default 10)")))),
        tool("mimic_wait", "wait until a node matching the query appears in the active window (polls); returns the matches, or fails on timeout",
            schema(listOf("query"), mapOf(
                "query" to prop("string", "the text/id/class/desc to wait for"),
                "by" to BY, "match" to MATCH, "filter" to FILTER, "package" to PACKAGE,
                "timeout" to prop("number", "seconds to wait (default 10)")))),
        tool("mimic_packages", "list launchable apps as {package, label, component}; optionally filter by a substring of either. the component can be passed straight to mimic_launch",
            schema(emptyList(), mapOf(
                "query" to prop("string", "filter by package or label substring"),
                "fuzzy" to prop("boolean", "approximate (edit-distance) match, tolerating typos")))),
        tool("mimic_launch", "launch an app or activity (by package, component, or action/uri); with wait=true, block until the app is foreground",
            schema(emptyList(), mapOf(
                "package" to prop("string", "launch this app's main activity"),
                "component" to prop("string", "explicit 'pkg/.Activity'"),
                "action" to prop("string", "an intent action"),
                "uri" to prop("string", "data uri (with action, or opened via ACTION_VIEW)"),
                "wait" to prop("boolean", "block until the launched app owns the active window"),
                "timeout" to prop("number", "seconds to wait when wait=true (default 10)")))),
        tool("mimic_screenshot", "capture the screen as an image -- a last resort; prefer the text tree (dump/find) which is far cheaper",
            schema(emptyList(), mapOf(
                "format" to prop("string", "png | jpeg", listOf("png", "jpeg")),
                "quality" to prop("integer", "jpeg quality 1-100"),
                "scale" to prop("number", "downscale factor 0-1")))),
    ))
}
