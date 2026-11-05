package dev.jadxmcp

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Regression tests for the "load reports ready, next tool says No APK loaded" race. The model
 * fires tool calls concurrently, so reads must never observe the loader's transient state.
 */
class ConcurrentEngineTest {

    private val sampleJar = File("test-app/build/sample.jar")

    private fun newEngine() = JadxEngine(diskCacheEnabled = false)

    /** A unique copy so autosave/disk state from other tests can't leak in. */
    private fun freshJar(): File {
        assertTrue(sampleJar.exists(), "sample.jar must exist before running tests")
        val f = Files.createTempFile("revoid-concurrent", ".jar").toFile()
        f.writeBytes(sampleJar.readBytes())
        return f
    }

    @Test
    fun `concurrent burst after load never reports no apk`() {
        val jar = freshJar()
        val engine = newEngine()
        try {
            assertEquals("loaded", engine.loadApkAsync(jar.absolutePath)["status"])

            val pool = Executors.newFixedThreadPool(8)
            try {
                repeat(100) {
                    val start = CountDownLatch(1)
                    val calls = listOf<() -> Any>(
                        { engine.getMainActivityClass() },
                        { engine.getManifestPermissions() },
                        { engine.getMainApplicationClassesNames() },
                        { engine.getMainActivityClass() }, // duplicate of the first call
                    )
                    val futures = calls.map { call -> pool.submit<Any> { start.await(); call() } }
                    start.countDown()
                    futures.forEach { f ->
                        assertNotNull(f.get(60, TimeUnit.SECONDS))
                    }
                }
            } finally {
                pool.shutdownNow()
            }
        } finally {
            engine.close()
            jar.delete()
        }
    }

    @Test
    fun `reads during load wait and never report no apk`() {
        val jar = freshJar()
        val engine = newEngine()
        engine.loadGateMs = 500
        try {
            val loader = Thread { engine.loadApkAsync(jar.absolutePath) }
            loader.start()
            Thread.sleep(100) // let the loader enter LOADING and take the engine lock

            val pool = Executors.newFixedThreadPool(4)
            try {
                val futures = (1..4).map { pool.submit<String> { engine.getMainActivityClass() } }
                futures.forEach { f ->
                    try {
                        assertEquals("com.example.testapp.MainActivity", f.get(60, TimeUnit.SECONDS))
                    } catch (e: ExecutionException) {
                        // Waiting is preferred; a soft "still loading" is allowed. A wrong
                        // "No APK loaded" is the bug this test guards against.
                        assertTrue(e.cause is LoadingException, "unexpected failure: ${e.cause}")
                    }
                }
            } finally {
                pool.shutdownNow()
            }

            loader.join(60_000)
            assertEquals("ready", engine.loadStatus()["state"])
        } finally {
            engine.close()
            jar.delete()
        }
    }

    @Test
    fun `one engine instance serves both load and analysis`() {
        val jar = freshJar()
        val engine = newEngine()
        try {
            // Every tool handler closes over the single engine passed to registerTools. If a second
            // instance were created per handler, this read (on the same object that loaded) would
            // report no APK.
            engine.loadApk(jar.absolutePath)
            assertEquals("ready", engine.loadStatus()["state"])
            assertEquals("com.example.testapp.MainActivity", engine.getMainActivityClass())
        } finally {
            engine.close()
            jar.delete()
        }
    }
}
