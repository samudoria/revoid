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
    val rawName: String,
    val shortId: String,
    val declClass: String,
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
    val origin: String? = null, // "smali" when found in a method that failed to decompile
    val methodId: String? = null,
)


/**
 * LLM-friendly class summary.
 */
@Serializable
data class ClassSummary(
    val name: String,
    val rawName: String,
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
    val rawName: String,
    val shortId: String,
    val declClass: String,
    val type: String,
    val accessFlags: String,
)

/**
 * A single cross-reference.
 */
@Serializable
data class XrefInfo(
    val usedInClass: String,
    val usedInClassRaw: String? = null,
    val usedInMethod: String? = null,
    val usedInMethodId: String? = null,
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
    val items: List<ClassRef>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

/** A class by display name plus its stable raw (original) name. */
@Serializable
data class ClassRef(val name: String, val rawName: String)

/** A method by display signature plus its stable id (`rawDeclClass#shortId`). */
@Serializable
data class MethodRef(val signature: String, val id: String)

@Serializable
data class PaginatedMethodRefs(
    val items: List<MethodRef>,
    val totalCount: Int,
    val limit: Int,
    val offset: Int,
)

/** Raw jadx node reference: declClass is the raw class name; shortId is null for classes. */
@Serializable
data class RefInfo(val refType: String, val declClass: String, val shortId: String? = null)

@Serializable
data class EditResult(
    val ref: RefInfo,
    val target: String,
    val oldName: String? = null,
    val newName: String? = null,
    val refreshedClasses: List<ClassRef>,
)

@Serializable
data class AnnotationInfo(
    val index: Int,
    val kind: String, // rename | comment
    val ref: RefInfo,
    val target: String? = null,
    val newName: String? = null,
    val text: String? = null,
    val style: String? = null,
    val codeRef: String? = null,
)

@Serializable
data class ProjectResult(val path: String, val renames: Int, val comments: Int, val refreshedClasses: Int = 0)

@Serializable
data class ServerInfo(val revoidVersion: String, val jadxVersion: String, val capabilities: Map<String, Boolean>)

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

