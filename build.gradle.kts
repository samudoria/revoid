plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.serialization") version "2.3.21"
    application
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

group = "dev.jadxmcp"
version = "1.0.1"

repositories {
    mavenCentral()
    google()
}

dependencies {
    // MCP Kotlin SDK (STDIO server)
    implementation("io.modelcontextprotocol:kotlin-sdk:0.13.0")

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

tasks.shadowJar {
    archiveBaseName.set("jadx-headless-mcp")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
    manifest {
        attributes("Main-Class" to "dev.jadxmcp.MainKt")
    }
}

kotlin {
    jvmToolchain(21)
}
