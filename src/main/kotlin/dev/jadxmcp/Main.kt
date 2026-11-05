package dev.jadxmcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import org.slf4j.Logger
import org.slf4j.LoggerFactory

const val REVOID_VERSION = "1.1.3"

/** A fresh MCP Server wired to [engine] and all tools. Reused by the STDIO and SSE entrypoints. */
fun newServer(engine: JadxEngine): Server {
    val server = Server(
        serverInfo = Implementation("jadx-headless-mcp", REVOID_VERSION),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    )
    registerTools(server, engine)
    return server
}

/**
 * If `REVOID_PRELOAD_APK` is set, kick off a background load right away so an agent
 * that connects shortly after finds the APK already loading/ready (poll `load_status`
 * instead of a cold `revoid_load_app`). Best-effort: an unreadable path only warns.
 */
fun preloadApkFromEnv(engine: JadxEngine, log: Logger) {
    val apkPath = System.getenv("REVOID_PRELOAD_APK")?.trim().orEmpty()
    if (apkPath.isEmpty()) return
    try {
        engine.loadApkAsync(apkPath)
        log.info("REVOID_PRELOAD_APK set; background load started for {}", apkPath)
    } catch (e: RuntimeException) {
        log.warn("REVOID_PRELOAD_APK ignored ({}): {}", apkPath, e.message)
    }
}

fun main(): Unit = runBlocking {
    val log = LoggerFactory.getLogger("dev.jadxmcp.Main")
    log.info("Starting JADX Headless MCP Server v$REVOID_VERSION (STDIO)")

    val engine = JadxEngine()
    preloadApkFromEnv(engine, log)
    val server = newServer(engine)
    log.info("Registered MCP tools, connecting STDIO transport...")

    val transport = StdioServerTransport(
        input = System.`in`.asSource().buffered(),
        output = System.out.asSink().buffered(),
    )

    val done = CompletableDeferred<Unit>()
    val session = server.createSession(transport)
    session.onClose { done.complete(Unit) }

    log.info("JADX Headless MCP Server is running")
    done.await()
    engine.close()
    log.info("Server shutdown")
}
