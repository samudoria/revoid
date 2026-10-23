import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "dev.jadxmcp"
version = "1.1.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    // MCP Kotlin SDK (STDIO + SSE server). Pulls in ktor-server-core/sse transitively.
    implementation("io.modelcontextprotocol:kotlin-sdk:0.13.0")

    // Ktor engine for the SSE build (Netty: supports HTTPS; rest of ktor is transitive via the SDK)
    implementation("io.ktor:ktor-server-netty:3.4.3")

    // JADX core — headless decompiler
    implementation("io.github.skylot:jadx-core:1.5.1")
    implementation("io.github.skylot:jadx-dex-input:1.5.1")
    implementation("io.github.skylot:jadx-java-input:1.5.1")
    implementation("io.github.skylot:jadx-smali-input:1.5.1")

    // Logging (MUST go to stderr only)
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // Kotlin serialization (match MCP SDK transitive versions)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // Kotlin coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // kotlinx-io (required by MCP SDK for STDIO transport)
    implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.9.0")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.test {
    useJUnitPlatform()
    val out = layout.buildDirectory.dir("test-out").get().asFile
    environment("REVOID_OUTPUT_ROOT", out.path)  // autosave target; start clean
    doFirst { out.deleteRecursively() }
}

application {
    mainClass.set("dev.jadxmcp.MainKt")
}

// STDIO build (default): jadx-headless-mcp-all.jar
tasks.shadowJar {
    archiveBaseName.set("jadx-headless-mcp")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
    manifest {
        attributes("Main-Class" to "dev.jadxmcp.MainKt")
    }
}

// SSE/HTTP build: jadx-headless-mcp-sse-all.jar
val sseJar by tasks.registering(ShadowJar::class) {
    archiveBaseName.set("jadx-headless-mcp-sse")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
    from(sourceSets.main.get().output)
    configurations = listOf(project.configurations.runtimeClasspath.get())
    manifest {
        attributes("Main-Class" to "dev.jadxmcp.SseMainKt")
    }
}

tasks.assemble { dependsOn(sseJar) }

kotlin {
    jvmToolchain(21)
}
