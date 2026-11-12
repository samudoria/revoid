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
# Build both fat JARs containing all dependencies
./gradlew shadowJar sseJar
```

Two builds are produced in `build/libs/`:

- `jadx-headless-mcp-all.jar` — **STDIO** transport (default; for Claude Desktop, OpenCode, etc.).
- `jadx-headless-mcp-sse-all.jar` — **HTTP/SSE** transport, with optional HTTPS.

## HTTP/SSE server

Run the SSE build to serve MCP over the network:

```bash
java -jar build/libs/jadx-headless-mcp-sse-all.jar
```

It listens on `http://0.0.0.0:3000/` by default. Point an SSE MCP client at that base URL.

| Variable | Purpose |
|----------|---------|
| `REVOID_HOST` / `REVOID_PORT` | Bind address (default `0.0.0.0`) and port (default `3000`) |
| `REVOID_TLS_KEYSTORE` | Path to a PKCS12/JKS keystore. Set it to serve **HTTPS** instead of HTTP |
| `REVOID_TLS_PASSWORD` | Keystore/key password (required with `REVOID_TLS_KEYSTORE`) |
| `REVOID_TLS_ALIAS` | Key alias in the keystore (default `revoid`) |
| `REVOID_ALLOWED_HOSTS` | Comma-separated `Host`-header allowlist (DNS-rebinding protection). Unset = accept any host |

```bash
# HTTPS example
REVOID_TLS_KEYSTORE=/path/revoid.p12 REVOID_TLS_PASSWORD=secret \
  java -jar build/libs/jadx-headless-mcp-sse-all.jar
```

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
      "command": ["java", "-Xmx6g", "-jar", "/path/to/jadx_headless_mcp/build/libs/jadx-headless-mcp-all.jar"],
      "enabled": true,
      "timeout": 300000
    }
  }
}
```

`-Xmx` matters for large APKs (the JVM default is a quarter of RAM), and `timeout` (ms) is the MCP request timeout.

## Available MCP Tools

| Tool | Description |
|------|-------------|
| `revoid_load_app` | Start loading an APK/DEX file in the background. **Must be called first.** Returns immediately. |
| `load_status` | Load lifecycle: `state` = `idle`/`loading`/`ready`/`error`, plus `progress` (0..1), `elapsed_s`, and (when ready) `apkId`/`classCount`. Poll after `revoid_load_app`. |
| `get_server_info` | Revoid/jadx versions, capabilities, and current `loadState`/`loadedApk`. |
| `list_classes` | List all decompiled classes, optionally filtered by package prefix. |
| `get_class_imports` | List the import statements (fully-qualified type names) from a class's source. |
| `list_methods` | List all methods in a class with signatures and access modifiers. |
| `get_method_source` | Get the decompiled source code of a specific method. |
| `get_fields_of_class` | List all fields in a class with their types and access modifiers. |
| `get_smali_method_signatures` | List the smali method signatures (access flags + name + descriptor) of a class. |
| `get_class_summary` | Get a structured summary (superclass, interfaces, component type). |
| `search_classes` | Preferred search: classes by name using substring or regex. Pass a concrete query. |
| `search_classes_by_keyword` | Index-backed keyword search over class/member names and dex strings; bounded and paginated. |
| `search_method_by_name` | Index-backed method name search, paginated. |
| `search_strings` | Streaming search over the prebuilt dex string index; bounded and paginated. |
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

## Agent workflow

1. Call `revoid_load_app` with the APK/DEX path. It returns immediately (`state: loading`);
   the load runs in the background — only the dex is parsed and indexed, decompilation is lazy
   (per class, on demand).
2. Poll `load_status` until `state: ready`. While loading, every other tool returns a **soft**
   `{"status":"initializing","state":"loading","retry_after_s":2,"hint":...}` instead of a hard
   error, so you can simply retry after the suggested delay instead of treating it as a failure.
3. Once `ready`, run analysis tools normally (`list_classes`, `get_class_summary`, `get_class_source`, …).
   Source for a class is decompiled the first time you ask for it and cached; a repeat `revoid_load_app`
   of the same APK restores the cached source from disk, so nothing is re-decompiled.

## Logging

The server uses STDIO for MCP JSON-RPC communication. Therefore, all standard output (`stdout`) is reserved strictly for protocol messages. Diagnostic logs and jadx warnings are routed exclusively to standard error (`stderr`).

## Names and editing

Classes are identified by raw name (`a.b.Outer$Inner`) and methods by `rawClass#shortId` (e.g. `a.b.C#m(Ljava/lang/String;I)V`). Lookups are exact and accept the raw name, the current display name, or any earlier display name from this session; a miss lists candidates.

## Environment variables

| Variable | Purpose |
|----------|---------|
| `REVOID_OUTPUT_ROOT` | Folder `save_project` may write to (default `~/.revoid_output`) |
| `REVOID_AUTOSAVE` | Edits are saved after every change to `<apk name>.jadx` in the output root and restored on the next `revoid_load_app` of the same APK. Set a path to save elsewhere, `0` to disable |
| `REVOID_CACHE_DIR` | Cross-session cache of decompiled source, keyed by APK sha256 (default `<tmp>/revoid-cache`). Repeat loads of the same APK skip re-decompiling |
| `REVOID_CACHE_TTL_HOURS` | How long a cache entry stays valid (default `24`) |
| `REVOID_CACHE_MAX_MB` | Total cache size cap; oldest entries are evicted (default `1024`) |
| `REVOID_PRELOAD_APK` | APK path to load in the background as soon as the server starts (both STDIO and SSE), so a connecting agent finds it already `loading`/`ready` via `load_status` instead of a cold `revoid_load_app`. Ignored (with a warning) if the path is unreadable |
| `REVOID_TOOL_TIMEOUT_S` | Deadline for full-app scans such as `search_classes_by_keyword` (default `120`). Partial results are returned with `truncated: true` |
| `REVOID_SEARCH_MAX_RESULTS` | Maximum matches materialized per search (default `10000`). Further matches set `truncated: true` |
| `REVOID_KEYWORD_INDEX_MAX_ENTRIES` | Cap on keyword-index entries (default `500000`) to bound memory |
| `REVOID_PARSE_RESOURCES` | Set `1` to parse resources at load. Required for `get_resource_strings`; otherwise it returns `available: false` with a clear message |
| `REVOID_MAX_CONCURRENT_TOOLS` | Maximum tool handlers running at once (default `4`). Extra calls wait instead of exhausting server threads |


## Releases

Pushing a tag like `v1.1.0` runs `.github/workflows/release.yml`, which tests, builds both fat JARs and attaches them to a GitHub release: `revoid-<tag>.jar` (STDIO) and `revoid-sse-<tag>.jar` (HTTP/SSE).
