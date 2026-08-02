package dev.jadxmcp

import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import jadx.api.JavaMethod
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Headless analysis engine wrapping jadx-core.
 *
 * Responsibilities:
 * - Load APK/DEX files via JadxDecompiler (no GUI)
 * - Build and cache indexes for classes, methods, strings
 * - Delegate call-graph and reachability queries
 * - Cache decompiled source to avoid re-decompilation
 */
class JadxEngine {

    private val log = LoggerFactory.getLogger(JadxEngine::class.java)

    // Active decompiler instance
    private var decompiler: JadxDecompiler? = null
    var currentApkPath: String? = null
    private var currentApkId: String? = null

    // ── Indexes ──────────────────────────────────────────────
    // Class full name → JavaClass
    private val classIndex = ConcurrentHashMap<String, JavaClass>()

    // Method full signature → JavaMethod
    private val methodIndex = ConcurrentHashMap<String, JavaMethod>()

    // Class full name → decompiled source (lazy cache)
    private val sourceCache = ConcurrentHashMap<String, String>()



    // ── Public API ──────────────────────────────────────────

    val isLoaded: Boolean get() = decompiler != null

    /**
     * Load and analyze an APK file.
     */
    fun loadApk(path: String): ApkInfo {
        val file = File(path)
        require(file.exists()) { "File not found: $path" }
        require(file.isFile) { "Not a file: $path" }

        // Close previous decompiler if any
        close()

        log.info("Loading APK: {}", path)

        val args = JadxArgs().apply {
            setInputFile(file)
            // Performance optimizations for headless analysis
            threadsCount = Runtime.getRuntime().availableProcessors()
            isSkipResources = false
            isShowInconsistentCode = true
            isDeobfuscationOn = false
            isRespectBytecodeAccModifiers = true
        }

        val jadx = JadxDecompiler(args)
        jadx.load()

        decompiler = jadx
        currentApkPath = path
        currentApkId = file.nameWithoutExtension

        // Build indexes
        buildIndexes(jadx)



        val classCount = classIndex.size
        log.info("Loaded {} classes from {}", classCount, path)

        return ApkInfo(
            status = "loaded",
            apkId = currentApkId!!,
            apkPath = path,
            classCount = classCount,
            packageName = guessPackageName(),
        )
    }

    /**
     * List all class names, optionally filtered by a package prefix.
     */
    fun listClasses(filter: String? = null, limit: Int = 50, offset: Int = 0): PaginatedClasses {
        ensureLoaded()
        val names = classIndex.keys.sorted()
        val filtered = if (filter.isNullOrBlank()) names else names.filter { it.startsWith(filter, ignoreCase = true) }
        val paged = filtered.drop(offset).take(limit)
        return PaginatedClasses(paged, filtered.size, limit, offset)
    }

    /**
     * Get full decompiled source for a class.
     */
    fun getClassSource(className: String): String {
        ensureLoaded()
        val cls = findClass(className)
        return getCachedSource(cls)
    }

    /**
     * List methods in a class with their metadata.
     */
    fun listMethods(className: String, limit: Int = 50, offset: Int = 0): PaginatedMethods {
        ensureLoaded()
        val cls = findClass(className)
        val methods = cls.methods.map { method ->
            MethodInfo(
                name = method.name,
                fullSignature = getMethodSignature(method),
                returnType = method.returnType?.toString() ?: "void",
                parameterTypes = method.arguments?.map { it.toString() } ?: emptyList(),
                accessFlags = formatAccessFlags(method),
            )
        }
        val paged = methods.drop(offset).take(limit)
        return PaginatedMethods(paged, methods.size, limit, offset)
    }

    /**
     * Get decompiled source of a specific method.
     */
    fun getMethodSource(className: String, methodName: String): String {
        ensureLoaded()
        val cls = findClass(className)
        val source = getCachedSource(cls)

        // Find all methods matching the name
        val matchingMethods = cls.methods.filter { it.name == methodName }
        if (matchingMethods.isEmpty()) {
            throw IllegalArgumentException("Method '$methodName' not found in class '$className'")
        }

        // Extract method bodies from class source using line offsets
        val results = mutableListOf<String>()
        for (method in matchingMethods) {
            val methodSrc = extractMethodSource(source, method)
            if (methodSrc != null) {
                results.add("// Signature: ${getMethodSignature(method)}\n$methodSrc")
            }
        }

        return if (results.isEmpty()) {
            "// Could not extract method body for '$methodName'. Full class source:\n$source"
        } else {
            results.joinToString("\n\n")
        }
    }

    /**
     * Search class names by substring or regex.
     */
    fun searchClasses(query: String, isRegex: Boolean = false): List<String> {
        ensureLoaded()
        return if (isRegex) {
            val pattern = Regex(query, RegexOption.IGNORE_CASE)
            classIndex.keys.filter { pattern.containsMatchIn(it) }.sorted()
        } else {
            classIndex.keys.filter { it.contains(query, ignoreCase = true) }.sorted()
        }
    }

    /**
     * Search string constants across all decompiled classes.
     */
    fun searchStrings(query: String, limit: Int = 50, offset: Int = 0): PaginatedStringMatches {
        ensureLoaded()
        val results = mutableListOf<StringMatch>()
        val lowerQuery = query.lowercase()

        for ((className, cls) in classIndex) {
            try {
                val source = getCachedSource(cls)
                val lines = source.lines()
                for ((idx, line) in lines.withIndex()) {
                    // Look for string literals containing the query
                    if (line.contains('"') && line.lowercase().contains(lowerQuery)) {
                        // Extract string literals from the line
                        val stringLiterals = extractStringLiterals(line)
                        for (literal in stringLiterals) {
                            if (literal.lowercase().contains(lowerQuery)) {
                                results.add(StringMatch(
                                    value = literal,
                                    className = className,
                                    lineNumber = idx + 1,
                                ))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                log.debug("Failed to search strings in {}: {}", className, e.message)
            }
        }

        val paged = results.drop(offset).take(limit)
        return PaginatedStringMatches(paged, results.size, limit, offset)
    }

    /**
     * Get an LLM-friendly summary of a class.
     */
    fun getClassSummary(className: String): ClassSummary {
        ensureLoaded()
        val cls = findClass(className)
        val classNode = cls.classNode
        val superName = classNode.superClass?.toString()
        val ifaceNames = classNode.interfaces?.map { it.toString() } ?: emptyList()
        return ClassSummary(
            name = cls.fullName,
            superClass = superName,
            interfaces = ifaceNames,
            methodCount = cls.methods.size,
            fieldCount = cls.fields.size,
            isActivity = isAndroidComponent(cls, "Activity"),
            isService = isAndroidComponent(cls, "Service"),
            isBroadcastReceiver = isAndroidComponent(cls, "BroadcastReceiver"),
            isContentProvider = isAndroidComponent(cls, "ContentProvider"),
            isFragment = isAndroidComponent(cls, "Fragment"),
        )
    }

    // ── Resources & Manifest ─────────────────────────────────

    fun getAllResourceFileNames(limit: Int = 50, offset: Int = 0): PaginatedStrings {
        ensureLoaded()
        val names = decompiler?.resources?.map { it.originalName } ?: emptyList()
        val paged = names.drop(offset).take(limit)
        return PaginatedStrings(paged, names.size, limit, offset)
    }

    fun getResourceFile(name: String): String {
        ensureLoaded()
        val res = decompiler?.resources?.find { it.originalName == name }
            ?: throw IllegalArgumentException("Resource file '$name' not found.")
        val content = res.loadContent()
        return content.text?.codeStr ?: "// Resource could not be decoded as text."
    }

    fun getResourceStrings(limit: Int = 50, offset: Int = 0): PaginatedResourceStrings {
        ensureLoaded()
        val res = decompiler?.resources?.find { it.originalName.endsWith("res/values/strings.xml") }
            ?: throw IllegalArgumentException("strings.xml not found. Are resources parsed?")
        
        val xml = res.loadContent().text?.codeStr ?: return PaginatedResourceStrings(emptyList(), 0, limit, offset)
        
        val results = mutableListOf<ResourceString>()
        val pattern = Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
        
        pattern.findAll(xml).forEach { match ->
            results.add(ResourceString(match.groupValues[1], match.groupValues[2]))
        }
        
        val paged = results.drop(offset).take(limit)
        return PaginatedResourceStrings(paged, results.size, limit, offset)
    }

    private fun getAndroidManifest(): String {
        ensureLoaded()
        val res = decompiler?.resources?.find { it.originalName == "AndroidManifest.xml" }
            ?: throw IllegalArgumentException("AndroidManifest.xml not found")
        return res.loadContent().text?.codeStr ?: "// Failed to decode AndroidManifest.xml"
    }

    fun getManifestComponent(componentType: String): List<ManifestComponent> {
        val xml = getAndroidManifest()
        val results = mutableListOf<ManifestComponent>()
        val pattern = Regex("<$componentType\\b([^>]*)>(.*?)</$componentType>", RegexOption.DOT_MATCHES_ALL)
        val selfClosingPattern = Regex("<$componentType\\b([^>]*)/>")

        fun extractAttributes(attrs: String, xmlBlock: String) {
            val nameMatch = Regex("android:name=\"([^\"]+)\"").find(attrs)
            val name = nameMatch?.groupValues?.get(1) ?: "Unknown"
            val exportedMatch = Regex("android:exported=\"(true|false)\"").find(attrs)
            val exported = exportedMatch?.groupValues?.get(1)?.toBoolean()
            results.add(ManifestComponent(componentType, name, exported, xmlBlock))
        }

        pattern.findAll(xml).forEach { extractAttributes(it.groupValues[1], it.value) }
        selfClosingPattern.findAll(xml).forEach { extractAttributes(it.groupValues[1], it.value) }
        return results
    }

    fun getManifestPermissions(): List<String> {
        val xml = getAndroidManifest()
        val results = mutableListOf<String>()
        val selfClosingPattern = Regex("<uses-permission\\b([^>]*)/>")
        
        selfClosingPattern.findAll(xml).forEach { match ->
            val attrs = match.groupValues[1]
            val nameMatch = Regex("android:name=\"([^\"]+)\"").find(attrs)
            if (nameMatch != null) {
                results.add(nameMatch.groupValues[1])
            }
        }
        return results
    }

    fun getManifestExportedComponents(): List<ManifestComponent> {
        val results = mutableListOf<ManifestComponent>()
        listOf("activity", "service", "receiver", "provider").forEach { componentType ->
            getManifestComponent(componentType).filter { it.exported == true }.forEach { results.add(it) }
        }
        return results
    }

    fun getMainActivityClass(): String {
        val activities = getManifestComponent("activity")
        val mainActivity = activities.find { 
            it.rawXml.contains("android.intent.action.MAIN") && 
            it.rawXml.contains("android.intent.category.LAUNCHER") 
        } ?: throw IllegalArgumentException("No main activity found in manifest")
        
        var name = mainActivity.name
        if (name.startsWith(".")) {
            val pkgMatch = Regex("package=\"([^\"]+)\"").find(getAndroidManifest())
            val pkg = pkgMatch?.groupValues?.get(1) ?: ""
            name = pkg + name
        } else if (!name.contains(".")) {
            val pkgMatch = Regex("package=\"([^\"]+)\"").find(getAndroidManifest())
            val pkg = pkgMatch?.groupValues?.get(1) ?: ""
            name = "$pkg.$name"
        }
        return name
    }

    fun getMainApplicationClassesNames(): List<String> {
        val pkgMatch = Regex("package=\"([^\"]+)\"").find(getAndroidManifest())
        val pkg = pkgMatch?.groupValues?.get(1) ?: throw IllegalArgumentException("Package name not found in manifest")
        return classIndex.keys.filter { it.startsWith("$pkg.") }.sorted()
    }

    // ── Structure & X-Refs ───────────────────────────────────

    fun getFieldsOfClass(className: String): List<FieldInfo> {
        ensureLoaded()
        val cls = findClass(className)
        return cls.fields.map { field ->
            val flags = mutableListOf<String>()
            val mods = field.accessFlags
            if (mods.isPublic) flags.add("public")
            if (mods.isProtected) flags.add("protected")
            if (mods.isPrivate) flags.add("private")
            if (mods.isStatic) flags.add("static")
            if (mods.isFinal) flags.add("final")
            FieldInfo(field.name, field.type.toString().substringAfterLast('.'), flags.joinToString(" "))
        }
    }

    fun getSmaliOfClass(className: String): String {
        ensureLoaded()
        val cls = findClass(className)
        return cls.smali ?: "// Smali generation failed or unsupported."
    }

    fun searchMethodByName(query: String, isRegex: Boolean = false): List<MethodInfo> {
        ensureLoaded()
        val methods = if (isRegex) {
            val pattern = Regex(query, RegexOption.IGNORE_CASE)
            methodIndex.values.filter { pattern.containsMatchIn(it.name) }
        } else {
            methodIndex.values.filter { it.name.contains(query, ignoreCase = true) }
        }
        return methods.map { method ->
            MethodInfo(
                name = method.name,
                fullSignature = getMethodSignature(method),
                returnType = method.returnType?.toString() ?: "void",
                parameterTypes = method.arguments?.map { it.toString() } ?: emptyList(),
                accessFlags = formatAccessFlags(method),
            )
        }
    }

    fun searchClassesByKeyword(keyword: String, limit: Int = 50, offset: Int = 0): PaginatedClasses {
        ensureLoaded()
        val lowerKeyword = keyword.lowercase()
        val matches = classIndex.keys.filter { className ->
            val source = getCachedSource(findClass(className)).lowercase()
            source.contains(lowerKeyword)
        }.sorted()

        val total = matches.size
        val paged = matches.drop(offset).take(limit)
        return PaginatedClasses(paged, total, limit, offset)
    }

    fun xrefsToClass(className: String, limit: Int = 50, offset: Int = 0): PaginatedXrefs {
        ensureLoaded()
        val cls = findClass(className)
        val uses = cls.useIn.mapNotNull { node ->
            when (node) {
                is JavaClass -> XrefInfo(usedInClass = node.fullName)
                is JavaMethod -> XrefInfo(usedInClass = node.declaringClass.fullName, usedInMethod = getMethodSignature(node))
                is jadx.api.JavaField -> XrefInfo(usedInClass = node.declaringClass.fullName, usedInMethod = node.name)
                else -> null
            }
        }.distinct()

        val total = uses.size
        val paged = uses.drop(offset).take(limit)
        return PaginatedXrefs(paged, total, limit, offset)
    }

    fun xrefsToMethod(methodSignature: String, limit: Int = 50, offset: Int = 0): PaginatedXrefs {
        ensureLoaded()
        val method = methodIndex[methodSignature] ?: methodIndex.entries.find { it.key.contains(methodSignature, ignoreCase = true) }?.value
            ?: throw IllegalArgumentException("Method not found.")
        val uses = method.useIn.mapNotNull { node ->
            when (node) {
                is JavaClass -> XrefInfo(usedInClass = node.fullName)
                is JavaMethod -> XrefInfo(usedInClass = node.declaringClass.fullName, usedInMethod = getMethodSignature(node))
                else -> null
            }
        }.distinct()

        val total = uses.size
        val paged = uses.drop(offset).take(limit)
        return PaginatedXrefs(paged, total, limit, offset)
    }

    fun xrefsToField(className: String, fieldName: String, limit: Int = 50, offset: Int = 0): PaginatedXrefs {
        ensureLoaded()
        val cls = findClass(className)
        val field = cls.fields.find { it.name == fieldName } ?: throw IllegalArgumentException("Field '$fieldName' not found in class '$className'")
        val uses = field.useIn.mapNotNull { node ->
            when (node) {
                is JavaClass -> XrefInfo(usedInClass = node.fullName)
                is JavaMethod -> XrefInfo(usedInClass = node.declaringClass.fullName, usedInMethod = getMethodSignature(node))
                else -> null
            }
        }.distinct()

        val total = uses.size
        val paged = uses.drop(offset).take(limit)
        return PaginatedXrefs(paged, total, limit, offset)
    }

    // ── Call graph & Reachability ────────────────────────────

    fun getCallers(methodSignature: String): List<String> {
        ensureLoaded()
        val method = methodIndex[methodSignature] ?: methodIndex.entries.find { it.key.contains(methodSignature, ignoreCase = true) }?.value
            ?: return emptyList()
        return method.useIn.mapNotNull { node ->
            when (node) {
                is JavaMethod -> getMethodSignature(node)
                else -> null
            }
        }.distinct().sorted()
    }

    fun getCallees(methodSignature: String): List<String> {
        ensureLoaded()
        val method = methodIndex[methodSignature] ?: methodIndex.entries.find { it.key.contains(methodSignature, ignoreCase = true) }?.value
            ?: return emptyList()
        val mNode = method.methodNode
        if (mNode.instructions == null) {
            try {
                mNode.unload()
                mNode.load()
            } catch (_: Exception) {}
        }
        val insns = mNode.instructions ?: return emptyList()
        val callees = mutableSetOf<String>()
        for (insn in insns) {
            if (insn?.type == jadx.core.dex.instructions.InsnType.INVOKE) {
                val inv = insn as jadx.core.dex.instructions.InvokeNode
                val info = inv.callMth
                val cn = info.declClass.fullName
                val p = info.argumentsTypes?.joinToString(", ") { simplifyType(it.toString()) } ?: ""
                val r = simplifyType(info.returnType?.toString() ?: "void")
                callees.add("$cn.${info.name}($p):$r")
            }
        }
        return callees.sorted()
    }
    /**
     * Get all known method signatures (for fuzzy matching).
     */
    fun getAllMethodSignatures(): List<String> {
        ensureLoaded()
        return methodIndex.keys.sorted()
    }

    // ── Internal ─────────────────────────────────────────────

    private fun ensureLoaded() {
        checkNotNull(decompiler) { "No APK loaded. Call load_apk first." }
    }

    private fun findClass(className: String): JavaClass {
        return classIndex[className]
            ?: classIndex.entries.find { it.key.endsWith(".$className") || it.key.endsWith(className) }?.value
            ?: throw IllegalArgumentException(
                "Class '$className' not found. Use list_classes or search_classes to find available classes."
            )
    }

    private fun buildIndexes(jadx: JadxDecompiler) {
        classIndex.clear()
        methodIndex.clear()
        sourceCache.clear()

        for (cls in jadx.classes) {
            classIndex[cls.fullName] = cls
            for (method in cls.methods) {
                val sig = getMethodSignature(method)
                methodIndex[sig] = method
            }
        }
        log.info("Indexed {} classes, {} methods", classIndex.size, methodIndex.size)
    }

    private fun getCachedSource(cls: JavaClass): String {
        return sourceCache.getOrPut(cls.fullName) {
            try {
                cls.code ?: "// Decompilation failed for ${cls.fullName}"
            } catch (e: Exception) {
                log.warn("Decompilation failed for {}: {}", cls.fullName, e.message)
                "// Decompilation failed: ${e.message}"
            }
        }
    }

    /**
     * Build a human-readable method signature.
     * Format: com.example.Class.methodName(ParamType1, ParamType2):ReturnType
     */
    internal fun getMethodSignature(method: JavaMethod): String {
        val className = method.declaringClass.fullName
        val name = method.name
        val params = method.arguments?.joinToString(", ") { simplifyType(it.toString()) } ?: ""
        val ret = simplifyType(method.returnType?.toString() ?: "void")
        return "$className.$name($params):$ret"
    }

    /**
     * Simplify JVM type names for readability.
     * e.g. "java.lang.String" → "String", "int[]" stays "int[]"
     */
    private fun simplifyType(type: String): String {
        return type.substringAfterLast('.')
    }

    private fun formatAccessFlags(method: JavaMethod): String {
        val flags = mutableListOf<String>()
        val mods = method.accessFlags
        if (mods.isPublic) flags.add("public")
        if (mods.isProtected) flags.add("protected")
        if (mods.isPrivate) flags.add("private")
        if (mods.isStatic) flags.add("static")
        if (mods.isFinal) flags.add("final")
        if (mods.isAbstract) flags.add("abstract")
        if (mods.isSynchronized) flags.add("synchronized")
        if (mods.isNative) flags.add("native")
        return flags.joinToString(" ")
    }

    /**
     * Extract a method's source from the class source.
     * Uses text-based heuristic matching of method declarations.
     */
    private fun extractMethodSource(classSource: String, method: JavaMethod): String? {
        val lines = classSource.lines()

        // Try to find method declaration by signature pattern
        val methodName = method.name

        // Build a regex pattern to match the method declaration
        val paramPattern = "\\([^)]*\\)"
        val declPattern = Regex(
            """(public|protected|private|static|final|abstract|synchronized|native|\s)*\s*\S+\s+${Regex.escape(methodName)}\s*$paramPattern"""
        )

        var startLine = -1
        for (i in lines.indices) {
            if (declPattern.containsMatchIn(lines[i]) ||
                (methodName == "<init>" && lines[i].contains(method.declaringClass.name + "(")) ||
                (methodName == "<clinit>" && lines[i].trimStart().startsWith("static {"))) {
                startLine = i
                break
            }
        }

        if (startLine < 0) return null

        // Find the end of the method by brace matching
        var braceDepth = 0
        var foundOpenBrace = false
        var endLine = startLine

        for (i in startLine until lines.size) {
            val line = lines[i]
            for (ch in line) {
                when (ch) {
                    '{' -> { braceDepth++; foundOpenBrace = true }
                    '}' -> braceDepth--
                }
            }
            if (foundOpenBrace && braceDepth <= 0) {
                endLine = i
                break
            }
        }

        if (!foundOpenBrace) {
            // Abstract or native method — just return the declaration line
            return lines[startLine]
        }

        return lines.subList(startLine, endLine + 1).joinToString("\n")
    }

    /**
     * Extract string literals from a line of Java source.
     */
    private fun extractStringLiterals(line: String): List<String> {
        val literals = mutableListOf<String>()
        var i = 0
        while (i < line.length) {
            if (line[i] == '"') {
                val sb = StringBuilder()
                i++ // skip opening quote
                while (i < line.length && line[i] != '"') {
                    if (line[i] == '\\' && i + 1 < line.length) {
                        sb.append(line[i])
                        sb.append(line[i + 1])
                        i += 2
                    } else {
                        sb.append(line[i])
                        i++
                    }
                }
                literals.add(sb.toString())
                i++ // skip closing quote
            } else {
                i++
            }
        }
        return literals
    }

    /**
     * Check if a class is an Android component by examining its superclass.
     */
    private fun isAndroidComponent(cls: JavaClass, componentType: String): Boolean {
        val classNode = cls.classNode
        val superName = classNode.superClass?.toString() ?: return false
        if (superName.contains(componentType)) return true
        val ifaces = classNode.interfaces ?: return false
        return ifaces.any { it.toString().contains(componentType) }
    }

    /**
     * Guess the app's package name from the most common top-level package.
     */
    private fun guessPackageName(): String? {
        val packages = classIndex.keys
            .filter { !it.startsWith("android.") && !it.startsWith("java.") &&
                     !it.startsWith("kotlin.") && !it.startsWith("androidx.") &&
                     !it.startsWith("com.google.") && !it.startsWith("org.apache.") }
            .mapNotNull { name ->
                val parts = name.split(".")
                if (parts.size >= 3) parts.take(3).joinToString(".") else null
            }
            .groupingBy { it }
            .eachCount()
        return packages.maxByOrNull { it.value }?.key
    }

    fun close() {
        try {
            decompiler?.close()
        } catch (_: Exception) {}
        decompiler = null
        currentApkPath = null
        currentApkId = null
        classIndex.clear()
        methodIndex.clear()
        sourceCache.clear()
    }
}
