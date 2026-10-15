package dev.jadxmcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.jadxmcp.ToolRegistry")
private val json = Json { prettyPrint = true; encodeDefaults = true }

fun registerTools(server: Server, engine: JadxEngine) {
    val page = "Pagination: limit (default 50), offset (default 0)"
    fun SB.paging() { prop("limit", "integer", "Max items (default 50)"); prop("offset", "integer", "Items to skip (default 0)") }
    fun JsonObject?.lim() = optInt("limit") ?: 50
    fun JsonObject?.off() = optInt("offset") ?: 0

    server.addTool("get_server_info", "Revoid and jadx versions plus capabilities (e.g. editing: true).", mkSchema {}) { _ ->
        wrap { json.encodeToString(engine.serverInfo()) }
    }

    server.addTool("load_apk",
        "Load and analyze an APK/DEX file. Must be called first. Resets any edits.",
        mkSchema {
            required("apk_path"); prop("apk_path", "string", "Absolute path to APK/DEX file")
            prop("deobfuscation", "boolean", "Enable jadx deobfuscation (default false)")
        }
    ) { req ->
        wrap { json.encodeToString(engine.loadApk(req.arguments.str("apk_path"), req.arguments.optBool("deobfuscation") ?: false)) }
    }

    server.addTool("list_classes",
        "List decompiled classes (inner classes included) with raw names. Optionally filter by package prefix.",
        mkSchema { prop("filter", "string", "Optional package prefix filter"); paging() }
    ) { req ->
        wrap { json.encodeToString(engine.listClasses(req.arguments.optStr("filter"), req.arguments.lim(), req.arguments.off())) }
    }

    server.addTool("get_class_source",
        "Get decompiled Java source of a class (an inner class returns its top-level class's source). Output is capped; use start_line/end_line to page.",
        mkSchema {
            required("class_name"); prop("class_name", "string", "Class name: raw (a.b.Outer\$Inner) or current/earlier display name; exact match")
            prop("start_line", "integer", "First line (1-based)"); prop("end_line", "integer", "Last line (inclusive)")
            prop("line_numbers", "boolean", "Prefix lines with numbers (default false)")
            prop("max_chars", "integer", "Size cap in characters (default ${JadxEngine.DEFAULT_MAX_CHARS})")
        }
    ) { req ->
        val a = req.arguments
        wrap {
            engine.getClassSource(a.str("class_name"), a.optInt("start_line"), a.optInt("end_line"),
                a.optBool("line_numbers") ?: false, a.optInt("max_chars") ?: JadxEngine.DEFAULT_MAX_CHARS)
        }
    }

    server.addTool("list_methods",
        "List methods in a class with full-type signatures and shortIds.",
        mkSchema { required("class_name"); prop("class_name", "string", "Class name (raw or display)"); paging() }
    ) { req ->
        wrap { json.encodeToString(engine.listMethods(req.arguments.str("class_name"), req.arguments.lim(), req.arguments.off())) }
    }

    server.addTool("get_method_source",
        "Get decompiled source of a method. Overloads: pass method_id (shortId) to select one, otherwise all overloads of the name are returned.",
        mkSchema {
            required("class_name", "method_name")
            prop("class_name", "string", "Class name (raw or display)")
            prop("method_name", "string", "Method name (raw, display or earlier name)")
            prop("method_id", "string", "Optional shortId, e.g. m(Ljava/lang/String;I)V")
        }
    ) { req ->
        wrap { engine.getMethodSource(req.arguments.str("class_name"), req.arguments.str("method_name"), req.arguments.optStr("method_id")) }
    }

    server.addTool("get_class_summary",
        "Get structured summary: superclass, interfaces, counts, component type.",
        mkSchema { required("class_name"); prop("class_name", "string", "Class name (raw or display)") }
    ) { req ->
        wrap { json.encodeToString(engine.getClassSummary(req.arguments.str("class_name"))) }
    }

    server.addTool("search_classes",
        "Search class names (display or raw) by substring or regex.",
        mkSchema { required("query"); prop("query", "string", "Search query"); prop("is_regex", "boolean", "Treat as regex (default: false)"); paging() }
    ) { req ->
        wrap { json.encodeToString(engine.searchClasses(req.arguments.str("query"), req.arguments.optBool("is_regex") ?: false, req.arguments.lim(), req.arguments.off())) }
    }

    server.addTool("search_strings",
        "Search string constants across all classes. Also scans smali const-string for methods that failed to decompile.",
        mkSchema { required("query"); prop("query", "string", "Substring to search"); paging() }
    ) { req ->
        wrap { json.encodeToString(engine.searchStrings(req.arguments.str("query"), req.arguments.lim(), req.arguments.off())) }
    }

    val sigDesc = "Method id 'rawClass#shortId', or display signature 'pkg.Cls.name(types):ret' (return optional), or 'pkg.Cls.name' if not overloaded"
    val exactDesc = "Exact match (default true). false enables substring matching"
    server.addTool("get_callers",
        "Get methods that call a target method.",
        mkSchema { required("method_signature"); prop("method_signature", "string", sigDesc); prop("exact", "boolean", exactDesc); paging() }
    ) { req ->
        wrap { json.encodeToString(engine.getCallers(req.arguments.str("method_signature"), req.arguments.optBool("exact") ?: true, req.arguments.lim(), req.arguments.off())) }
    }

    server.addTool("get_callees",
        "Get methods called by a given method.",
        mkSchema { required("method_signature"); prop("method_signature", "string", sigDesc); prop("exact", "boolean", exactDesc); paging() }
    ) { req ->
        wrap { json.encodeToString(engine.getCallees(req.arguments.str("method_signature"), req.arguments.optBool("exact") ?: true, req.arguments.lim(), req.arguments.off())) }
    }

    server.addTool("search_method_by_name", "Search methods across classes by name (display or raw).",
        mkSchema { required("query"); prop("query", "string", "Method name"); prop("is_regex", "boolean", "Treat as regex"); paging() }) { req ->
        wrap { json.encodeToString(engine.searchMethodByName(req.arguments.str("query"), req.arguments.optBool("is_regex") ?: false, req.arguments.lim(), req.arguments.off())) }
    }
    server.addTool("search_classes_by_keyword", "Search for classes whose source contains a keyword",
        mkSchema { required("keyword"); prop("keyword", "string", "Keyword"); paging() }) { req ->
        wrap { json.encodeToString(engine.searchClassesByKeyword(req.arguments.str("keyword"), req.arguments.lim(), req.arguments.off())) }
    }
    server.addTool("get_fields_of_class", "List fields in a class", mkSchema { required("class_name"); prop("class_name", "string", "Class name (raw or display)") }) { req ->
        wrap { json.encodeToString(engine.getFieldsOfClass(req.arguments.str("class_name"))) }
    }
    server.addTool("get_smali_of_class", "Fetch smali of class", mkSchema { required("class_name"); prop("class_name", "string", "Class name (raw or display)") }) { req ->
        wrap { engine.getSmaliOfClass(req.arguments.str("class_name")) }
    }

    // ── Live editing ──
    val memberDesc = "Optional member: method/field name or shortId. Omit to target the class itself"
    server.addTool("resolve_node", "Resolve a display or raw name to its raw jadx reference {refType, declClass, shortId}.",
        mkSchema {
            required("class_name"); prop("class_name", "string", "Class name (raw, display or earlier)"); prop("member", "string", memberDesc)
            prop("fuzzy", "boolean", "Opt in to substring/suffix class matching (default false)")
        }) { req ->
        wrap { json.encodeToString(engine.resolveNode(req.arguments.str("class_name"), req.arguments.optStr("member"), req.arguments.optBool("fuzzy") ?: false)) }
    }
    server.addTool("rename_node",
        "Rename a class, method or field (jadx code rename). Empty new_name resets. Refuses constructors and anonymous classes. Refreshes affected classes; old names keep resolving.",
        mkSchema {
            required("class_name", "new_name"); prop("class_name", "string", "Class name (raw or display)"); prop("member", "string", memberDesc)
            prop("new_name", "string", "Valid Java identifier; empty string resets to the original name")
        }) { req ->
        wrap { json.encodeToString(engine.renameNode(req.arguments.str("class_name"), req.arguments.optStr("member"), req.arguments.str("new_name"))) }
    }
    server.addTool("add_comment",
        "Add a comment to a class, method or field. Rejected on anonymous classes (not rendered); comment the outer class instead.",
        mkSchema {
            required("class_name", "text"); prop("class_name", "string", "Class name (raw or display)"); prop("member", "string", memberDesc)
            prop("text", "string", "Comment text"); prop("style", "string", "LINE, BLOCK, BLOCK_CONDENSED, JAVADOC (default), JAVADOC_CONDENSED")
        }) { req ->
        wrap { json.encodeToString(engine.addComment(req.arguments.str("class_name"), req.arguments.optStr("member"), req.arguments.str("text"), req.arguments.optStr("style"))) }
    }
    server.addTool("list_annotations", "List renames and comments with raw references and indexes.", mkSchema {}) { _ ->
        wrap { json.encodeToString(engine.listAnnotations()) }
    }
    server.addTool("remove_annotation", "Remove a rename/comment by its index from list_annotations (indexes shift after removal).",
        mkSchema { required("index"); prop("index", "integer", "Index from list_annotations") }) { req ->
        wrap { json.encodeToString(engine.removeAnnotation(req.arguments.optInt("index") ?: throw IllegalArgumentException("Missing: index"))) }
    }
    server.addTool("save_project", "Save renames/comments to a jadx-gui .jadx file. Path must be inside REVOID_OUTPUT_ROOT (relative paths resolve there).",
        mkSchema { required("path"); prop("path", "string", "Target .jadx path")}) { req ->
        wrap { json.encodeToString(engine.saveProject(req.arguments.str("path"))) }
    }
    server.addTool("load_project", "Replace current renames/comments with those from a jadx-gui .jadx file and reload affected classes.",
        mkSchema { required("path"); prop("path", "string", "Path to .jadx file") }) { req ->
        wrap { json.encodeToString(engine.loadProject(req.arguments.str("path"))) }
    }

    server.addTool("summarize_function",
        "Add a summary comment at the top of a function. Write the summary yourself from the source (what it does, key inputs/outputs). Java: pass class_name + method (comment shown in get_method_source).",
        mkSchema {
            required("summary"); prop("summary", "string", "Summary text")
            prop("class_name", "string", "Java class name (raw or display)"); prop("method", "string", "Java method name or shortId")
        }) { req ->
        val a = req.arguments
        wrap {
            val text = a.str("summary")
            json.encodeToString(engine.addComment(a.str("class_name"), a.str("method"), text, "BLOCK"))
        }
    }

    server.addTool("get_main_activity_class", "Fetch main activity from AndroidManifest", mkSchema {}) { req ->
        wrap { engine.getMainActivityClass() }
    }

    server.addTool("get_main_application_classes_names", "Fetch all the main application classes names", mkSchema {}) { req ->
        wrap { json.encodeToString(engine.getMainApplicationClassesNames()) }
    }
    server.addTool("get_manifest_activities", "Retrieve all activities from AndroidManifest", mkSchema {}) { req ->
        wrap { json.encodeToString(engine.getManifestActivities()) }
    }
    server.addTool("get_manifest_services", "Retrieve all services from AndroidManifest", mkSchema {}) { req ->
        wrap { json.encodeToString(engine.getManifestComponent("service")) }
    }
    server.addTool("get_manifest_receivers", "Retrieve all receivers from AndroidManifest", mkSchema {}) { req ->
        wrap { json.encodeToString(engine.getManifestComponent("receiver")) }
    }
    server.addTool("get_manifest_providers", "Retrieve all providers from AndroidManifest", mkSchema {}) { req ->
        wrap { json.encodeToString(engine.getManifestComponent("provider")) }
    }
    server.addTool("get_manifest_permissions", "Retrieve all used permissions from AndroidManifest", mkSchema {}) { req ->
        wrap { json.encodeToString(engine.getManifestPermissions()) }
    }
    server.addTool("get_manifest_exported_components", "Retrieve all exported components from AndroidManifest", mkSchema {}) { req ->
        wrap { json.encodeToString(engine.getManifestExportedComponents()) }
    }
    server.addTool("get_resource_strings", "Fetches paginated resource strings from strings.xml", mkSchema { prop("limit", "integer", "Limit (default 50)"); prop("offset", "integer", "Offset (default 0)") }) { req ->
        wrap { json.encodeToString(engine.getResourceStrings(req.arguments.optInt("limit") ?: 50, req.arguments.optInt("offset") ?: 0)) }
    }
    server.addTool("get_all_resource_file_names", "Retrieve all resource file names", mkSchema { prop("limit", "integer", "Limit (default 50)"); prop("offset", "integer", "Offset (default 0)") }) { req ->
        wrap { json.encodeToString(engine.getAllResourceFileNames(req.arguments.optInt("limit") ?: 50, req.arguments.optInt("offset") ?: 0)) }
    }
    server.addTool("get_resource_file", "Retrieve resource file content", mkSchema { required("name"); prop("name", "string", "Original file name (e.g., AndroidManifest.xml)") }) { req ->
        wrap { engine.getResourceFile(req.arguments.str("name")) }
    }

    server.addTool("xrefs_to_class", "Find all references to a class", mkSchema { required("class_name"); prop("class_name", "string", "Class name (raw or display)"); paging() }) { req ->
        wrap { json.encodeToString(engine.xrefsToClass(req.arguments.str("class_name"), req.arguments.optInt("limit") ?: 50, req.arguments.optInt("offset") ?: 0)) }
    }
    server.addTool("xrefs_to_method", "Find all references to a method", mkSchema { required("method_signature"); prop("method_signature", "string", sigDesc); prop("exact", "boolean", exactDesc); paging() }) { req ->
        wrap { json.encodeToString(engine.xrefsToMethod(req.arguments.str("method_signature"), req.arguments.lim(), req.arguments.off(), req.arguments.optBool("exact") ?: true)) }
    }
    server.addTool("xrefs_to_field", "Find all references to a field", mkSchema { required("class_name", "field_name"); prop("class_name", "string", "Class name"); prop("field_name", "string", "Field name"); prop("limit", "integer", "Limit"); prop("offset", "integer", "Offset") }) { req ->
        wrap { json.encodeToString(engine.xrefsToField(req.arguments.str("class_name"), req.arguments.str("field_name"), req.arguments.optInt("limit") ?: 50, req.arguments.optInt("offset") ?: 0)) }
    }
}

private inline fun wrap(block: () -> String): CallToolResult = try {
    CallToolResult(content = listOf(TextContent(block())))
} catch (e: IllegalArgumentException) {
    CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
} catch (e: IllegalStateException) {
    CallToolResult(content = listOf(TextContent("Error: ${e.message}")), isError = true)
} catch (e: Exception) {
    log.error("Tool error", e)
    CallToolResult(content = listOf(TextContent("Internal error: ${e.javaClass.simpleName}: ${e.message}")), isError = true)
}

private class SB {
    val props = mutableMapOf<String, JsonObject>()
    val req = mutableListOf<String>()
    fun prop(n: String, t: String, d: String) {
        props[n] = buildJsonObject { put("type", t); put("description", d) }
    }
    fun required(vararg ns: String) { req.addAll(ns) }
    fun build(): ToolSchema {
        val p = buildJsonObject { props.forEach { (k, v) -> put(k, v) } }
        return ToolSchema(properties = p, required = req)
    }
}

private fun mkSchema(block: SB.() -> Unit): ToolSchema = SB().apply(block).build()

private fun JsonObject?.str(k: String): String =
    this?.get(k)?.jsonPrimitive?.content ?: throw IllegalArgumentException("Missing: $k")
private fun JsonObject?.optStr(k: String): String? = this?.get(k)?.jsonPrimitive?.contentOrNull
private fun JsonObject?.optBool(k: String): Boolean? = this?.get(k)?.jsonPrimitive?.booleanOrNull
private fun JsonObject?.optInt(k: String): Int? = this?.get(k)?.jsonPrimitive?.intOrNull
