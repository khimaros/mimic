package com.khimaros.mimic

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

// a node with no name of its own borrows one from its descendants. bounded so a
// scroll container cannot absorb the whole screen into a single label.
private const val LABEL_MAX_DEPTH = 4
private const val LABEL_MAX_PARTS = 4
private const val LABEL_JOIN = " / "

// how a view request is shaped. parsed once from intent extras so the traversal
// logic stays free of intent plumbing.
data class ViewConfig(
    val format: String,
    val filter: String,
    val maxDepth: Int,
    val pkg: String?,
    // null means the caller did not choose; each format then picks its own default.
    val fields: Set<String>?,
    val by: String?,
    val query: String?,
    val match: String,
) {
    // several filters may be combined ("interactive,visible") and every one must
    // pass, so a caller can narrow to the rows it can actually reach in one dump.
    val filters: List<String> = filter.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    // a json dump carries every attribute unless asked to trim; compact carries
    // the four terse columns. resolved once, not per node.
    val jsonFields: Set<String> = fields ?: NODE_FIELDS.toSet()
    val compactFields: List<String> = (fields ?: COMPACT_FIELDS).filter { it != "children" }

    fun plusFilter(name: String): ViewConfig = copy(filter = "$filter,$name")

    companion object {
        fun from(get: (String) -> String?): ViewConfig = ViewConfig(
            format = get(Extras.FORMAT) ?: Defaults.FORMAT,
            filter = get(Extras.FILTER) ?: Defaults.FILTER,
            maxDepth = get(Extras.MAX_DEPTH)?.toIntOrNull() ?: Defaults.MAX_DEPTH,
            pkg = get(Extras.PACKAGE)?.ifBlank { null },
            fields = get(Extras.FIELDS)
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }?.toSet(),
            by = get(Extras.BY)?.ifBlank { null },
            query = get(Extras.QUERY),
            match = get(Extras.MATCH) ?: Defaults.MATCH,
        )
    }
}

// serializes an AccessibilityNodeInfo tree, applying the filter, query, depth,
// and package constraints on the device so the returned payload stays small.
// all traversal is pure with respect to the node tree (read-only).
object NodeTree {

    // DUMP: render the (filtered) tree in the requested format. returns either a
    // json value (tree/flat) or a string (compact).
    fun render(root: AccessibilityNodeInfo?, cfg: ViewConfig): Any = when (cfg.format) {
        "compact" -> collect(root, cfg).joinToString("\n") { compactLine(it, cfg.compactFields) }
        "flat" -> JSONArray().apply { collect(root, cfg).forEach { put(nodeJson(it, cfg.jsonFields)) } }
        else -> treeJson(root, cfg, 0) ?: JSONObject()
    }

    // FIND / CLICK / SET_TEXT target resolution: nodes matching filter+query in
    // pre-order. interaction takes the first.
    fun collect(root: AccessibilityNodeInfo?, cfg: ViewConfig): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        if (root != null) walk(root, cfg, 0) { out.add(it) }
        return out
    }

    fun firstMatch(root: AccessibilityNodeInfo?, cfg: ViewConfig): AccessibilityNodeInfo? =
        collect(root, cfg).firstOrNull()

    private fun walk(
        node: AccessibilityNodeInfo,
        cfg: ViewConfig,
        depth: Int,
        emit: (AccessibilityNodeInfo) -> Unit,
    ) {
        if (selected(node, cfg)) emit(node)
        if (cfg.maxDepth < 0 || depth < cfg.maxDepth) {
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { walk(it, cfg, depth + 1, emit) }
            }
        }
    }

    // a node survives into a tree render if it is selected itself or has any
    // selected descendant, so container structure around matches is preserved.
    private fun treeJson(node: AccessibilityNodeInfo?, cfg: ViewConfig, depth: Int): JSONObject? {
        if (node == null) return null
        val children = JSONArray()
        if (cfg.maxDepth < 0 || depth < cfg.maxDepth) {
            for (i in 0 until node.childCount) {
                treeJson(node.getChild(i), cfg, depth + 1)?.let { children.put(it) }
            }
        }
        if (!selected(node, cfg) && children.length() == 0) return null
        return nodeJson(node, cfg.jsonFields).also {
            if ("children" in cfg.jsonFields && children.length() > 0) it.put("children", children)
        }
    }

    private fun selected(node: AccessibilityNodeInfo, cfg: ViewConfig): Boolean =
        passesFilter(node, cfg) && matchesQuery(node, cfg)

    private fun passesFilter(node: AccessibilityNodeInfo, cfg: ViewConfig): Boolean {
        cfg.pkg?.let { if (node.packageName?.toString() != it) return false }
        return cfg.filters.all { matchesFilter(node, it) }
    }

    private fun matchesFilter(node: AccessibilityNodeInfo, name: String): Boolean = when (name) {
        "interactive" -> actionable(node)
        "text" -> textOf(node) != null || descOf(node) != null
        "visible" -> node.isVisibleToUser
        else -> true
    }

    // the actions mimic can actually perform on a node. focusability is not one
    // of them, so a focusable-only container is never offered as somewhere to
    // tap -- it still appears under filter=all.
    private fun actionable(node: AccessibilityNodeInfo): Boolean =
        node.isClickable || node.isLongClickable || node.isEditable ||
            node.isScrollable || node.isCheckable

    private fun matchesQuery(node: AccessibilityNodeInfo, cfg: ViewConfig): Boolean {
        val q = cfg.query ?: return true
        val hay = when (cfg.by ?: "text") {
            "id" -> node.viewIdResourceName
            "class" -> node.className?.toString()
            "desc" -> descOf(node)
            else -> textOf(node)
        } ?: return false
        return when (cfg.match) {
            "exact" -> hay == q
            "regex" -> runCatching { Regex(q).containsMatchIn(hay) }.getOrDefault(false)
            else -> hay.contains(q, ignoreCase = true)
        }
    }

    private fun nodeJson(node: AccessibilityNodeInfo, fields: Set<String>): JSONObject {
        val o = JSONObject()
        val r = Rect().also { node.getBoundsInScreen(it) }
        if ("class" in fields) node.className?.let { o.put("class", it.toString()) }
        if ("text" in fields) textOf(node)?.let { o.put("text", it) }
        if ("desc" in fields) descOf(node)?.let { o.put("desc", it) }
        // omitted when text/desc already carries it, so a node that names itself
        // is not serialized twice.
        if ("label" in fields) labelOf(node)
            ?.takeUnless { it == o.optString("text") || it == o.optString("desc") }
            ?.let { o.put("label", it) }
        if ("id" in fields) node.viewIdResourceName?.let { o.put("id", it) }
        if ("bounds" in fields) o.put("bounds", JSONArray(listOf(r.left, r.top, r.right, r.bottom)))
        if ("center" in fields) o.put("center", JSONArray(listOf(r.centerX(), r.centerY())))
        if ("actions" in fields) o.put("actions", JSONArray(actionsOf(node)))
        return o
    }

    // one terse line per node, a tab-delimited column per field -- by default
    // "cx,cy<tab>class<tab>label<tab>id". built for llm context budgets;
    // everything needed to click is on the line.
    private fun compactLine(node: AccessibilityNodeInfo, fields: List<String>): String {
        val r = Rect().also { node.getBoundsInScreen(it) }
        return fields.joinToString("\t") { field ->
            // collapse tabs/newlines so each node stays on one line whatever its
            // text content.
            compactValue(node, r, field).replace(WHITESPACE, " ").trim()
        }
    }

    private fun compactValue(node: AccessibilityNodeInfo, r: Rect, field: String): String = when (field) {
        "center" -> "${r.centerX()},${r.centerY()}"
        "bounds" -> "${r.left},${r.top},${r.right},${r.bottom}"
        "class" -> node.className?.toString()?.substringAfterLast('.') ?: ""
        "label" -> labelOf(node) ?: ""
        "text" -> textOf(node) ?: ""
        "desc" -> descOf(node) ?: ""
        "id" -> node.viewIdResourceName?.substringAfterLast('/') ?: ""
        "actions" -> actionsOf(node).joinToString(",")
        else -> ""
    }

    private val WHITESPACE = Regex("[\\t\\r\\n]+")

    private fun textOf(node: AccessibilityNodeInfo): String? =
        node.text?.toString()?.takeIf { it.isNotEmpty() }

    private fun descOf(node: AccessibilityNodeInfo): String? =
        node.contentDescription?.toString()?.takeIf { it.isNotEmpty() }

    // the node's name: its own text or content-description, else -- for a node
    // that can be acted on -- the text of its descendants. a clickable row whose
    // name lives in child TextViews is the dominant android pattern, and without
    // this a screen of them reads as indistinguishable containers.
    private fun labelOf(node: AccessibilityNodeInfo): String? =
        textOf(node) ?: descOf(node) ?: if (actionable(node)) inheritedLabel(node) else null

    private fun inheritedLabel(node: AccessibilityNodeInfo): String? {
        val parts = ArrayList<String>()
        gatherText(node, 0, parts)
        return parts.takeIf { it.isNotEmpty() }?.joinToString(LABEL_JOIN)
    }

    // descent stops at a descendant that is itself clickable: that text names the
    // child, not this node. a row's title and summary both make the label, since
    // the summary is often what tells two rows apart.
    private fun gatherText(node: AccessibilityNodeInfo, depth: Int, out: MutableList<String>) {
        if (depth >= LABEL_MAX_DEPTH) return
        for (i in 0 until node.childCount) {
            if (out.size >= LABEL_MAX_PARTS) return
            val child = node.getChild(i) ?: continue
            if (child.isClickable) continue
            val own = textOf(child) ?: descOf(child)
            if (own != null) out.add(own) else gatherText(child, depth + 1, out)
        }
    }

    private fun actionsOf(node: AccessibilityNodeInfo): List<String> = buildList {
        if (node.isClickable) add("click")
        if (node.isLongClickable) add("long")
        if (node.isEditable) add("edit")
        if (node.isScrollable) add("scroll")
        if (node.isFocusable) add("focus")
        if (node.isCheckable) add("check")
        if (node.isChecked) add("checked")
        if (node.isSelected) add("selected")
    }
}
