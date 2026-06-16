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
    server.addTool("load_apk",
        "Load and analyze an APK/DEX file. Must be called first.",
        mkSchema { required("apk_path"); prop("apk_path", "string", "Absolute path to APK/DEX file") }
    ) { req ->
        wrap { json.encodeToString(engine.loadApk(req.arguments.str("apk_path"))) }
    }

    server.addTool("list_classes",
        "List all decompiled classes. Optionally filter by package prefix.",
        mkSchema { prop("filter", "string", "Optional package prefix filter") }
    ) { req ->
        wrap { json.encodeToString(engine.listClasses(req.arguments.optStr("filter"))) }
    }

    server.addTool("get_class_source",
        "Get full decompiled Java source of a class.",
        mkSchema { required("class_name"); prop("class_name", "string", "Fully qualified class name") }
    ) { req ->
        wrap { engine.getClassSource(req.arguments.str("class_name")) }
    }

    server.addTool("list_methods",
        "List all methods in a class with signatures and access modifiers.",
        mkSchema { required("class_name"); prop("class_name", "string", "Fully qualified class name") }
    ) { req ->
        wrap { json.encodeToString(engine.listMethods(req.arguments.str("class_name"))) }
    }

    server.addTool("get_method_source",
        "Get decompiled source of a specific method.",
        mkSchema {
            required("class_name", "method_name")
            prop("class_name", "string", "Fully qualified class name")
            prop("method_name", "string", "Method name")
        }
    ) { req ->
        wrap { engine.getMethodSource(req.arguments.str("class_name"), req.arguments.str("method_name")) }
    }

    server.addTool("get_class_summary",
        "Get structured summary: superclass, interfaces, counts, component type.",
        mkSchema { required("class_name"); prop("class_name", "string", "Fully qualified class name") }
    ) { req ->
        wrap { json.encodeToString(engine.getClassSummary(req.arguments.str("class_name"))) }
    }

    server.addTool("search_classes",
        "Search class names by substring or regex.",
        mkSchema {
            required("query")
            prop("query", "string", "Search query")
            prop("is_regex", "boolean", "Treat as regex (default: false)")
        }
    ) { req ->
        wrap {
            json.encodeToString(engine.searchClasses(
                req.arguments.str("query"),
                req.arguments.optBool("is_regex") ?: false
            ))
        }
    }

    server.addTool("search_strings",
        "Search string constants across all classes. Finds URLs, API keys, secrets.",
        mkSchema { required("query"); prop("query", "string", "Substring to search") }
    ) { req ->
        wrap { json.encodeToString(engine.searchStrings(req.arguments.str("query"))) }
    }

    server.addTool("get_callers",
        "Get all methods that call a target method. Supports fuzzy matching.",
        mkSchema { required("method_signature"); prop("method_signature", "string", "Method signature or partial name") }
    ) { req ->
        wrap { json.encodeToString(engine.getCallers(req.arguments.str("method_signature"))) }
    }

    server.addTool("get_callees",
        "Get all methods called by a given method.",
        mkSchema { required("method_signature"); prop("method_signature", "string", "Method signature or partial name") }
    ) { req ->
        wrap { json.encodeToString(engine.getCallees(req.arguments.str("method_signature"))) }
    }




    server.addTool("search_method_by_name", "Search method across classes", mkSchema { required("query"); prop("query", "string", "Method name"); prop("is_regex", "boolean", "Treat as regex") }) { req ->
        wrap { json.encodeToString(engine.searchMethodByName(req.arguments.str("query"), req.arguments.optBool("is_regex") ?: false)) }
    }
    server.addTool("search_classes_by_keyword", "Search for classes whose source contains a keyword", mkSchema { required("keyword"); prop("keyword", "string", "Keyword"); prop("limit", "integer", "Limit (default: 50)"); prop("offset", "integer", "Offset (default: 0)") }) { req ->
        wrap { json.encodeToString(engine.searchClassesByKeyword(req.arguments.str("keyword"), req.arguments.optInt("limit") ?: 50, req.arguments.optInt("offset") ?: 0)) }
    }
    server.addTool("get_fields_of_class", "List fields in a class", mkSchema { required("class_name"); prop("class_name", "string", "Fully qualified class name") }) { req ->
        wrap { json.encodeToString(engine.getFieldsOfClass(req.arguments.str("class_name"))) }
    }
    server.addTool("get_smali_of_class", "Fetch smali of class", mkSchema { required("class_name"); prop("class_name", "string", "Fully qualified class name") }) { req ->
        wrap { engine.getSmaliOfClass(req.arguments.str("class_name")) }
    }
    server.addTool("get_main_activity_class", "Fetch main activity from AndroidManifest", mkSchema {}) { req ->
        wrap { engine.getMainActivityClass() }
    }

    server.addTool("get_main_application_classes_names", "Fetch all the main application classes names", mkSchema {}) { req ->
        wrap { json.encodeToString(engine.getMainApplicationClassesNames()) }
    }
    server.addTool("get_android_manifest", "Retrieve the AndroidManifest.xml content", mkSchema {}) { req ->
        wrap { engine.getAndroidManifest() }
    }
    server.addTool("get_manifest_component", "Retrieve specific manifest component", mkSchema { required("component_type"); prop("component_type", "string", "Component type (e.g., activity, service)") }) { req ->
        wrap { json.encodeToString(engine.getManifestComponent(req.arguments.str("component_type"))) }
    }
    server.addTool("get_strings", "Fetches the strings.xml file", mkSchema {}) { req ->
        wrap { engine.getStrings() }
    }
    server.addTool("get_all_resource_file_names", "Retrieve all resource file names", mkSchema {}) { req ->
        wrap { json.encodeToString(engine.getAllResourceFileNames()) }
    }
    server.addTool("get_resource_file", "Retrieve resource file content", mkSchema { required("name"); prop("name", "string", "Original file name (e.g., AndroidManifest.xml)") }) { req ->
        wrap { engine.getResourceFile(req.arguments.str("name")) }
    }

    server.addTool("xrefs_to_class", "Find all references to a class", mkSchema { required("class_name"); prop("class_name", "string", "Class name"); prop("limit", "integer", "Limit"); prop("offset", "integer", "Offset") }) { req ->
        wrap { json.encodeToString(engine.xrefsToClass(req.arguments.str("class_name"), req.arguments.optInt("limit") ?: 50, req.arguments.optInt("offset") ?: 0)) }
    }
    server.addTool("xrefs_to_method", "Find all references to a method", mkSchema { required("method_signature"); prop("method_signature", "string", "Method signature"); prop("limit", "integer", "Limit"); prop("offset", "integer", "Offset") }) { req ->
        wrap { json.encodeToString(engine.xrefsToMethod(req.arguments.str("method_signature"), req.arguments.optInt("limit") ?: 50, req.arguments.optInt("offset") ?: 0)) }
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
