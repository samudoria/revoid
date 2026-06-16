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

    @Test
    fun testLoadApkAndListClasses() {
        val classes = engine.listClasses()
        assertTrue(classes.contains("com.example.testapp.MainActivity"), "MainActivity should be listed")
        assertTrue(classes.contains("com.example.testapp.AuthManager"), "AuthManager should be listed")
        assertTrue(classes.contains("com.example.testapp.Crypto"), "Crypto should be listed")
    }

    @Test
    fun testClassSummary() {
        val summary = engine.getClassSummary("com.example.testapp.MainActivity")
        assertEquals("com.example.testapp.MainActivity", summary.name)
        assertTrue(summary.isActivity, "MainActivity should be detected as an Activity component")
        assertEquals("android.app.Activity", summary.superClass)
        
        val cryptoSummary = engine.getClassSummary("com.example.testapp.Crypto")
        assertFalse(cryptoSummary.isActivity, "Crypto should not be an Activity")
    }

    @Test
    fun testSearchStrings() {
        val matches = engine.searchStrings("SUPER_SECRET")
        assertTrue(matches.isNotEmpty(), "Should find the secret string")
        
        val match = matches.find { it.value.contains("SUPER_SECRET_KEY") }
        assertNotNull(match, "Exact string should be found")
        assertEquals("com.example.testapp.Crypto", match!!.className)
    }

    @Test
    fun testCallGraph() {
        val callers = engine.getCallers("com.example.testapp.Crypto.decrypt(String):String")
        assertTrue(callers.isNotEmpty() && callers.first().contains(".login(String, String):boolean"), "Crypto.decrypt should be called by login")

        val callees = engine.getCallees("com.example.testapp.MainActivity.onCreate(Object):void")
        assertTrue(callees.any { it.contains(".login(String, String):boolean") }, "onCreate should call login")
    }



    @Test
    fun testFieldsAndSmali() {
        val fields = engine.getFieldsOfClass("com.example.testapp.Crypto")
        // No fields defined in the original Crypto.java, so it might be empty
        assertNotNull(fields)
        
        val smali = engine.getSmaliOfClass("com.example.testapp.MainActivity")
        assertTrue(smali.contains(".class") && smali.contains(".super"), "Smali code should be generated")
    }

    @Test
    fun testManifestParsing() {
        val manifest = engine.getAndroidManifest()
        assertTrue(manifest.contains("<manifest") && manifest.contains("com.example.testapp"), "Manifest should be parsed")
        
        val mainActivity = engine.getMainActivityClass()
        assertEquals("com.example.testapp.MainActivity", mainActivity, "MainActivity should be correctly identified from intent filters")
        
        val appClasses = engine.getMainApplicationClassesNames()
        assertTrue(appClasses.contains("com.example.testapp.MainActivity"), "App classes should contain MainActivity")
    }

    @Test
    fun testXrefs() {
        val xrefs = engine.xrefsToClass("com.example.testapp.Crypto")
        assertTrue(xrefs.items.any { it.usedInClass == "com.example.testapp.AuthManager" }, "Crypto should be used in AuthManager")
        
        val methodXrefs = engine.xrefsToMethod("com.example.testapp.Crypto.decrypt(String):String")
        assertTrue(methodXrefs.items.any { it.usedInMethod?.contains("login") == true }, "decrypt should be called by login")
    }



    @Test
    fun testSourceAndMethods() {
        val classSource = engine.getClassSource("com.example.testapp.Crypto")
        assertTrue(classSource.contains("class Crypto"), "Should retrieve full class source")

        val methods = engine.listMethods("com.example.testapp.Crypto")
        assertTrue(methods.any { it.name == "decrypt" }, "Should list the decrypt method")

        val methodSource = engine.getMethodSource("com.example.testapp.Crypto", "decrypt")
        assertTrue(methodSource.contains("public static String decrypt(String str)"), "Should retrieve exact method source")
    }

    @Test
    fun testAdvancedSearching() {
        val classes = engine.searchClasses("Crypto")
        assertTrue(classes.contains("com.example.testapp.Crypto"), "Should find Crypto via substring")

        val methods = engine.searchMethodByName("login")
        assertTrue(methods.any { it.name == "login" && it.fullSignature.contains("AuthManager") }, "Should find login method")

        val keywordMatches = engine.searchClassesByKeyword("SUPER_SECRET")
        assertTrue(keywordMatches.items.contains("com.example.testapp.Crypto"), "Should find Crypto by keyword in source")
    }

    @Test
    fun testAdvancedResources() {
        val components = engine.getManifestComponent("activity")
        assertTrue(components.any { it.name.contains("MainActivity") }, "Should extract activity component")

        try {
            engine.getStrings()
            fail("Should throw since sample.jar has no strings.xml")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("strings.xml not found") == true)
        }

        val files = engine.getAllResourceFileNames()
        assertTrue(files.contains("AndroidManifest.xml"), "Should list manifest in resource files")

        val resFile = engine.getResourceFile("AndroidManifest.xml")
        assertTrue(resFile.contains("<manifest"), "Should fetch explicit resource file")
    }



    @Test
    fun testFieldXrefs() {
        // Find xrefs to the 'auth' field in MainActivity
        val xrefs = engine.xrefsToField("com.example.testapp.MainActivity", "auth")
        assertTrue(xrefs.items.isNotEmpty(), "auth field should have references")
        assertTrue(xrefs.items.any { it.usedInMethod?.contains("onCreate") == true }, "auth field should be used in onCreate")
    }
}
