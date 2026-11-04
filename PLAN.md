# PLAN — Fast revoid APK load (lazy indexing, no eager decompile)

## Status

- **Phase 0** (plan + API verification) — done (`8ade84a`)
- **Phase 1a** (raw-node index, lazy decompile) — implemented (`310770f`); lazy test added
  in `JadxEngineTest.kt`. Not built locally (CI build; see note at bottom).
- **Phase 1b** (remove deobfuscation) — implemented in `JadxEngine.kt` + `ToolRegistry.kt`
  (revoid) and `src/registries/mcp.py` (ArkAudit, working-tree change, uncommitted).
- **Phase 2** (lazy resources) — implemented: `isSkipResources = true` in `loadApk` (skips the
  slow `RootNode.processResources`; the `ResourceFile` list is still built lazily via
  `JadxDecompiler.getResources()` and content is decoded per-file via `loadContent`); manifest
  is primed eagerly at load and cached (`manifestCache`); resource-lazy test added.
- **Phase 3** (disk cache) — implemented: in-session reuse keyed by APK sha256; cross-session
  disk cache under `REVOID_CACHE_DIR` (default `<tmp>/revoid-cache`, per-hash `sources.json`)
  with TTL (`REVOID_CACHE_TTL_HOURS`, 24h) and size/age cleanup (`REVOID_CACHE_MAX_MB`, 1GB);
  reuses the existing autosave `save_project`/`load_project` machinery for edits; `close()`/
  `clearIndexes()` never wipe the disk cache; warm-cache test uses the decompile counter.
- Phase 4 — pending.

## Problem

`JadxEngine.buildIndexes()` (JadxEngine.kt:842) walks `jadx.classes` and touches
`cls.methods` / `cls.fields` / `cls.innerClasses` on every `JavaClass`. In jadx-core 1.5.1
those accessors call `JavaClass.load()` → `ClassNode.decompile()`, so **indexing decompiles
every class up front**. A large APK spends the whole load time decompiling code that the
analyst may never read.

Secondary costs (later phases): resources are decoded eagerly (`isSkipResources = false`),
no cross-load cache, and tools hard-fail while a load is in progress.

## Goals

1. `revoid_load_app` returns as soon as the raw dex model is parsed (fast); decompilation
   happens lazily per class, only when a tool needs source/code/use-in.
2. Tool outputs stay byte-identical to today (same keys, names, signatures, errors).
3. Resources become lazy; manifest stays eagerly available.
4. A disk cache makes repeat loads of the same APK nearly free.
5. Loads run in the background with a soft `initializing` state instead of hard errors.
6. `deobfuscation` is removed everywhere.

## Pinned versions

- jadx-core / dex-input / java-input / smali-input: **1.5.1**
- Kotlin / serialization plugin: **2.3.21**
- Gradle wrapper: **8.10**; JDK toolchain 21
- MCP Kotlin SDK 0.13.0 (unchanged)

## Verified jadx-core 1.5.1 API facts (checked with javap against jadx-core-1.5.1.jar)

- `JadxDecompiler.getRoot()` → `RootNode`; `RootNode.getClasses()` returns **all** classes
  including inner (raw `ClassNode` list, no decompile). `getClassesWithoutInner()` filters.
- `ClassNode`: `getMethods()`, `getFields()`, `getInnerClasses()` are **cheap** (raw dex
  model). `getRawName()`, `getFullName()` (alias/display), `isInner()`, `getSuperClass()`,
  `getInterfaces()`, `getAccessFlags()` are cheap.
- `MethodNode`: `getMethodInfo()` (`.getShortId()`, `.getName()`, `.getAlias()`, `.getReturnType()`,
  `.getArgumentsTypes()`), `getDeclaringClass()`, `getAccessFlags()`, `getAlias()`.
- `FieldNode`: `getFieldInfo()` (`.getShortId()`, `.getName()`, `.getAlias()`, `.getType()`),
  `getDeclaringClass()`, `getAccessFlags()`, `getAlias()`.
- `AccessInfo` (jadx.core.dex.info) has the `isPublic/isProtected/isPrivate/isStatic/isFinal/...`
  methods the current code already uses.
- **Java wrappers are created lazily and cheaply**: `JadxDecompiler.getJavaNodeByRef(ICodeNodeRef)`
  is public; raw nodes implement `ICodeNode` → `ICodeNodeRef`. It converts exactly one
  `ClassNode`/`MethodNode`/`FieldNode` into its `JavaClass`/`JavaMethod`/`JavaField` wrapper
  (cached via `setJavaNode`) **without decompiling** (the `JavaClass` constructor is trivial;
  `getCode()/getMethods()/getFields()/getInnerClasses()` are what trigger `decompile()`).
- `ClassNode.getJavaNode()` is a plain field getter (null until converted) — not a converter;
  use `decompiler.getJavaNodeByRef(...)` instead.

## Locking / thread-safety design

- All index maps stay `ConcurrentHashMap` (already are). They are written only by the single
  load path (background loader thread or the synchronous `loadApk`) and read from MCP handler
  threads; `ConcurrentHashMap` gives safe publish.
- The lazy Java-wrapper resolution goes through `JadxDecompiler.getJavaNodeByRef`, which is
  internally synchronized (convert* are `synchronized`) — safe to call from any thread.
- `getCachedSource` uses `ConcurrentHashMap.getOrPut` (already atomic) for the source cache.
- Phase 4 state machine: `loader` thread + `@Volatile loadState/loadError`; the load thread
  writes state then publishes via `@Volatile`; tool threads read state via `ensureLoaded()`.
  Only one load at a time (`@Synchronized loadApkAsync`).
- Disk cache (Phase 3) is written by the load thread (single writer) and read by tool threads;
  restore happens inside the load thread before `buildIndexes` publishes `decompiler`.

## Phase plan (each committed separately)

### Phase 1a — raw-node index, lazy decompile

- Change index value types to raw nodes:
  `classIndex: ConcurrentHashMap<String, ClassNode>`, `methodIndex: ...<MethodNode>`,
  `fieldIndex: ...<FieldNode>` (same keys: rawName / `rawCls#shortId`).
- `buildIndexes` walks `jadx.root.classes` and reads `methods`/`fields`/`innerClasses` on the
  raw model — no decompile.
- Add lazy resolvers `javaClassOf/javaMethodOf/javaFieldOf` via `decompiler.getJavaNodeByRef`.
- Add `findClassNode` (raw) alongside `findClass` (Java wrapper) with identical messages.
- Cheap tools run on raw nodes: `list_classes`, `search_classes`, `get_class_summary`,
  `list_methods`, `search_method_by_name`, `get_fields_of_class`, `get_main_application_classes_names`,
  `get_callees` (signature only), package guess. Everything needing source/code/use-in
  resolves the Java wrapper per class (decompiles only that class).
- Keep all Java-level helpers (`getMethodSignature`, `methodInfo`, `shortId/mid/fid`) and reuse
  them via wrappers so output stays byte-identical.
- Expose `internal fun decompileCount()` (incremented on `getCachedSource` miss) for a lazy
  test: after `loadApk`, count == 0; after reading one class source, count is small.
- Verify: 5 named tools return identical results; cold load decompiles nothing.

### Phase 1b — remove deobfuscation

- Drop the `deobfuscation` parameter from `loadApk`/`loadApkAsync`, the `currentDeobfuscation`
  field, its part of the in-session cache key, and `JadxArgs.isDeobfuscationOn`.
- Drop the `deobfuscation` property from the `revoid_load_app` schema in ToolRegistry.kt.
- Drop the ArkAudit-side force-false override in `src/registries/mcp.py`
  (`_FORCE_TOOL_ARG_OVERRIDES`) and update docs.

### Phase 2 — lazy resources

- `isSkipResources = true`; `jadx.load()` still runs (dex is parsed).
- Eagerly parse/keep the manifest for the manifest tools; decode all other resources lazily
  on first `get_resource_file` / `get_resource_strings` / `get_all_resource_file_names`.
- Keep `isShowInconsistentCode` unchanged. Manifest test stays green.

### Phase 3 — disk cache

- In-session cache key: APK path + sha256 content hash (replaces path-only comparison).
- Cross-session disk cache under `REVOID_CACHE_DIR` (default temp subdir), TTL default ~24h,
  size/age cleanup. Store a `.jadx` codeData + a marker; after a cold load persist by hash;
  on a same-hash load restore via the existing `loadProject` machinery.
- `close()` / `clearIndexes()` wipe only in-memory state, never the disk cache.
- Warm-cache test asserts `decompileCount()` stays 0 on a cached reload (not timing).

### Phase 4 — background load + soft status

- `revoid_load_app` returns immediately; state machine `idle → loading → ready` (+ `error`).
- `load_status` returns `{"state","progress","elapsed_s"}` while loading.
- Tools before `ready`: either answer (manifest/search/index) or return a soft
  `{"status":"initializing","state":"loading","retry_after_s":N,"hint":...}` — never the old
  hard error. Background order: manifest + index early, per-class decompile on demand.
- `get_server_info` reports load state + new capabilities.
- README updated with the intended agent flow.

## Build / verification note

Local Gradle builds are intentionally skipped (wrapper download is unreliable here). The
changes are pushed to GitHub and built by the repo's CI. Syntactic correctness is ensured by
careful review against the verified jadx API above.
