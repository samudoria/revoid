package dev.jadxmcp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class LoadStateSyncTest {

    private val sampleJar = File("test-app/build/sample.jar")

    private fun newEngine() = JadxEngine(diskCacheEnabled = false)

    /** Copy [source] into a new zip with an extra entry so its sha256 differs (a genuinely different APK). */
    private fun copyWithExtraEntry(source: File, target: File) {
        ZipInputStream(source.inputStream().buffered()).use { input ->
            ZipOutputStream(target.outputStream().buffered()).use { output ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    output.putNextEntry(ZipEntry(entry.name))
                    input.copyTo(output)
                    output.closeEntry()
                }
                output.putNextEntry(ZipEntry("revoid-extra.txt"))
                output.write("different apk".toByteArray())
                output.closeEntry()
            }
        }
    }

    @Test
    fun `close resets READY so status and tools agree`() {
        val engine = newEngine()
        try {
            assertTrue(sampleJar.exists(), "sample.jar must exist before running tests")
            engine.loadApkAsync(sampleJar.absolutePath)
            assertEquals("ready", engine.loadStatus()["state"])
            assertTrue(engine.isLoaded)

            engine.close()

            val status = engine.loadStatus()
            assertEquals("idle", status["state"])
            assertEquals("not_loaded", status["status"])
            assertFalse(engine.isLoaded)

            val e = assertThrows(IllegalStateException::class.java) { engine.listClasses() }
            assertTrue(e.message!!.contains("No APK loaded"), e.message)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `loading the same apk twice is a no-op and stays usable`() {
        val engine = newEngine()
        try {
            val first = engine.loadApkAsync(sampleJar.absolutePath)
            assertEquals("loaded", first["status"])
            val second = engine.loadApkAsync(sampleJar.absolutePath)
            assertEquals("loaded", second["status"])
            assertEquals("ready", engine.loadStatus()["state"])
            assertTrue(engine.isLoaded)
            assertTrue(engine.listClasses(limit = 1000).items.isNotEmpty())
        } finally {
            engine.close()
        }
    }

    @Test
    fun `reloading a different apk tears down the old one and stays usable`() {
        val engine = newEngine()
        val other = Files.createTempFile("revoid-other", ".jar").toFile()
        try {
            copyWithExtraEntry(sampleJar, other)
            engine.loadApkAsync(sampleJar.absolutePath)
            assertEquals("sample", engine.loadStatus()["apkId"])

            val result = engine.loadApkAsync(other.absolutePath)
            assertEquals("loaded", result["status"])
            assertEquals("ready", engine.loadStatus()["state"])
            assertEquals(other.nameWithoutExtension, engine.loadStatus()["apkId"])
            assertEquals(other.absolutePath, engine.currentApkPath)
            assertTrue(engine.isLoaded)
            assertTrue(engine.listClasses(limit = 1000).items.isNotEmpty())
            assertTrue(engine.getMainApplicationClassesNames().contains("com.example.testapp.MainActivity"))
        } finally {
            engine.close()
            other.delete()
        }
    }

    @Test
    fun `error state is reported and tools explain the failure`() {
        val engine = newEngine()
        try {
            // jadx tolerates malformed input (it just loads zero classes), so simulate the terminal
            // ERROR state the loader sets when its worker throws, and verify the diagnostics.
            val stateField = JadxEngine::class.java.getDeclaredField("loadState").apply { isAccessible = true }
            val errorField = JadxEngine::class.java.getDeclaredField("loadError").apply { isAccessible = true }
            stateField.set(engine, LoadState.ERROR)
            errorField.set(engine, "boom")

            val status = engine.loadStatus()
            assertEquals("error", status["state"])
            assertEquals("boom", status["error"])

            val e = assertThrows(IllegalStateException::class.java) { engine.listClasses() }
            assertTrue(e.message!!.startsWith("APK load failed: boom"), e.message)
        } finally {
            engine.close()
        }
    }
}
