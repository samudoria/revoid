package dev.jadxmcp

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

class NativeEngine(private val jadxEngine: JadxEngine) {

    private val log = LoggerFactory.getLogger(NativeEngine::class.java)

    private val ghidraHome: String? = System.getenv("GHIDRA_HOME") ?: System.getProperty("ghidra.home")
    private val cacheDir = File(System.getProperty("user.home"), ".revoid_native_cache").apply { mkdirs() }

    // In-memory cache of analysis results
    // libName -> Cache
    private val functionsCache = ConcurrentHashMap<String, List<NativeFunction>>()
    private val functionMapCache = ConcurrentHashMap<String, Map<Long, NativeFunction>>()
    private val stringsCache = ConcurrentHashMap<String, List<NativeString>>()
    private val importsCache = ConcurrentHashMap<String, List<NativeImport>>()
    private val exportsCache = ConcurrentHashMap<String, List<NativeExport>>()
    private val globalsCache = ConcurrentHashMap<String, List<NativeGlobal>>()

    init {
        if (ghidraHome.isNullOrEmpty()) {
            log.warn("GHIDRA_HOME is not set. Native analysis will be unavailable.")
        }
    }

    fun isReady(): Boolean {
        return !ghidraHome.isNullOrEmpty()
    }

    fun checkReady() {
        if (!isReady()) {
            throw IllegalStateException("GHIDRA_HOME is not set. Cannot run native analysis.")
        }
    }

    fun listNativeLibraries(): List<String> {
        val apkPath = jadxEngine.currentApkPath ?: throw IllegalStateException("No APK loaded.")
        val libs = mutableListOf<String>()
        ZipFile(apkPath).use { zip ->
            for (entry in zip.entries()) {
                if (!entry.isDirectory && entry.name.endsWith(".so")) {
                    libs.add(entry.name)
                }
            }
        }
        return libs.sorted()
    }

    fun analyzeNativeLibrary(libName: String): NativeAnalysisSummary {
        checkReady()
        val apkPath = jadxEngine.currentApkPath ?: throw IllegalStateException("No APK loaded.")

        // Extract the .so file from the APK
        val extractedSo = extractSoFromApk(apkPath, libName)
        val fileHash = calculateFileHash(extractedSo) + "_v3"
        val cachedJson = File(cacheDir, "${fileHash}.json")

        var isCached = false

        if (cachedJson.exists()) {
            log.info("Loading cached analysis for {}...", libName)
            isCached = true
        } else {
            log.info("Analyzing {} with Ghidra headless...", libName)
            runGhidraHeadless(extractedSo, cachedJson)
        }

        parseJsonOutput(cachedJson, libName)
        
        // Clean up extracted .so if it was just created
        if (!isCached) {
            extractedSo.delete()
        }

        val functions = functionsCache[libName] ?: emptyList()
        val imports = importsCache[libName] ?: emptyList()
        val exports = exportsCache[libName] ?: emptyList()
        val strings = stringsCache[libName] ?: emptyList()
        val globals = globalsCache[libName] ?: emptyList()

        return NativeAnalysisSummary(
            libraryName = libName,
            functionCount = functions.size,
            importCount = imports.size,
            exportCount = exports.size,
            stringCount = strings.size,
            globalCount = globals.size,
            cached = isCached
        )
    }

    fun getFunctions(libName: String, limit: Int = 50, offset: Int = 0): PaginatedNativeFunctions {
        val functions = functionsCache[libName] ?: throw IllegalArgumentException("Library '$libName' not analyzed yet. Call analyze_native_library first.")
        val paged = functions.drop(offset).take(limit)
        return PaginatedNativeFunctions(paged, functions.size, limit, offset)
    }

    fun getStrings(libName: String, limit: Int = 50, offset: Int = 0): PaginatedNativeStrings {
        val strings = stringsCache[libName] ?: throw IllegalArgumentException("Library '$libName' not analyzed yet.")
        val paged = strings.drop(offset).take(limit)
        return PaginatedNativeStrings(paged, strings.size, limit, offset)
    }

    fun getImports(libName: String, limit: Int = 50, offset: Int = 0): PaginatedNativeImports {
        val imports = importsCache[libName] ?: throw IllegalArgumentException("Library '$libName' not analyzed yet.")
        val paged = imports.drop(offset).take(limit)
        return PaginatedNativeImports(paged, imports.size, limit, offset)
    }

    fun getExports(libName: String, limit: Int = 50, offset: Int = 0): PaginatedNativeExports {
        val exports = exportsCache[libName] ?: throw IllegalArgumentException("Library '$libName' not analyzed yet.")
        val paged = exports.drop(offset).take(limit)
        return PaginatedNativeExports(paged, exports.size, limit, offset)
    }

    fun getFunction(libName: String, query: String): NativeFunction {
        val map = functionMapCache[libName] ?: throw IllegalArgumentException("Library '$libName' not analyzed yet.")
        val functions = functionsCache[libName] ?: emptyList()
        
        // Try parsing query as address
        val addr = query.toLongOrNull(16) ?: query.toLongOrNull()
        if (addr != null && map.containsKey(addr)) {
            return map[addr]!!
        }

        // Search by name
        val exactMatch = functions.find { it.name == query }
        if (exactMatch != null) return exactMatch

        val partialMatch = functions.find { it.name.contains(query, ignoreCase = true) }
        if (partialMatch != null) return partialMatch

        throw IllegalArgumentException("Function '$query' not found in $libName")
    }

    // --- Internal Methods ---

    private fun extractSoFromApk(apkPath: String, libName: String): File {
        val tempFile = File.createTempFile("extracted_", "_${File(libName).name}")
        tempFile.deleteOnExit()

        ZipFile(apkPath).use { zip ->
            val entry = zip.getEntry(libName) ?: throw IllegalArgumentException("Library '$libName' not found in APK")
            zip.getInputStream(entry).use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return tempFile
    }

    private fun runGhidraHeadless(soFile: File, outputJson: File) {
        val analyzeHeadless = findAnalyzeHeadless()
        val tempDir = Files.createTempDirectory("revoid_ghidra_").toFile()
        val projectDir = tempDir
        val tempOutput = File(tempDir, "analysis_output.json")
        val scriptDir = extractGhidraScript()

        try {
            val cmd = listOf(
                analyzeHeadless.absolutePath,
                projectDir.absolutePath,
                "NativeAnalysis",
                "-import",
                soFile.absolutePath,
                "-scriptPath",
                scriptDir.absolutePath,
                "-postScript",
                "ExportNativeAnalysis.java",
                tempOutput.absolutePath,
                "-deleteProject"
            )

            log.info("Running Ghidra headless: ${cmd.joinToString(" ")}")

            val pb = ProcessBuilder(cmd)
            pb.redirectErrorStream(true)
            val process = pb.start()

            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    System.err.println("[Ghidra] $line") // stream to stderr
                }
            }

            val exitCode = process.waitFor()
            if (!tempOutput.exists()) {
                log.error("Ghidra analysis failed or no output produced. Exit code: $exitCode")
                throw RuntimeException("Ghidra completed but no output produced.")
            }

            Files.copy(tempOutput.toPath(), outputJson.toPath(), StandardCopyOption.REPLACE_EXISTING)

        } finally {
            projectDir.deleteRecursively()
            scriptDir.deleteRecursively()
        }
    }

    private fun extractGhidraScript(): File {
        val tempScriptDir = Files.createTempDirectory("ghidra_scripts_").toFile()
        tempScriptDir.deleteOnExit()

        val scriptName = "ExportNativeAnalysis.java"
        val tempScript = File(tempScriptDir, scriptName)
        tempScript.deleteOnExit()

        val resourceName = "ghidra_scripts/$scriptName"
        val scriptStream = this::class.java.classLoader.getResourceAsStream(resourceName)
            ?: throw RuntimeException("Could not find $resourceName in resources.")

        scriptStream.use { input ->
            tempScript.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        return tempScriptDir
    }

    private fun findAnalyzeHeadless(): File {
        val support = File(ghidraHome, "support")
        val scriptUnix = File(support, "analyzeHeadless")
        if (scriptUnix.exists()) return scriptUnix
        
        val scriptWin = File(support, "analyzeHeadless.bat")
        if (scriptWin.exists()) return scriptWin
        
        throw IllegalStateException("analyzeHeadless not found in $ghidraHome/support")
    }

    private fun calculateFileHash(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use {
            val buffer = ByteArray(8192)
            var read: Int
            while (it.read(buffer).also { read = it } != -1) {
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun parseJsonOutput(jsonFile: File, libName: String) {
        val content = jsonFile.readText()
        val rootElement = Json.parseToJsonElement(content)

        val newFunctions = mutableListOf<NativeFunction>()
        val newImports = mutableListOf<NativeImport>()
        val newExports = mutableListOf<NativeExport>()
        val newStrings = mutableListOf<NativeString>()
        val newGlobals = mutableListOf<NativeGlobal>()
        val newFunctionMap = mutableMapOf<Long, NativeFunction>()

        var functionsArray: JsonArray? = null
        var rootObj: JsonObject? = null

        if (rootElement is JsonArray) {
            functionsArray = rootElement
        } else if (rootElement is JsonObject) {
            rootObj = rootElement
            if (rootObj.containsKey("functions")) {
                functionsArray = rootObj["functions"]?.jsonArray
            }
        }

        if (functionsArray != null) {
            for (elem in functionsArray) {
                val obj = elem.jsonObject
                
                val incRefs = mutableListOf<NativeReference>()
                if (obj.containsKey("incoming")) {
                    for (incElem in obj["incoming"]!!.jsonArray) {
                        val incObj = incElem.jsonObject
                        val source = incObj["source"]!!.jsonPrimitive.long
                        val typeStr = incObj["type"]!!.jsonPrimitive.content
                        incRefs.add(NativeReference(source, obj["address"]!!.jsonPrimitive.long, ReferenceType.valueOf(typeStr)))
                    }
                }

                val outRefs = mutableListOf<NativeReference>()
                if (obj.containsKey("outgoing")) {
                    for (outElem in obj["outgoing"]!!.jsonArray) {
                        val outObj = outElem.jsonObject
                        val target = outObj["target"]!!.jsonPrimitive.long
                        val typeStr = outObj["type"]!!.jsonPrimitive.content
                        outRefs.add(NativeReference(obj["address"]!!.jsonPrimitive.long, target, ReferenceType.valueOf(typeStr)))
                    }
                }

                val func = NativeFunction(
                    name = obj["name"]!!.jsonPrimitive.content,
                    address = obj["address"]!!.jsonPrimitive.long,
                    size = obj["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                    disassembly = obj["disassembly"]?.jsonPrimitive?.content ?: "",
                    decompiled = obj["decompiled"]?.jsonPrimitive?.content ?: "",
                    incomingReferences = incRefs,
                    outgoingReferences = outRefs
                )
                newFunctions.add(func)
                newFunctionMap[func.address] = func
            }
        }

        if (rootObj != null) {
            if (rootObj.containsKey("imports")) {
                for (elem in rootObj["imports"]!!.jsonArray) {
                    val obj = elem.jsonObject
                    newImports.add(NativeImport(obj["name"]!!.jsonPrimitive.content, obj["address"]!!.jsonPrimitive.long))
                }
            }
            if (rootObj.containsKey("exports")) {
                for (elem in rootObj["exports"]!!.jsonArray) {
                    val obj = elem.jsonObject
                    newExports.add(NativeExport(obj["name"]!!.jsonPrimitive.content, obj["address"]!!.jsonPrimitive.long))
                }
            }
            if (rootObj.containsKey("strings")) {
                for (elem in rootObj["strings"]!!.jsonArray) {
                    val obj = elem.jsonObject
                    newStrings.add(NativeString(
                        address = obj["address"]!!.jsonPrimitive.long,
                        stringValue = obj["stringValue"]!!.jsonPrimitive.content,
                        length = obj["length"]!!.jsonPrimitive.int,
                        referenceCount = obj["referenceCount"]!!.jsonPrimitive.int
                    ))
                }
            }
            if (rootObj.containsKey("globals")) {
                for (elem in rootObj["globals"]!!.jsonArray) {
                    val obj = elem.jsonObject
                    newGlobals.add(NativeGlobal(
                        address = obj["address"]!!.jsonPrimitive.long,
                        name = obj["name"]!!.jsonPrimitive.content,
                        size = obj["size"]!!.jsonPrimitive.int,
                        dataType = obj["dataType"]?.jsonPrimitive?.content ?: ""
                    ))
                }
            }
        }

        functionsCache[libName] = newFunctions
        functionMapCache[libName] = newFunctionMap
        importsCache[libName] = newImports
        exportsCache[libName] = newExports
        stringsCache[libName] = newStrings
        globalsCache[libName] = newGlobals
    }
}
