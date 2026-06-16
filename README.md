# Revoid:JADX Headless MCP Server

Revoid is a fully headless, analysis-first Model Context Protocol (MCP) server for Android reverse engineering, built on top of `jadx-core`.

Unlike UI-based decompiler tools, this server is designed specifically for Large Language Models (LLMs) to programmatically analyze, query, and navigate APK and DEX files deterministically. It behaves like a program analysis database interface.

## Features

- **Headless-First**: Uses the `jadx-core` library directly for high-performance, headless decompilation.
- **In-Memory Indexing**: Automatically builds indexes for classes, methods, and strings upon loading an APK.
- **Call Graph Analysis**: Computes forward and reverse execution paths (callers and callees) programmatically from bytecode `INVOKE` instructions.
- **Deterministic**: Provides consistent results without relying on GUI state, selected text, or HTTP bridges.

## Tech Stack

- **Kotlin & Gradle**
- **MCP Kotlin SDK** (`io.modelcontextprotocol:kotlin-sdk`)
- **jadx-core 1.5.1** (`io.github.skylot:jadx-core`)

## Build

Ensure you have Java 21+ and Gradle installed.

```bash
# Build the fat JAR containing all dependencies
./gradlew shadowJar
```

The compiled JAR will be located at `build/libs/jadx-headless-mcp-all.jar`.

## Testing

The project includes a synthetic test app and a JUnit 5 test suite to verify core engine features (indexing, call graph resolution, and reachability).

```bash
# 1. Compile the test app to generate sample.jar
./test-app/build.sh

# 2. Run the JUnit test suite
./gradlew test
```

## Configuration (Claude Desktop)

To use the server with Claude Desktop, add the following to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "jadx": {
      "command": "java",
      "args": ["-jar", "/path/to/jadx_headless_mcp/build/libs/jadx-headless-mcp-all.jar"]
    }
  }
}
```

Make sure to replace `/path/to/...` with the absolute path to the generated JAR file.

## Available MCP Tools

| Tool | Description |
|------|-------------|
| `load_apk` | Load and analyze an APK/DEX file. **Must be called first.** |
| `list_classes` | List all decompiled classes, optionally filtered by package prefix. |
| `get_class_source` | Get the full decompiled Java source code of a class. |
| `list_methods` | List all methods in a class with signatures and access modifiers. |
| `get_method_source` | Get the decompiled source code of a specific method. |
| `get_fields_of_class` | List all fields in a class with their types and access modifiers. |
| `get_smali_of_class` | Fetch the disassembled Dalvik bytecode (Smali) of a class. |
| `get_class_summary` | Get a structured summary (superclass, interfaces, component type). |
| `search_classes` | Search for classes by name using substring or regex matching. |
| `search_classes_by_keyword` | Search for classes whose source code contains a specific keyword. |
| `search_method_by_name` | Search methods across all classes by substring or regex. |
| `search_strings` | Search for string constants across all classes (URLs, API keys, etc.). |
| `get_callers` | Get all methods that call a target method. |
| `get_callees` | Get all methods called by a given method. |
| `xrefs_to_class` | Find all cross-references (usages) to a specific class. |
| `xrefs_to_method` | Find all cross-references (callers/usages) to a specific method. |
| `xrefs_to_field` | Find all cross-references (reads/writes) to a specific field. |
| `get_android_manifest` | Retrieve the decoded `AndroidManifest.xml` content. |
| `get_manifest_component` | Extract specific components (e.g. `<activity>`, `<service>`) from the manifest. |
| `get_main_activity_class` | Identify the launchable Main Activity from `AndroidManifest.xml`. |
| `get_main_application_classes_names` | List all classes belonging to the app's base package. |
| `get_strings` | Retrieve and decode the `res/values/strings.xml` file. |
| `get_all_resource_file_names` | Retrieve a list of all resource file names inside the APK. |
| `get_resource_file` | Retrieve the decoded content of a specific resource file. |

## Logging

The server uses STDIO for MCP JSON-RPC communication. Therefore, all standard output (`stdout`) is reserved strictly for protocol messages. Diagnostic logs and jadx warnings are routed exclusively to standard error (`stderr`).
