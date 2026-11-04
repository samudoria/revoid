package dev.jadxmcp

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File

class JadxEngineTest {

    companion object {
        private lateinit var engine: JadxEngine

        @JvmStatic
        @BeforeAll
        fun setup() {
            val jarFile = File("test-app/build/sample.jar")
            assertTrue(jarFile.exists(), "sample.jar must exist before running tests")
            
            engine = JadxEngine()
            val info = engine.loadApk(jarFile.absolutePath)
            
            assertEquals("loaded", info.status)
            assertTrue(info.classCount > 0, "Should have loaded some classes")
            assertTrue(engine.isLoaded)
        }

        @JvmStatic
        @AfterAll
        fun teardown() {
            if (::engine.isInitialized) {
                engine.close()
            }
        }
    }

    private val crypto = "com.example.testapp.Crypto"
    private val decrypt = "$crypto.decrypt(java.lang.String):java.lang.String"

    @Test
    fun testLoadApkAndListClasses() {
        val names = engine.listClasses(limit = 1000).items.map { it.name }
        assertTrue(names.containsAll(listOf("com.example.testapp.MainActivity", "com.example.testapp.AuthManager", crypto)))
    }

    @Test
    fun testClassSummary() {
        val summary = engine.getClassSummary("com.example.testapp.MainActivity")
        assertEquals("com.example.testapp.MainActivity", summary.rawName)
        assertTrue(summary.isActivity)
        assertFalse(engine.getClassSummary(crypto).isActivity)
    }

    @Test
    fun testExactClassLookup() {
        val e = assertThrows(IllegalArgumentException::class.java) { engine.getClassSummary("Crypto") }
        assertTrue(e.message!!.contains(crypto), "miss should list candidates")
        assertEquals(crypto, engine.resolveNode("Crypto", fuzzy = true).declClass)
    }

    @Test
    fun testSearchStrings() {
        val match = engine.searchStrings("SUPER_SECRET").items.find { it.value.contains("SUPER_SECRET_KEY") }
        assertNotNull(match)
        assertEquals(crypto, match!!.className)
    }

    @Test
    fun testCallGraph() {
        val callers = engine.getCallers(decrypt).items
        assertTrue(callers.any { it.signature.contains(".login(java.lang.String, java.lang.String):boolean") })
        val byId = engine.getCallers(callers.first().id.let { "$crypto#decrypt(Ljava/lang/String;)Ljava/lang/String;" }).items
        assertEquals(callers, byId)
        assertTrue(engine.getCallees("com.example.testapp.MainActivity.onCreate").items.any { it.signature.contains(".login(") })
        assertThrows(IllegalArgumentException::class.java) { engine.getCallers("decrypt") }
        assertTrue(engine.getCallers("decrypt", exact = false).items.isNotEmpty())
    }

    @Test
    fun testFieldsAndSmali() {
        assertNotNull(engine.getFieldsOfClass(crypto))
        val smali = engine.getSmaliOfClass("com.example.testapp.MainActivity")
        assertTrue(smali.contains(".class") && smali.contains(".super"))
    }

    @Test
    fun testManifestParsing() {
        assertEquals("com.example.testapp.MainActivity", engine.getMainActivityClass())
        assertEquals("com.example.testapp.MainActivity", engine.getManifestComponent("activity").single().name)
        assertTrue(engine.getMainApplicationClassesNames().contains("com.example.testapp.MainActivity"))
    }

    @Test
    fun testXrefs() {
        assertTrue(engine.xrefsToClass(crypto).items.any { it.usedInClass == "com.example.testapp.AuthManager" })
        assertTrue(engine.xrefsToMethod(decrypt).items.any { it.usedInMethod?.contains("login") == true })
        assertTrue(engine.xrefsToField("com.example.testapp.MainActivity", "auth").items.any { it.usedInMethod?.contains("onCreate") == true })
    }

    @Test
    fun testSourceAndMethods() {
        val src = engine.getClassSource(crypto, lineNumbers = true, startLine = 1, endLine = 3)
        assertTrue(src.startsWith("1: "), src)
        assertTrue(engine.getClassSource(crypto, maxChars = 20).contains("[truncated"))
        assertTrue(engine.listMethods(crypto).items.any { it.name == "decrypt" && it.shortId.startsWith("decrypt(") })
        assertTrue(engine.getMethodSource(crypto, "decrypt").contains("public static String decrypt(String str)"))
        assertTrue(engine.getMethodSource(crypto, "decrypt", "decrypt(Ljava/lang/String;)Ljava/lang/String;").contains("decrypt"))
    }

    @Test
    fun testLazyLoadDoesNotDecompile() {
        // Use a distinct jar name so no autosave edits exist for it, and disable the disk cache
        // so this is a guaranteed cold load: loading must only parse raw nodes.
        val lazy = JadxEngine(diskCacheEnabled = false)
        val jarFile = File("test-app/build/sample-lazy.jar")
        jarFile.writeBytes(File("test-app/build/sample.jar").readBytes())
        try {
            lazy.loadApk(jarFile.absolutePath)
            assertEquals(0, lazy.decompileCount(), "loading must not decompile any class")
            assertTrue(lazy.listClasses(limit = 1000).items.isNotEmpty())
            assertEquals(0, lazy.decompileCount(), "listing classes must not decompile")
            lazy.getClassSource(crypto)
            assertEquals(1, lazy.decompileCount(), "reading source must decompile exactly one class")
            lazy.getClassSource(crypto)
            assertEquals(1, lazy.decompileCount(), "second read must come from cache")
        } finally {
            lazy.close()
            jarFile.delete()
        }
    }

    @Test
    fun testResourcesLazyAndManifestPrimed() {
        // isSkipResources=true: only the manifest is decoded eagerly at load (primed into the cache);
        // every other resource is decoded on demand via loadContent when a tool asks for it.
        val lazy = JadxEngine(diskCacheEnabled = false)
        val jarFile = File("test-app/build/sample-res.jar")
        jarFile.writeBytes(File("test-app/build/sample.jar").readBytes())
        try {
            lazy.loadApk(jarFile.absolutePath)
            assertEquals("com.example.testapp.MainActivity", lazy.getMainActivityClass())
            assertTrue(lazy.getAllResourceFileNames().items.contains("AndroidManifest.xml"))
            assertTrue(lazy.getResourceFile("AndroidManifest.xml").contains("<manifest"))
            assertSame(lazy.getAndroidManifest(), lazy.getAndroidManifest(), "manifest must be decoded once and served from cache")
        } finally {
            lazy.close()
            jarFile.delete()
        }
    }

    @Test
    fun testWarmDiskCacheSkipsDecompile() {
        // A fresh cache dir makes the first session a guaranteed cold load. After it decompiles one
        // class and closes (flushing the disk cache), the second session must serve source from disk
        // without re-decompiling anything — proven via the decompile counter, not timing.
        val cacheDir = java.nio.file.Files.createTempDirectory("revoid-warm-test").toFile()
        val jarFile = File("test-app/build/sample-warm.jar")
        jarFile.writeBytes(File("test-app/build/sample.jar").readBytes())
        try {
            val first = JadxEngine(cacheRootOverride = cacheDir)
            try {
                first.loadApk(jarFile.absolutePath)
                first.getClassSource(crypto)
                assertEquals(1, first.decompileCount(), "cold session must decompile the one requested class")
            } finally {
                first.close()
            }

            val second = JadxEngine(cacheRootOverride = cacheDir)
            try {
                second.loadApk(jarFile.absolutePath)
                assertEquals(0, second.decompileCount(), "warm load must not decompile anything")
                second.getClassSource(crypto)
                assertEquals(0, second.decompileCount(), "source must be served from the disk cache")
            } finally {
                second.close()
            }
        } finally {
            jarFile.delete()
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun testBackgroundLoadSoftStatus() {
        // While a background load is in flight, load_status must report state/progress/elapsed_s and
        // other tools must return a soft initializing signal (LoadingException), never a hard error.
        val gated = JadxEngine(diskCacheEnabled = false)
        val jarFile = File("test-app/build/sample-bg.jar")
        jarFile.writeBytes(File("test-app/build/sample.jar").readBytes())
        try {
            gated.loadGateMs = 3000
            gated.loadApkAsync(jarFile.absolutePath)

            var status = gated.loadStatus()
            val deadline = System.currentTimeMillis() + 5000
            while (status["state"] != "loading" && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
                status = gated.loadStatus()
            }
            assertEquals("loading", status["state"])
            assertTrue(status.containsKey("progress"), "load_status must report progress")
            assertTrue(status.containsKey("elapsed_s"), "load_status must report elapsed_s")

            val ex = assertThrows(LoadingException::class.java) { gated.listClasses(limit = 1000) }
            assertTrue(ex.message!!.contains("loading"), "hint should describe the loading state")

            gated.loadGateMs = 0
            val done = System.currentTimeMillis() + 10000
            while (gated.loadStatus()["state"] != "ready" && System.currentTimeMillis() < done) Thread.sleep(20)
            assertEquals("ready", gated.loadStatus()["state"])
            assertTrue(gated.isLoaded)
        } finally {
            gated.loadGateMs = 0
            gated.close()
            jarFile.delete()
        }
    }

    @Test
    fun testLoadAppWhileLoadingReturnsSoftStatus() {
        // Mirrors REVOID_PRELOAD_APK: a background load (started at server startup) is already
        // in flight when the agent calls revoid_load_app; the call must return a soft loading
        // status rather than throw, so the agent polls load_status instead of erroring out.
        val gated = JadxEngine(diskCacheEnabled = false)
        val jarFile = File("test-app/build/sample-bg2.jar")
        jarFile.writeBytes(File("test-app/build/sample.jar").readBytes())
        try {
            gated.loadGateMs = 3000
            gated.loadApkAsync(jarFile.absolutePath)

            val deadline = System.currentTimeMillis() + 5000
            while (gated.loadStatus()["state"] != "loading" && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
            }
            assertEquals("loading", gated.loadStatus()["state"])

            val second = gated.loadApkAsync(jarFile.absolutePath)
            assertEquals("loading", second["state"])
            assertEquals("loading", second["status"])

            gated.loadGateMs = 0
            val done = System.currentTimeMillis() + 10000
            while (gated.loadStatus()["state"] != "ready" && System.currentTimeMillis() < done) Thread.sleep(20)
            assertEquals("ready", gated.loadStatus()["state"])
        } finally {
            gated.loadGateMs = 0
            gated.close()
            jarFile.delete()
        }
    }

    @Test
    fun testSearching() {
        assertTrue(engine.searchClasses("Crypto").items.any { it.rawName == crypto })
        assertTrue(engine.searchMethodByName("login").items.any { it.fullSignature.contains("AuthManager") })
        assertTrue(engine.searchClassesByKeyword("SUPER_SECRET").items.any { it.rawName == crypto })
    }

    @Test
    fun testResources() {
        assertTrue(engine.getAllResourceFileNames().items.contains("AndroidManifest.xml"))
        assertTrue(engine.getResourceFile("AndroidManifest.xml").contains("<manifest"))
    }

    @Test
    fun testServerInfo() {
        assertTrue(engine.serverInfo().capabilities["editing"] == true)
    }

    // Edits mutate shared state, so run them as one ordered scenario.
    @Test
    fun testEditing() {
        val login = "com.example.testapp.AuthManager"
        val r = engine.renameNode(login, "login", "signIn")
        assertEquals("signIn", r.newName)
        assertTrue(r.refreshedClasses.any { it.rawName == "com.example.testapp.MainActivity" }, "callers must be refreshed")
        assertTrue(engine.getClassSource("com.example.testapp.MainActivity").contains("signIn("))
        // raw, current and previous names all keep working
        assertTrue(engine.getMethodSource(login, "login").contains("signIn"))
        assertTrue(engine.getMethodSource(login, "signIn").contains("signIn"))

        engine.renameNode(login, null, "Authenticator")
        assertEquals(login, engine.resolveNode(login).declClass)
        assertEquals(login, engine.resolveNode("com.example.testapp.Authenticator").declClass)
        assertEquals(login, engine.getClassSummary("com.example.testapp.Authenticator").rawName)

        engine.addComment(login, "signIn", "checks the creds")
        assertTrue(engine.getMethodSource(login, "signIn").contains("checks the creds"))
        assertEquals(3, engine.listAnnotations().size)

        assertThrows(IllegalArgumentException::class.java) { engine.renameNode(login, null, "class") }
        assertThrows(IllegalArgumentException::class.java) { engine.renameNode(login, "<init>", "x") }

        // edits are autosaved and restored when the same APK is loaded again
        JadxEngine().let { other ->
            other.loadApk(File("test-app/build/sample.jar").absolutePath)
            assertTrue(other.listAnnotations().size >= 3, "autosave restore should include the edits from this session")
            other.close()
        }

        val saved = engine.saveProject("revoid-test.jadx")
        assertEquals(2, saved.renames)
        engine.listAnnotations().indices.reversed().forEach { engine.removeAnnotation(it) }
        assertTrue(
            engine.getMethodSource(login, "login").contains("boolean login("),
            "method alias should be removed after clearing annotations",
        )
        assertTrue(engine.getClassSource(login).contains("class AuthManager"))
        assertEquals(0, engine.listAnnotations().size)

        engine.loadProject(saved.path)
        assertTrue(engine.getClassSource(login).contains("class Authenticator"))
        engine.listAnnotations().indices.reversed().forEach { engine.removeAnnotation(it) }
        java.io.File(saved.path).delete()
    }
}
