package dev.jadxmcp

import io.ktor.server.application.Application
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.modelcontextprotocol.kotlin.sdk.server.mcp
import org.slf4j.LoggerFactory
import java.io.File
import java.security.KeyStore

/**
 * HTTP/SSE entrypoint. Serves the MCP SSE transport (GET+POST at the base URL).
 * TLS is enabled when REVOID_TLS_KEYSTORE is set, otherwise plain HTTP (terminate TLS at a proxy).
 *
 *   REVOID_HOST (default 0.0.0.0), REVOID_PORT (default 3000)
 *   REVOID_TLS_KEYSTORE (PKCS12/JKS path), REVOID_TLS_PASSWORD, REVOID_TLS_ALIAS (default revoid)
 */
fun main() {
    val log = LoggerFactory.getLogger("dev.jadxmcp.SseMain")
    val host = System.getenv("REVOID_HOST") ?: "0.0.0.0"
    val port = System.getenv("REVOID_PORT")?.toIntOrNull() ?: 3000
    val engine = JadxEngine()  // one engine shared by all SSE connections (single-user analysis)
    preloadApkFromEnv(engine, log)

    val keystore = System.getenv("REVOID_TLS_KEYSTORE")
    val scheme = if (keystore != null) "https" else "http"
    log.info("Starting JADX Headless MCP Server v$REVOID_VERSION (SSE) on $scheme://$host:$port")

    embeddedServer(Netty, applicationEnvironment { }, configure = {
        if (keystore == null) {
            connector { this.host = host; this.port = port }
        } else {
            val pass = (System.getenv("REVOID_TLS_PASSWORD")
                ?: error("REVOID_TLS_PASSWORD is required when REVOID_TLS_KEYSTORE is set")).toCharArray()
            val alias = System.getenv("REVOID_TLS_ALIAS") ?: "revoid"
            val ksFile = File(keystore)
            val ks = KeyStore.getInstance(ksFile, pass)  // type auto-detected from the file
            sslConnector(ks, alias, { pass }, { pass }) {
                this.host = host; this.port = port; keyStorePath = ksFile
            }
        }
    }, module = { mcpModule(engine) }).start(wait = true)
}

private fun Application.mcpModule(engine: JadxEngine) {
    // SDK installs SSE + content negotiation and advertises the POST endpoint.
    // DNS-rebinding protection defaults to a localhost-only Host allowlist, which rejects a
    // network-facing server; keep it off unless REVOID_ALLOWED_HOSTS pins the hosts to accept.
    val hosts = System.getenv("REVOID_ALLOWED_HOSTS")?.split(",")?.map(String::trim)?.filter(String::isNotEmpty)
    if (hosts.isNullOrEmpty()) {
        mcp(false) { newServer(engine) }
    } else {
        mcp(true, hosts) { newServer(engine) }
    }
}
