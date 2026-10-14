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
import org.slf4j.LoggerFactory

const val REVOID_VERSION = "1.0.0"

fun main(): Unit = runBlocking {
    val log = LoggerFactory.getLogger("dev.jadxmcp.Main")
    log.info("Starting JADX Headless MCP Server v1.0.0")

    val server = Server(
        serverInfo = Implementation("jadx-headless-mcp", REVOID_VERSION),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true),
            ),
        ),
    )

    val engine = JadxEngine()
    registerTools(server, engine)
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
