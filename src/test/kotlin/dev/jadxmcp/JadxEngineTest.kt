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

        val saved = engine.saveProject("revoid-test.jadx")
        assertEquals(2, saved.renames)
        engine.listAnnotations().indices.reversed().forEach { engine.removeAnnotation(it) }
        assertEquals("login", engine.getMethodSource(login, "login").lines().first { it.contains("(") }.let { "login" })
        assertTrue(engine.getClassSource(login).contains("class AuthManager"))
        assertEquals(0, engine.listAnnotations().size)

        engine.loadProject(saved.path)
        assertTrue(engine.getClassSource(login).contains("class Authenticator"))
        engine.listAnnotations().indices.reversed().forEach { engine.removeAnnotation(it) }
        java.io.File(saved.path).delete()
    }
}
