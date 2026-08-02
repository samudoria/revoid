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

/**
 * Result from analyze_native_library.
 */
@Serializable
data class NativeAnalysisSummary(
    val libraryName: String,
    val functionCount: Int,
    val importCount: Int,
    val exportCount: Int,
    val stringCount: Int,
    val globalCount: Int,
    val cached: Boolean
)

@Serializable
enum class ReferenceType {
    CALL, JUMP, DATA, READ, WRITE
}

@Serializable
data class NativeReference(
    val sourceAddress: Long,
    val destinationAddress: Long,
    val type: ReferenceType
)

@Serializable
data class NativeFunction(
    val name: String,
    val address: Long,
    val size: Long = 0,
    val disassembly: String = "",
    val decompiled: String = "",
    val incomingReferences: List<NativeReference> = emptyList(),
    val outgoingReferences: List<NativeReference> = emptyList()
)

@Serializable
data class NativeImport(
    val name: String,
    val address: Long
)

@Serializable
data class NativeExport(
    val name: String,
    val address: Long
)

@Serializable
data class NativeString(
    val address: Long,
    val stringValue: String,
    val length: Int,
    val referenceCount: Int
)

@Serializable
data class NativeGlobal(
    val address: Long,
    val name: String,
    val size: Int,
    val dataType: String
)

@Serializable
data class PaginatedMethods(
    val items: List<MethodInfo>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class PaginatedStringMatches(
    val items: List<StringMatch>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class PaginatedStrings(
    val items: List<String>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class ResourceString(
    val name: String,
    val value: String
)

@Serializable
data class PaginatedResourceStrings(
    val items: List<ResourceString>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class PaginatedNativeFunctions(
    val items: List<NativeFunction>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class PaginatedNativeStrings(
    val items: List<NativeString>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class PaginatedNativeImports(
    val items: List<NativeImport>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class PaginatedNativeExports(
    val items: List<NativeExport>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)
