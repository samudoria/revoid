package dev.jadxmcp

import kotlinx.serialization.Serializable

/**
 * Response from load_apk tool.
 */
@Serializable
data class ApkInfo(
    val status: String,
    val apkId: String,
    val apkPath: String,
    val classCount: Int,
    val packageName: String? = null,
    val minSdk: String? = null,
    val targetSdk: String? = null,
)

/**
 * Method metadata returned by list_methods.
 */
@Serializable
data class MethodInfo(
    val name: String,
    val fullSignature: String,
    val returnType: String,
    val parameterTypes: List<String>,
    val accessFlags: String,
)

/**
 * Result from search_strings.
 */
@Serializable
data class StringMatch(
    val value: String,
    val className: String,
    val lineNumber: Int? = null,
)


/**
 * LLM-friendly class summary.
 */
@Serializable
data class ClassSummary(
    val name: String,
    val superClass: String? = null,
    val interfaces: List<String> = emptyList(),
    val methodCount: Int = 0,
    val fieldCount: Int = 0,
    val isActivity: Boolean = false,
    val isService: Boolean = false,
    val isBroadcastReceiver: Boolean = false,
    val isContentProvider: Boolean = false,
    val isFragment: Boolean = false,
)

/**
 * Field metadata returned by get_fields_of_class.
 */
@Serializable
data class FieldInfo(
    val name: String,
    val type: String,
    val accessFlags: String,
)

/**
 * A single cross-reference.
 */
@Serializable
data class XrefInfo(
    val usedInClass: String,
    val usedInMethod: String? = null,
    val line: Int? = null,
)

/**
 * Result for paginated cross-references.
 */
@Serializable
data class PaginatedXrefs(
    val items: List<XrefInfo>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

/**
 * Result for paginated string/keyword searches.
 */
@Serializable
data class PaginatedClasses(
    val items: List<String>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

/**
 * Component from AndroidManifest.xml
 */
@Serializable
data class ManifestComponent(
    val type: String, // activity, service, receiver, provider
    val name: String,
    val exported: Boolean? = null,
    val rawXml: String,
)
