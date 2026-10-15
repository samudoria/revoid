# Revoid: JADX Headless MCP Server

Revoid is a fully headless, analysis-first Model Context Protocol (MCP) server for Android reverse engineering, built on top of `jadx-core`.

Unlike UI-based decompiler tools, this server is designed specifically for Large Language Models (LLMs) to programmatically analyze, query, and navigate APK and DEX files deterministically. It behaves like a program analysis database interface.

## Features

- **Headless-First**: Uses the `jadx-core` library directly for high-performance, headless decompilation.
- **In-Memory Indexing**: Automatically builds indexes for classes, methods, and strings upon loading an APK.
- **Call Graph Analysis**: Computes forward and reverse execution paths (callers and callees) programmatically from bytecode `INVOKE` instructions.
- **Context-Window Optimized**: All list APIs (classes, methods, strings) are paginated to prevent LLM context bloat.
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

## Configuration (OpenCode)

Add a local MCP server to `opencode.json` (project root or `~/.config/opencode/opencode.json`):

```json
{
  "$schema": "https://opencode.ai/config.json",
  "mcp": {
    "jadx": {
      "type": "local",
      "command": ["java", "-jar", "/path/to/jadx_headless_mcp/build/libs/jadx-headless-mcp-all.jar"],
      "enabled": true
    }
  }
}
```

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
| `get_callers` | Methods that call a target (exact by default; `exact=false` for substring). Paginated. |
| `get_callees` | Get all methods called by a given method. |
| `get_server_info` | Revoid/jadx versions and capabilities (`editing: true`). |
| `resolve_node` | Display or raw name → raw ref `{refType, declClass, shortId}`. |
| `rename_node` | Rename a class/method/field; empty name resets. Refreshes affected classes. |
| `add_comment` | Add a comment to a class/method/field (not anonymous classes). |
| `list_annotations` / `remove_annotation` | List or remove renames/comments (by index). |
| `save_project` / `load_project` | Write/read a jadx-gui `.jadx` file. Saves are confined to `REVOID_OUTPUT_ROOT`. |
| `xrefs_to_class` | Find all cross-references (usages) to a specific class. |
| `xrefs_to_method` | Find all cross-references (callers/usages) to a specific method. |
| `xrefs_to_field` | Find all cross-references (reads/writes) to a specific field. |
| `get_manifest_activities` | Extract all `<activity>` tags from the manifest. |
| `get_manifest_services` | Extract all `<service>` tags from the manifest. |
| `get_manifest_receivers` | Extract all `<receiver>` tags from the manifest. |
| `get_manifest_providers` | Extract all `<provider>` tags from the manifest. |
| `get_manifest_permissions` | Extract all `<uses-permission>` tags from the manifest. |
| `get_manifest_exported_components` | Extract all explicitly exported components from the manifest. |
| `get_main_activity_class` | Identify the launchable Main Activity from `AndroidManifest.xml`. |
| `get_main_application_classes_names` | List all classes belonging to the app's base package. |
| `get_resource_strings` | Paginated retrieval of key-value pairs from `res/values/strings.xml`. |
| `get_all_resource_file_names` | Retrieve a paginated list of all resource file names inside the APK. |
| `get_resource_file` | Retrieve the decoded content of a specific resource file. |

## Logging

The server uses STDIO for MCP JSON-RPC communication. Therefore, all standard output (`stdout`) is reserved strictly for protocol messages. Diagnostic logs and jadx warnings are routed exclusively to standard error (`stderr`).

## Names and editing

Classes are identified by raw name (`a.b.Outer$Inner`) and methods by `rawClass#shortId` (e.g. `a.b.C#m(Ljava/lang/String;I)V`). Lookups are exact and accept the raw name, the current display name, or any earlier display name from this session; a miss lists candidates.

## Environment variables

| Variable | Purpose |
|----------|---------|
| `REVOID_OUTPUT_ROOT` | Folder `save_project` may write to (default `~/.revoid_output`) |
| `REVOID_AUTOSAVE` | Edits are saved after every change to `<apk name>.jadx` in the output root and restored on the next `load_apk` of the same APK. Set a path to save elsewhere, `0` to disable |

## Releases

Pushing a tag like `v1.1.0` runs `.github/workflows/release.yml`, which tests, builds the fat JAR and attaches `revoid-<tag>.jar` to a GitHub release. Run it with `java -jar revoid-<tag>.jar`.
