package dev.jadxmcp

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import jadx.api.JavaField
import jadx.api.JavaMethod
import jadx.api.JavaNode
import jadx.api.data.CodeRefType
import jadx.api.data.CommentStyle
import jadx.api.data.ICodeComment
import jadx.api.data.ICodeRename
import jadx.api.data.IJavaCodeRef
import jadx.api.data.IJavaNodeRef
import jadx.api.data.impl.JadxCodeComment
import jadx.api.data.impl.JadxCodeData
import jadx.api.data.impl.JadxCodeRef
import jadx.api.data.impl.JadxCodeRename
import jadx.api.data.impl.JadxNodeRef
import jadx.core.dex.attributes.AType
import jadx.core.dex.instructions.InsnType
import jadx.core.dex.instructions.InvokeNode
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.zip.ZipFile
import java.io.StringReader
import java.io.StringWriter
import java.util.concurrent.ConcurrentHashMap
import javax.lang.model.SourceVersion
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.xml.sax.InputSource

/**
 * Headless analysis engine wrapping jadx-core.
 *
 * Identity model: classes are keyed by RAW name (`a.b.Outer$Inner`), methods/fields by
 * `rawDeclClass#shortId`. Display names change on rename; lookups accept raw name, current
 * display name, and any earlier display name seen in this session.
 */
class JadxEngine {

    private val log = LoggerFactory.getLogger(JadxEngine::class.java)

    private var decompiler: JadxDecompiler? = null
    var currentApkPath: String? = null
    private var currentApkId: String? = null

    // ── Indexes ──────────────────────────────────────────────
    private val classIndex = ConcurrentHashMap<String, JavaClass>()   // raw name → class (inner included)
    private val methodIndex = ConcurrentHashMap<String, JavaMethod>() // rawCls#shortId
    private val fieldIndex = ConcurrentHashMap<String, JavaField>()   // rawCls#shortId
    private val displayIndex = ConcurrentHashMap<String, String>()    // current display name → raw
    private val oldClassNames = ConcurrentHashMap<String, String>()   // earlier display name → raw
    private val lastClassName = ConcurrentHashMap<String, String>()   // raw → last seen display name
    private val lastMemberName = ConcurrentHashMap<String, String>()  // member id → last seen display name
    private val oldMemberNames = ConcurrentHashMap<String, MutableSet<String>>()

    // top-level raw class name → decompiled source
    private val sourceCache = ConcurrentHashMap<String, String>()

    // ── User edits (mirrors jadx-gui code data) ──────────────
    private val renames = mutableListOf<ICodeRename>()
    private val comments = mutableListOf<ICodeComment>()

    val isLoaded: Boolean get() = decompiler != null

    // ── Loading ──────────────────────────────────────────────

    fun loadApk(path: String, deobfuscation: Boolean = false): ApkInfo {
        val file = File(path)
        require(file.exists()) { "File not found: $path" }
        require(file.isFile) { "Not a file: $path" }

        close()
        log.info("Loading APK: {}", path)

        val args = JadxArgs().apply {
            setInputFile(file)
            threadsCount = Runtime.getRuntime().availableProcessors()
            isSkipResources = false
            isShowInconsistentCode = true
            isDeobfuscationOn = deobfuscation
            isRespectBytecodeAccModifiers = true
            codeData = JadxCodeData()
        }

        val jadx = JadxDecompiler(args)
        jadx.load()

        decompiler = jadx
        currentApkPath = path
        currentApkId = file.nameWithoutExtension
        buildIndexes(jadx)

        log.info("Loaded {} classes from {}", classIndex.size, path)
        return ApkInfo(
            status = "loaded",
            apkId = currentApkId!!,
            apkPath = path,
            classCount = classIndex.size,
            packageName = guessPackageName(),
        )
    }

    fun serverInfo() = ServerInfo(
        revoidVersion = REVOID_VERSION,
        jadxVersion = JadxDecompiler.getVersion(),
        capabilities = mapOf("editing" to true, "pagination" to true),
    )

    fun listNativeLibraries(): List<String> {
        ensureLoaded()
        return ZipFile(currentApkPath!!).use { z -> z.entries().asSequence().map { it.name }.filter { it.endsWith(".so") }.sorted().toList() }
    }

    /** Extracts a .so from the APK into REVOID_OUTPUT_ROOT/native/ so a native-analysis MCP server (e.g. ghidra-headless-mcp) can open it. */
    fun extractNativeLibrary(libName: String): String {
        ensureLoaded()
        val target = File(outputRoot(), "native/" + libName.removePrefix("lib/").replace('/', '_')).canonicalFile
        ZipFile(currentApkPath!!).use { z ->
            val e = z.getEntry(libName) ?: throw IllegalArgumentException("Library '$libName' not found; use list_native_libraries")
            target.parentFile.mkdirs()
            z.getInputStream(e).use { i -> target.outputStream().use { i.copyTo(it) } }
        }
        return target.path
    }

    // ── Classes ──────────────────────────────────────────────

    fun listClasses(filter: String? = null, limit: Int = 50, offset: Int = 0): PaginatedClasses {
        ensureLoaded()
        val all = classIndex.values.filter {
            filter.isNullOrBlank() || it.fullName.startsWith(filter, true) || it.rawName.startsWith(filter, true)
        }.sortedBy { it.fullName }
        return pageClasses(all, limit, offset)
    }

    fun getClassSource(
        className: String,
        startLine: Int? = null,
        endLine: Int? = null,
        lineNumbers: Boolean = false,
        maxChars: Int = DEFAULT_MAX_CHARS,
    ): String {
        ensureLoaded()
        val lines = getCachedSource(findClass(className)).lines()
        val total = lines.size
        val s = (startLine ?: 1).coerceAtLeast(1)
        val e = (endLine ?: total).coerceAtMost(total)
        require(s <= e) { "Invalid line range $s-$e (class has $total lines)" }
        val width = e.toString().length
        val text = (s..e).joinToString("\n") { n ->
            if (lineNumbers) "${n.toString().padStart(width)}: ${lines[n - 1]}" else lines[n - 1]
        }
        if (text.length <= maxChars) return text
        val cut = text.lastIndexOf('\n', maxChars).let { if (it <= 0) maxChars else it }
        val shown = text.substring(0, cut)
        val lastShown = s + shown.count { it == '\n' }
        return "$shown\n// [truncated: showing lines $s-$lastShown of $total, ${text.length - cut} chars omitted. " +
            "Use start_line/end_line to read more]"
    }

    fun getClassSummary(className: String): ClassSummary {
        ensureLoaded()
        val cls = findClass(className)
        val classNode = cls.classNode
        return ClassSummary(
            name = cls.fullName,
            rawName = cls.rawName,
            superClass = classNode.superClass?.toString(),
            interfaces = classNode.interfaces?.map { it.toString() } ?: emptyList(),
            methodCount = cls.methods.size,
            fieldCount = cls.fields.size,
            isActivity = isAndroidComponent(cls, "Activity"),
            isService = isAndroidComponent(cls, "Service"),
            isBroadcastReceiver = isAndroidComponent(cls, "BroadcastReceiver"),
            isContentProvider = isAndroidComponent(cls, "ContentProvider"),
            isFragment = isAndroidComponent(cls, "Fragment"),
        )
    }

    fun searchClasses(query: String, isRegex: Boolean = false, limit: Int = 50, offset: Int = 0): PaginatedClasses {
        ensureLoaded()
        val match = matcher(query, isRegex)
        val all = classIndex.values.filter { match(it.fullName) || match(it.rawName) }.sortedBy { it.fullName }
        return pageClasses(all, limit, offset)
    }

    fun searchClassesByKeyword(keyword: String, limit: Int = 50, offset: Int = 0): PaginatedClasses {
        ensureLoaded()
        val lower = keyword.lowercase()
        val all = classIndex.values.filter { !it.isInner && getCachedSource(it).lowercase().contains(lower) }
            .sortedBy { it.fullName }
        return pageClasses(all, limit, offset)
    }

    // ── Methods & fields ─────────────────────────────────────

    fun listMethods(className: String, limit: Int = 50, offset: Int = 0): PaginatedMethods {
        ensureLoaded()
        val methods = findClass(className).methods.map { methodInfo(it) }
        return PaginatedMethods(methods.page(limit, offset), methods.size, limit, offset)
    }

    fun getMethodSource(className: String, methodName: String, methodId: String? = null): String {
        ensureLoaded()
        val cls = findClass(className)
        val source = getCachedSource(cls)

        val matching = if (!methodId.isNullOrBlank()) {
            listOf(cls.searchMethodByShortId(methodId)
                ?: throw IllegalArgumentException("Method id '$methodId' not found in '${cls.fullName}'. Ids: ${cls.methods.joinToString { shortId(it) }}"))
        } else {
            cls.methods.filter { nameMatches(it, methodName) }
        }
        if (matching.isEmpty()) throw IllegalArgumentException("Method '$methodName' not found in class '$className'")

        val results = matching.mapNotNull { m ->
            extractMethodSource(source, m)?.let { "// Signature: ${getMethodSignature(m)}  id: ${shortId(m)}\n$it" }
        }
        return if (results.isEmpty()) "// Could not extract method body for '$methodName'. Full class source:\n$source"
        else results.joinToString("\n\n")
    }

    fun getFieldsOfClass(className: String): List<FieldInfo> {
        ensureLoaded()
        return findClass(className).fields.map { field ->
            val mods = field.accessFlags
            val flags = listOfNotNull(
                "public".takeIf { mods.isPublic }, "protected".takeIf { mods.isProtected },
                "private".takeIf { mods.isPrivate }, "static".takeIf { mods.isStatic },
                "final".takeIf { mods.isFinal },
            )
            val info = field.fieldNode.fieldInfo
            FieldInfo(field.name, info.name, info.shortId, field.declaringClass.rawName, field.type.toString(), flags.joinToString(" "))
        }
    }

    fun getSmaliOfClass(className: String): String {
        ensureLoaded()
        return findClass(className).smali ?: "// Smali generation failed or unsupported."
    }

    fun searchMethodByName(query: String, isRegex: Boolean = false, limit: Int = 50, offset: Int = 0): PaginatedMethods {
        ensureLoaded()
        val match = matcher(query, isRegex)
        val methods = methodIndex.values
            .filter { match(it.name) || match(it.methodNode.methodInfo.name) }
            .map { methodInfo(it) }
            .sortedBy { it.fullSignature }
        return PaginatedMethods(methods.page(limit, offset), methods.size, limit, offset)
    }

    // ── Strings ──────────────────────────────────────────────

    /**
     * Search string literals in decompiled source; for methods that failed to decompile,
     * fall back to const-string instructions in the smali.
     */
    fun searchStrings(query: String, limit: Int = 50, offset: Int = 0): PaginatedStringMatches {
        ensureLoaded()
        val results = mutableListOf<StringMatch>()
        val lowerQuery = query.lowercase()

        for (cls in classIndex.values.sortedBy { it.rawName }) {
            try {
                val source = getCachedSource(cls)
                if (!cls.isInner) {
                    for ((idx, line) in source.lines().withIndex()) {
                        if (!line.contains('"') || !line.lowercase().contains(lowerQuery)) continue
                        for (literal in extractStringLiterals(line)) {
                            if (literal.lowercase().contains(lowerQuery)) {
                                results.add(StringMatch(literal, cls.fullName, idx + 1))
                            }
                        }
                    }
                }
                val failed = cls.methods.filter { it.methodNode.contains(AType.JADX_ERROR) }
                    .associateBy { it.methodNode.methodInfo.shortId }
                if (failed.isNotEmpty()) {
                    var current: String? = null
                    for ((idx, line) in (cls.smali ?: "").lines().withIndex()) {
                        val t = line.trim()
                        if (t.startsWith(".method ")) current = t.substringAfterLast(' ')
                        else if (t == ".end method") current = null
                        else if (current in failed && t.startsWith("const-string") && t.lowercase().contains(lowerQuery)) {
                            for (literal in extractStringLiterals(t)) {
                                if (literal.lowercase().contains(lowerQuery)) {
                                    results.add(StringMatch(literal, cls.fullName, idx + 1, "smali", mid(failed[current]!!)))
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log.debug("Failed to search strings in {}: {}", cls.rawName, e.message)
            }
        }
        return PaginatedStringMatches(results.page(limit, offset), results.size, limit, offset)
    }

    // ── Resources & Manifest ─────────────────────────────────

    fun getAllResourceFileNames(limit: Int = 50, offset: Int = 0): PaginatedStrings {
        ensureLoaded()
        val names = decompiler?.resources?.map { it.originalName } ?: emptyList()
        return PaginatedStrings(names.page(limit, offset), names.size, limit, offset)
    }

    fun getResourceFile(name: String): String {
        ensureLoaded()
        val res = decompiler?.resources?.find { it.originalName == name }
            ?: throw IllegalArgumentException("Resource file '$name' not found.")
        return res.loadContent().text?.codeStr ?: "// Resource could not be decoded as text."
    }

    fun getResourceStrings(limit: Int = 50, offset: Int = 0): PaginatedResourceStrings {
        ensureLoaded()
        val res = decompiler?.resources?.find { it.originalName.endsWith("res/values/strings.xml") }
            ?: throw IllegalArgumentException("strings.xml not found. Are resources parsed?")
        val xml = res.loadContent().text?.codeStr ?: return PaginatedResourceStrings(emptyList(), 0, limit, offset)
        val pattern = Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        val results = pattern.findAll(xml).map { ResourceString(it.groupValues[1], it.groupValues[2]) }.toList()
        return PaginatedResourceStrings(results.page(limit, offset), results.size, limit, offset)
    }

    internal fun getAndroidManifest(): String {
        ensureLoaded()
        val res = decompiler?.resources?.find { it.originalName == "AndroidManifest.xml" }
            ?: throw IllegalArgumentException("AndroidManifest.xml not found")
        return res.loadContent().text?.codeStr ?: "// Failed to decode AndroidManifest.xml"
    }

    private fun manifestRoot(): Element {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        // The manifest comes from an untrusted APK: no DTDs / external entities.
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        return f.newDocumentBuilder().parse(InputSource(StringReader(getAndroidManifest()))).documentElement
    }

    private fun elements(root: Element, tag: String): List<Element> =
        root.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it) as Element } }

    private fun resolveComponentName(pkg: String, name: String): String = when {
        name.startsWith(".") -> pkg + name
        !name.contains('.') && pkg.isNotEmpty() -> "$pkg.$name"
        else -> name
    }

    private fun serialize(e: Element): String {
        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        t.setOutputProperty(OutputKeys.INDENT, "yes")
        return StringWriter().also { t.transform(DOMSource(e), StreamResult(it)) }.toString()
    }

    /** Components of one manifest tag (`activity`, `activity-alias`, `service`, ...); names are resolved to full class names. */
    fun getManifestComponent(componentType: String): List<ManifestComponent> {
        val root = manifestRoot()
        val pkg = root.getAttribute("package")
        return elements(root, componentType).map { e ->
            ManifestComponent(
                type = componentType,
                name = resolveComponentName(pkg, e.getAttribute("android:name").ifEmpty { "Unknown" }),
                exported = e.getAttribute("android:exported").takeIf { it.isNotEmpty() }?.toBoolean(),
                rawXml = serialize(e),
            )
        }
    }

    fun getManifestActivities(): List<ManifestComponent> =
        getManifestComponent("activity") + getManifestComponent("activity-alias")

    fun getManifestPermissions(): List<String> {
        val root = manifestRoot()
        return listOf("uses-permission", "uses-permission-sdk-23")
            .flatMap { tag -> elements(root, tag) }
            .map { it.getAttribute("android:name") }
            .filter { it.isNotEmpty() }
    }

    fun getManifestExportedComponents(): List<ManifestComponent> =
        (getManifestActivities() + listOf("service", "receiver", "provider").flatMap { getManifestComponent(it) })
            .filter { it.exported == true }

    fun getMainActivityClass(): String {
        val root = manifestRoot()
        val pkg = root.getAttribute("package")
        // (class, isLauncher) for every MAIN entry point; LAUNCHER wins over LEANBACK_LAUNCHER
        val candidates = (elements(root, "activity") + elements(root, "activity-alias")).flatMap { comp ->
            val target = comp.getAttribute("android:targetActivity").ifEmpty { comp.getAttribute("android:name") }
            elements(comp, "intent-filter").mapNotNull { f ->
                val isMain = elements(f, "action").any { it.getAttribute("android:name") == "android.intent.action.MAIN" }
                val cats = elements(f, "category").map { it.getAttribute("android:name") }
                when {
                    !isMain -> null
                    "android.intent.category.LAUNCHER" in cats -> resolveComponentName(pkg, target) to true
                    "android.intent.category.LEANBACK_LAUNCHER" in cats -> resolveComponentName(pkg, target) to false
                    else -> null
                }
            }
        }
        return (candidates.firstOrNull { it.second } ?: candidates.firstOrNull())?.first
            ?: throw IllegalArgumentException("No main activity found in manifest")
    }

    fun getMainApplicationClassesNames(): List<String> {
        val pkg = manifestRoot().getAttribute("package")
            .ifEmpty { throw IllegalArgumentException("Package name not found in manifest") }
        return classIndex.values.map { it.fullName }.filter { it.startsWith("$pkg.") }.sorted()
    }

    // ── X-Refs ───────────────────────────────────────────────

    private fun xrefs(nodes: List<JavaNode>, limit: Int, offset: Int): PaginatedXrefs {
        val uses = nodes.mapNotNull { node ->
            when (node) {
                is JavaClass -> XrefInfo(usedInClass = node.fullName, usedInClassRaw = node.rawName)
                is JavaMethod -> XrefInfo(node.declaringClass.fullName, node.declaringClass.rawName, getMethodSignature(node), mid(node))
                is JavaField -> XrefInfo(node.declaringClass.fullName, node.declaringClass.rawName, node.name)
                else -> null
            }
        }.distinct()
        return PaginatedXrefs(uses.page(limit, offset), uses.size, limit, offset)
    }

    fun xrefsToClass(className: String, limit: Int = 50, offset: Int = 0): PaginatedXrefs {
        ensureLoaded()
        return xrefs(findClass(className).useIn, limit, offset)
    }

    fun xrefsToMethod(methodSignature: String, limit: Int = 50, offset: Int = 0, exact: Boolean = true): PaginatedXrefs {
        ensureLoaded()
        return xrefs(resolveMethod(methodSignature, exact).useIn, limit, offset)
    }

    fun xrefsToField(className: String, fieldName: String, limit: Int = 50, offset: Int = 0): PaginatedXrefs {
        ensureLoaded()
        val cls = findClass(className)
        val field = fieldIndex[cls.rawName + "#" + fieldName]
            ?: cls.fields.find { memberNameMatches(fid(it), it.name, it.fieldNode.fieldInfo.name, fieldName) }
            ?: throw IllegalArgumentException("Field '$fieldName' not found in class '$className'")
        return xrefs(field.useIn, limit, offset)
    }

    // ── Call graph ───────────────────────────────────────────

    fun getCallers(methodSignature: String, exact: Boolean = true, limit: Int = 50, offset: Int = 0): PaginatedMethodRefs {
        ensureLoaded()
        val refs = resolveMethod(methodSignature, exact).useIn
            .filterIsInstance<JavaMethod>()
            .map { MethodRef(getMethodSignature(it), mid(it)) }
            .distinct().sortedBy { it.signature }
        return PaginatedMethodRefs(refs.page(limit, offset), refs.size, limit, offset)
    }

    fun getCallees(methodSignature: String, exact: Boolean = true, limit: Int = 50, offset: Int = 0): PaginatedMethodRefs {
        ensureLoaded()
        val mNode = resolveMethod(methodSignature, exact).methodNode
        if (mNode.instructions == null) {
            try { mNode.unload(); mNode.load() } catch (_: Exception) {}
        }
        val insns = mNode.instructions ?: return PaginatedMethodRefs(emptyList(), 0, limit, offset)
        val refs = mutableSetOf<MethodRef>()
        for (insn in insns) {
            if (insn?.type != InsnType.INVOKE) continue
            val info = (insn as InvokeNode).callMth
            val id = info.declClass.rawName + "#" + info.shortId
            val known = methodIndex[id]
            val sig = if (known != null) getMethodSignature(known) else {
                val p = info.argumentsTypes?.joinToString(", ") ?: ""
                "${info.declClass.fullName}.${info.alias}($p):${info.returnType ?: "void"}"
            }
            refs.add(MethodRef(sig, id))
        }
        val sorted = refs.sortedBy { it.signature }
        return PaginatedMethodRefs(sorted.page(limit, offset), sorted.size, limit, offset)
    }

    // ── Live editing ─────────────────────────────────────────

    /** Resolve a display/raw name (and optional member) to a raw jadx node reference. */
    fun resolveNode(className: String, member: String? = null, fuzzy: Boolean = false): RefInfo {
        ensureLoaded()
        return refInfo(nodeRef(resolveMember(findClass(className, fuzzy), member)))
    }

    fun renameNode(className: String, member: String?, newName: String): EditResult {
        ensureLoaded()
        val cls = findClass(className)
        getCachedSource(cls) // anonymous/inline flags are only known after decompilation
        val node = resolveMember(cls, member)
        requireEditable(node)
        if (node is JavaMethod && (node.isConstructor || node.isClassInit)) {
            throw IllegalArgumentException("Constructors and static initializers cannot be renamed")
        }
        require(newName.isEmpty() || SourceVersion.isName(newName)) { "'$newName' is not a valid Java identifier" }

        val ref = nodeRef(node)
        val old = node.name
        renames.removeAll { it.nodeRef == ref && it.codeRef == null }
        if (newName.isNotEmpty()) renames.add(JadxCodeRename(ref, newName)) else node.removeAlias()
        val refreshed = applyEdit(listOf(node) + relatedMethods(node))
        return EditResult(refInfo(ref), describe(node), old, node.name, refreshed)
    }

    fun addComment(className: String, member: String?, text: String, style: String? = null): EditResult {
        ensureLoaded()
        require(text.isNotBlank()) { "Comment text is empty" }
        val cls = findClass(className)
        getCachedSource(cls)
        val node = resolveMember(cls, member)
        requireEditable(node, "Comments on anonymous classes are not rendered")
        val st = style?.let { s ->
            CommentStyle.values().find { it.name.equals(s, true) }
                ?: throw IllegalArgumentException("Unknown style '$s'. One of: ${CommentStyle.values().joinToString()}")
        } ?: CommentStyle.JAVADOC

        val ref = nodeRef(node)
        comments.add(JadxCodeComment(ref, text, st))
        return EditResult(refInfo(ref), describe(node), null, null, applyEdit(listOf(node)))
    }

    fun listAnnotations(): List<AnnotationInfo> {
        ensureLoaded()
        val out = mutableListOf<AnnotationInfo>()
        for (r in renames) {
            out.add(AnnotationInfo(out.size, "rename", refInfo(r.nodeRef), nodeOfRef(r.nodeRef)?.let { describe(it) },
                newName = r.newName, codeRef = r.codeRef?.toString()))
        }
        for (c in comments) {
            out.add(AnnotationInfo(out.size, "comment", refInfo(c.nodeRef), nodeOfRef(c.nodeRef)?.let { describe(it) },
                text = c.comment, style = c.style.name, codeRef = c.codeRef?.toString()))
        }
        return out
    }

    /** Remove one annotation by its index in the current [listAnnotations] output (indexes shift after removal). */
    fun removeAnnotation(index: Int): EditResult {
        ensureLoaded()
        val all = listAnnotations()
        require(index in all.indices) { "No annotation with index $index (have ${all.size})" }
        val a = all[index]
        if (index < renames.size) renames.removeAt(index) else comments.removeAt(index - renames.size)
        val node = nodeOfRef(a.ref.let { JadxNodeRef(IJavaNodeRef.RefType.valueOf(it.refType), it.declClass, it.shortId) })
        if (a.kind == "rename") node?.removeAlias() // jadx keeps the old alias unless told to drop it
        val refreshed = if (node != null) applyEdit(listOf(node) + relatedMethods(node)) else applyEdit(emptyList())
        return EditResult(a.ref, a.target ?: "", null, null, refreshed)
    }

    fun saveProject(path: String): ProjectResult {
        ensureLoaded()
        val root = outputRoot()
        val target = (if (File(path).isAbsolute) File(path) else File(root, path)).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) { "Path must be inside REVOID_OUTPUT_ROOT ($root)" }
        target.parentFile.mkdirs()

        // Keep unrelated jadx-gui settings if the file already exists.
        val base = runCatching { Json.parseToJsonElement(target.readText()).jsonObject }.getOrNull()
        val project = buildJsonObject {
            base?.forEach { (k, v) -> if (k != "codeData") put(k, v) }
            if (base == null) {
                put("projectVersion", 2)
                putJsonArray("files") { add(currentApkPath) }
            }
            putJsonObject("codeData") {
                putJsonArray("renames") { renames.forEach { r ->
                    addJsonObject {
                        put("nodeRef", nodeRefJson(r.nodeRef))
                        r.codeRef?.let { put("codeRef", codeRefJson(it)) }
                        put("newName", r.newName)
                    }
                } }
                putJsonArray("comments") { comments.forEach { c ->
                    addJsonObject {
                        put("nodeRef", nodeRefJson(c.nodeRef))
                        c.codeRef?.let { put("codeRef", codeRefJson(it)) }
                        put("comment", c.comment)
                        put("style", c.style.name)
                    }
                } }
            }
        }
        target.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), project))
        return ProjectResult(target.path, renames.size, comments.size)
    }

    /** Replace current renames/comments with those in a jadx-gui .jadx project file and reload affected classes. */
    fun loadProject(path: String): ProjectResult {
        ensureLoaded()
        val file = File(path)
        require(file.isFile) { "Not a file: $path" }
        val data = Json.parseToJsonElement(file.readText()).jsonObject["codeData"]?.jsonObject
            ?: throw IllegalArgumentException("No codeData section in project file")

        val newRenames = (data["renames"]?.jsonArray ?: JsonArray(emptyList())).map { e ->
            val o = e.jsonObject
            JadxCodeRename(parseNodeRef(o["nodeRef"]!!.jsonObject), parseCodeRef(o["codeRef"]), o["newName"]!!.jsonPrimitive.content)
        }
        val newComments = (data["comments"]?.jsonArray ?: JsonArray(emptyList())).map { e ->
            val o = e.jsonObject
            val style = o["style"]?.jsonPrimitive?.content?.let { CommentStyle.valueOf(it) } ?: CommentStyle.JAVADOC
            JadxCodeComment(parseNodeRef(o["nodeRef"]!!.jsonObject), parseCodeRef(o["codeRef"]), o["comment"]!!.jsonPrimitive.content, style)
        }

        val oldNodes = (renames.map { it.nodeRef } + comments.map { it.nodeRef }).mapNotNull { nodeOfRef(it) }
        renames.forEach { nodeOfRef(it.nodeRef)?.removeAlias() }
        renames.clear(); renames.addAll(newRenames)
        comments.clear(); comments.addAll(newComments)
        val newNodes = (newRenames.map { it.nodeRef } + newComments.map { it.nodeRef }).mapNotNull { nodeOfRef(it) }
        val refreshed = applyEdit(oldNodes + newNodes)
        return ProjectResult(file.path, renames.size, comments.size, refreshed.size)
    }

    // ── Edit internals ───────────────────────────────────────

    private fun outputRoot(): File =
        File(System.getenv("REVOID_OUTPUT_ROOT") ?: File(System.getProperty("user.home"), ".revoid_output").path)
            .apply { mkdirs() }.canonicalFile

    private fun requireEditable(node: JavaNode, what: String = "Anonymous classes are inlined by jadx and cannot be edited") {
        val c = if (node is JavaClass) node else node.declaringClass
        val simple = c.rawName.substringAfterLast('$', "")
        if (c.classNode.isAnonymous || (simple.isNotEmpty() && simple.all(Char::isDigit))) {
            val outer = c.declaringClass?.fullName ?: c.topParentClass.fullName
            throw IllegalArgumentException("$what; edit the outer class '$outer' instead")
        }
    }

    private fun relatedMethods(node: JavaNode): List<JavaNode> =
        if (node is JavaMethod) node.overrideRelatedMethods else emptyList()

    /**
     * Push edits into jadx, invalidate caches, reload every class whose output may change
     * (the nodes' own top-level classes plus everything that uses them), refresh name lookups.
     */
    private fun applyEdit(nodes: Collection<JavaNode>): List<ClassRef> {
        val jadx = decompiler!!
        val tops = linkedSetOf<JavaClass>()
        for (n in nodes) {
            tops.add(n.topParentClass)
            n.useIn.forEach { tops.add(it.topParentClass) }
        }
        jadx.args.codeData = JadxCodeData().also {
            it.renames = renames.toList()
            it.comments = comments.toList()
        }
        jadx.reloadCodeData()
        for (top in tops) {
            sourceCache.remove(top.rawName)
            try { top.reload() } catch (e: Exception) { log.warn("Reload failed for {}: {}", top.rawName, e.message) }
        }
        refreshNames()
        autosave()
        return tops.map { ClassRef(it.fullName, it.rawName) }
    }

    /** REVOID_AUTOSAVE=1 saves to `<apk>.jadx` after every edit; any other value is used as the project path. */
    private fun autosave() {
        val v = System.getenv("REVOID_AUTOSAVE")?.takeIf { it.isNotBlank() && it != "0" && !it.equals("false", true) } ?: return
        val path = if (v == "1" || v.equals("true", true)) "$currentApkId.jadx" else v
        try { saveProject(path) } catch (e: Exception) { log.warn("Autosave to {} failed: {}", path, e.message) }
    }

    private fun nodeRef(node: JavaNode): JadxNodeRef = JadxNodeRef.forJavaNode(node)!!

    private fun refInfo(r: IJavaNodeRef) = RefInfo(r.type.name, r.declaringClass, r.shortId)

    private fun nodeOfRef(r: IJavaNodeRef): JavaNode? = when (r.type) {
        IJavaNodeRef.RefType.CLASS -> classIndex[r.declaringClass]
        IJavaNodeRef.RefType.METHOD -> methodIndex[r.declaringClass + "#" + r.shortId]
        IJavaNodeRef.RefType.FIELD -> fieldIndex[r.declaringClass + "#" + r.shortId]
        else -> null
    }

    private fun describe(n: JavaNode): String = when (n) {
        is JavaMethod -> getMethodSignature(n)
        is JavaField -> n.declaringClass.fullName + "." + n.name
        else -> n.fullName
    }

    private fun nodeRefJson(r: IJavaNodeRef) = buildJsonObject {
        put("refType", r.type.name)
        put("declClass", r.declaringClass)
        r.shortId?.let { put("shortId", it) }
    }

    private fun codeRefJson(r: IJavaCodeRef) = buildJsonObject {
        put("attachType", r.attachType.name)
        put("index", r.index)
    }

    private fun parseNodeRef(o: JsonObject) = JadxNodeRef(
        IJavaNodeRef.RefType.valueOf((o["refType"] ?: o["type"])!!.jsonPrimitive.content),
        o["declClass"]!!.jsonPrimitive.content,
        o["shortId"]?.jsonPrimitive?.contentOrNull,
    )

    private fun parseCodeRef(e: JsonElement?): JadxCodeRef? {
        val o = (e as? JsonObject) ?: return null
        return JadxCodeRef(CodeRefType.valueOf(o["attachType"]!!.jsonPrimitive.content), o["index"]!!.jsonPrimitive.int)
    }

    /** class itself when member is blank; otherwise a method or field by id, raw name, display name or earlier name. */
    private fun resolveMember(cls: JavaClass, member: String?): JavaNode {
        if (member.isNullOrBlank()) return cls
        val id = cls.rawName + "#" + member
        methodIndex[id]?.let { return it }
        fieldIndex[id]?.let { return it }
        val found: List<JavaNode> =
            cls.methods.filter { nameMatches(it, member) } +
                cls.fields.filter { memberNameMatches(fid(it), it.name, it.fieldNode.fieldInfo.name, member) }
        return when (found.size) {
            1 -> found[0]
            0 -> throw IllegalArgumentException("Member '$member' not found in '${cls.fullName}'. " +
                "Members: ${(cls.methods.map { shortId(it) } + cls.fields.map { it.fieldNode.fieldInfo.shortId }).joinToString()}")
            else -> throw IllegalArgumentException("Member '$member' is ambiguous in '${cls.fullName}'; pass one of: " +
                found.joinToString { if (it is JavaMethod) shortId(it) else (it as JavaField).fieldNode.fieldInfo.shortId })
        }
    }

    // ── Name resolution ──────────────────────────────────────

    private fun ensureLoaded() {
        checkNotNull(decompiler) { "No APK loaded. Call load_apk first." }
    }

    /** Exact lookup by raw name, current display name, or earlier display name. Fuzzy only if asked. */
    private fun findClass(className: String, fuzzy: Boolean = false): JavaClass {
        for (n in listOf(className, className.replace('$', '.'))) {
            classIndex[n]?.let { return it }
            (displayIndex[n] ?: oldClassNames[n])?.let { return classIndex.getValue(it) }
        }
        val needle = className.replace('$', '.')
        val candidates = classIndex.values.filter {
            it.fullName.endsWith(".$needle") || it.fullName.contains(needle, true) || it.rawName.contains(className, true)
        }.sortedBy { it.fullName }
        if (fuzzy && candidates.isNotEmpty()) {
            return candidates.firstOrNull { it.fullName.endsWith(".$needle") } ?: candidates.first()
        }
        val hint = if (candidates.isEmpty()) "No similar classes." else
            "Did you mean: " + candidates.take(10).joinToString { "${it.fullName} (raw ${it.rawName})" } +
                if (candidates.size > 10) ", ... (${candidates.size} total)" else ""
        throw IllegalArgumentException("Class '$className' not found (exact match required). $hint")
    }

    private fun nameMatches(m: JavaMethod, name: String) =
        memberNameMatches(mid(m), m.name, m.methodNode.methodInfo.name, name)

    private fun memberNameMatches(id: String, display: String, raw: String, name: String) =
        name == display || name == raw || oldMemberNames[id]?.contains(name) == true

    /**
     * Accepts `rawDeclClass#shortId`, or a display signature `Cls.name(types):ret` (return type optional),
     * or `Cls.name` when unambiguous. With exact=false falls back to substring matching.
     */
    private fun resolveMethod(sig: String, exact: Boolean = true): JavaMethod {
        try {
            return resolveMethodExact(sig)
        } catch (e: IllegalArgumentException) {
            if (exact) throw e
            val s = sig.lowercase()
            return methodIndex.values.filter { getMethodSignature(it).lowercase().contains(s) || mid(it).lowercase().contains(s) }
                .minByOrNull { getMethodSignature(it) } ?: throw e
        }
    }

    private fun resolveMethodExact(sig: String): JavaMethod {
        methodIndex[sig]?.let { return it }
        if ('#' in sig) {
            val cls = findClass(sig.substringBefore('#'))
            return cls.searchMethodByShortId(sig.substringAfter('#'))
                ?: throw IllegalArgumentException("Method id '${sig.substringAfter('#')}' not found in '${cls.fullName}'. Ids: ${cls.methods.joinToString { shortId(it) }}")
        }
        val paren = sig.indexOf('(')
        val head = if (paren < 0) sig else sig.substring(0, paren)
        val dot = head.lastIndexOf('.')
        require(dot > 0) { "Method '$sig' must look like 'pkg.Class.name(types):ret' or 'rawClass#shortId'" }
        val cls = findClass(head.substring(0, dot))
        val name = head.substring(dot + 1)
        var found = cls.methods.filter { nameMatches(it, name) }
        if (paren >= 0) {
            val tail = sig.substring(paren).replace(" ", "")
            found = found.filter { getMethodSignature(it).let { s -> s.substring(s.indexOf('(')).replace(" ", "").startsWith(tail) } }
        }
        return when (found.size) {
            1 -> found[0]
            0 -> throw IllegalArgumentException("No method '$sig' in '${cls.fullName}'. Methods: ${cls.methods.joinToString { getMethodSignature(it) + " [" + shortId(it) + "]" }}")
            else -> throw IllegalArgumentException("Ambiguous method '$sig'; use one of: ${found.joinToString { cls.rawName + "#" + shortId(it) }}")
        }
    }

    private fun buildIndexes(jadx: JadxDecompiler) {
        clearIndexes()
        fun add(cls: JavaClass) {
            classIndex[cls.rawName] = cls
            cls.methods.forEach { methodIndex[mid(it)] = it }
            cls.fields.forEach { fieldIndex[fid(it)] = it }
            cls.innerClasses.forEach { add(it) }
        }
        jadx.classes.forEach { add(it) }
        refreshNames()
        log.info("Indexed {} classes, {} methods", classIndex.size, methodIndex.size)
    }

    /** Rebuild the display-name lookup; remember any display name that changed so it keeps resolving. */
    private fun refreshNames() {
        displayIndex.clear()
        for ((raw, c) in classIndex) {
            val d = c.fullName
            displayIndex[d] = raw
            lastClassName.put(raw, d)?.let { if (it != d) oldClassNames[it] = raw }
        }
        fun track(id: String, display: String) {
            lastMemberName.put(id, display)?.let {
                if (it != display) oldMemberNames.getOrPut(id) { ConcurrentHashMap.newKeySet() }.add(it)
            }
        }
        methodIndex.forEach { (id, m) -> track(id, m.name) }
        fieldIndex.forEach { (id, f) -> track(id, f.name) }
    }

    private fun clearIndexes() {
        classIndex.clear(); methodIndex.clear(); fieldIndex.clear(); displayIndex.clear()
        oldClassNames.clear(); lastClassName.clear(); lastMemberName.clear(); oldMemberNames.clear()
        sourceCache.clear(); renames.clear(); comments.clear()
    }

    private fun getCachedSource(cls: JavaClass): String {
        val top = cls.topParentClass
        return sourceCache.getOrPut(top.rawName) {
            try {
                top.code ?: "// Decompilation failed for ${top.fullName}"
            } catch (e: Exception) {
                log.warn("Decompilation failed for {}: {}", top.fullName, e.message)
                "// Decompilation failed: ${e.message}"
            }
        }
    }

    // ── Formatting helpers ───────────────────────────────────

    private fun shortId(m: JavaMethod) = m.methodNode.methodInfo.shortId
    private fun mid(m: JavaMethod) = m.declaringClass.rawName + "#" + shortId(m)
    private fun fid(f: JavaField) = f.declaringClass.rawName + "#" + f.fieldNode.fieldInfo.shortId

    private fun methodInfo(m: JavaMethod) = MethodInfo(
        name = m.name,
        rawName = m.methodNode.methodInfo.name,
        shortId = shortId(m),
        declClass = m.declaringClass.rawName,
        fullSignature = getMethodSignature(m),
        returnType = m.returnType?.toString() ?: "void",
        parameterTypes = m.arguments?.map { it.toString() } ?: emptyList(),
        accessFlags = formatAccessFlags(m),
    )

    /** `com.example.Class.method(java.lang.String, int):void` — full type names, no simplification. */
    internal fun getMethodSignature(method: JavaMethod): String {
        val params = method.arguments?.joinToString(", ") { it.toString() } ?: ""
        return "${method.declaringClass.fullName}.${method.name}($params):${method.returnType ?: "void"}"
    }

    private fun pageClasses(all: List<JavaClass>, limit: Int, offset: Int) =
        PaginatedClasses(all.page(limit, offset).map { ClassRef(it.fullName, it.rawName) }, all.size, limit, offset)

    private fun <T> List<T>.page(limit: Int, offset: Int) = drop(offset.coerceAtLeast(0)).take(limit.coerceAtLeast(0))

    private fun matcher(query: String, isRegex: Boolean): (String) -> Boolean =
        if (isRegex) Regex(query, RegexOption.IGNORE_CASE).let { r -> { s: String -> r.containsMatchIn(s) } }
        else { s: String -> s.contains(query, ignoreCase = true) }

    private fun formatAccessFlags(method: JavaMethod): String {
        val mods = method.accessFlags
        return listOfNotNull(
            "public".takeIf { mods.isPublic }, "protected".takeIf { mods.isProtected },
            "private".takeIf { mods.isPrivate }, "static".takeIf { mods.isStatic },
            "final".takeIf { mods.isFinal }, "abstract".takeIf { mods.isAbstract },
            "synchronized".takeIf { mods.isSynchronized }, "native".takeIf { mods.isNative },
        ).joinToString(" ")
    }

    /**
     * Extract a method from the class source: start at the line holding the method's definition
     * position (so overloads are told apart), include annotations/comments above it, then brace-match.
     */
    private fun extractMethodSource(classSource: String, method: JavaMethod): String? {
        val pos = method.defPos
        if (pos <= 0 || pos > classSource.length) return null
        val lines = classSource.lines()
        var start = classSource.substring(0, pos).count { it == '\n' }
        while (start > 0 && lines[start - 1].trim().let { it.startsWith("@") || it.startsWith("/*") || it.startsWith("*") || it.startsWith("//") }) start--

        var depth = 0
        var opened = false
        for (i in start until lines.size) {
            for (ch in lines[i]) {
                when {
                    ch == '{' -> { depth++; opened = true }
                    ch == '}' -> depth--
                    ch == ';' && !opened -> return lines.subList(start, i + 1).joinToString("\n") // abstract/native
                }
            }
            if (opened && depth <= 0) return lines.subList(start, i + 1).joinToString("\n")
        }
        return null
    }

    private fun extractStringLiterals(line: String): List<String> {
        val literals = mutableListOf<String>()
        var i = 0
        while (i < line.length) {
            if (line[i] == '"') {
                val sb = StringBuilder()
                i++
                while (i < line.length && line[i] != '"') {
                    if (line[i] == '\\' && i + 1 < line.length) { sb.append(line[i]).append(line[i + 1]); i += 2 }
                    else { sb.append(line[i]); i++ }
                }
                literals.add(sb.toString())
            }
            i++
        }
        return literals
    }

    private fun isAndroidComponent(cls: JavaClass, componentType: String): Boolean {
        val classNode = cls.classNode
        if (classNode.superClass?.toString()?.contains(componentType) == true) return true
        return classNode.interfaces?.any { it.toString().contains(componentType) } ?: false
    }

    private fun guessPackageName(): String? =
        classIndex.values.map { it.fullName }
            .filter { listOf("android.", "java.", "kotlin.", "androidx.", "com.google.", "org.apache.").none { p -> it.startsWith(p) } }
            .mapNotNull { n -> n.split(".").takeIf { it.size >= 3 }?.take(3)?.joinToString(".") }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    fun close() {
        try { decompiler?.close() } catch (_: Exception) {}
        decompiler = null
        currentApkPath = null
        currentApkId = null
        clearIndexes()
    }

    companion object {
        const val DEFAULT_MAX_CHARS = 60_000
    }
}
