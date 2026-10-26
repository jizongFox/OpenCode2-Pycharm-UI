# OpenCode IntelliJ Plugin - Developer Guide for Agents

This repository contains the source code for the OpenCode IntelliJ Platform plugin.
This document provides strict guidelines and useful context for AI agents (and human developers) working on this codebase.

## 1. Project Overview

- **Type**: IntelliJ Platform Plugin
- **Language**: Kotlin; bootstrap Gradle with JDK 21 and register the target IDE's bundled JBR toolchain
- **Build System**: Gradle (Kotlin DSL)
- **Target Platform**: Latest official stable PyCharm (currently 2026.2.3, build 262); exclude EAP and RC
- **Protocol**: OpenCode V2 only

### Core Dependencies
- **IntelliJ Platform SDK**: Latest stable PyCharm, pinned per release and verified against that exact build
- **OkHttp**: 4.12.0 (Networking, SSE)
- **Gson**: 2.11.0 (JSON Serialization)

## 2. Build and Verification Commands

**CRITICAL**: Always use the provided Gradle wrapper (`./gradlew`) in the root directory.

The standard Linux release entry point is `python3 scripts/build_release.py`. It calls the wrapper after resolving the current stable PyCharm, checking the SDK archive checksum, preparing cached JDK/SDK toolchains, and propagating environment proxy settings to Java. Reuse this workflow before diagnosing a missing build environment. Record actual compiler, test, structure, verifier, and GUI results separately. A new release must resolve the latest stable PyCharm again; do not silently keep an earlier IDE as the only verification target.

### Build & Run
- **Build Project** (Compiles sources):
  ```bash
  ./gradlew build
  ```
- **Run IDE (Sandbox)** (Launches a separate IntelliJ instance with the plugin installed):
  ```bash
  ./gradlew runIde
  ```
- **Clean Build** (Fixes most weird cache issues):
  ```bash
  ./gradlew clean build
  ```

### Testing
- **Run All Tests**:
  ```bash
  ./gradlew test
  ```
- **Run Single Test Class** (Preferred for targeted validation):
  ```bash
  ./gradlew test --tests "ai.opencode.ide.jetbrains.util.PortFinderTest"
  ```
- **Run Single Test Method** (Precision testing):
  ```bash
  ./gradlew test --tests "ai.opencode.ide.jetbrains.util.PortFinderTest.testPortAvailability"
  ```
- **Run Tests Matching Pattern**:
  ```bash
  ./gradlew test --tests "*PortFinder*"
  ```

### Code Quality
- **Lint & Verify** (Runs detekt/ktlint if configured, plus standard checks):
  ```bash
  ./gradlew check
  ```

## 3. Code Style & Conventions

Strictly adhere to the [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html) and [IntelliJ Platform SDK Guidelines](https://plugins.jetbrains.com/docs/intellij/intro.html).

### Formatting & Naming
- **Indentation**: 4 spaces. **NO TABS**.
- **Line Length**: 120 characters preferred.
- **Classes**: `PascalCase` (e.g., `OpenCodeService`, `DiffViewerService`).
- **Functions/Properties**: `camelCase` (e.g., `findAvailablePort`, `activeSessionId`).
- **Constants**: `UPPER_SNAKE_CASE` inside `companion object`.
- **Packages**: `lowercase`, separated by dots (e.g., `ai.opencode.ide.jetbrains.util`).

### Imports
- **No Wildcards**: Avoid `import com.foo.*` unless importing >5 classes from the same package.
- **Grouping**: Group `com.intellij.*`, `java.*`, and `ai.opencode.*` separately with blank lines between groups.

### Language Policy
- **English Only**: Comments, documentation, commit messages, and user-facing strings **MUST** be in English.
- **Null Safety**: 
  - Prefer `val` over `var`.
  - Use `?.`, `?:`, and `lateinit` responsibly.
  - **AVOID `!!`** (double-bang) operators. They cause runtime NPEs. Use `checkNotNull()` or safe calls instead.

### IntelliJ Specific Patterns
- **Services**: Use `@Service(Service.Level.PROJECT)` for logic. Avoid static singletons.
- **UI Threading**: UI updates **MUST** happen on the Event Dispatch Thread (EDT).
  ```kotlin
  ApplicationManager.getApplication().invokeLater { 
      // UI updates here
  }
  ```
- **Background Tasks**: Network/IO operations **MUST** be on a background thread (e.g., `AppExecutorUtil.getAppExecutorService()`).
- **Disposal**: Services/Components holding resources must implement `Disposable`.
- **Logging**: Use `Logger.getInstance(MyClass::class.java)`. Do not use `System.out.println`.

## 4. Architecture & Patterns

The codebase follows a modular structure within the main package `ai.opencode.ide.jetbrains`:

```
src/main/kotlin/ai/opencode/ide/jetbrains/
├── OpenCodeService.kt          # CORE: Main project service, manages connection & lifecycle
├── OpenCodeToolWindow.kt       # UI: Sidebar launch icon for the editor tab
├── QuickLaunchAction.kt        # Action: Cmd+Esc handler
├── api/                        # NETWORKING: REST & SSE clients
│   ├── OpenCodeApiClient.kt    # HTTP calls (OkHttp)
│   ├── SseEventListener.kt     # Server-Sent Events handler
│   └── models/                 # V2 event and diff models
├── diff/                       # FEATURE: Diff Viewer & Code Review
│   ├── DiffViewerService.kt    # Manages the IDE Diff Window
│   └── OpenCodeDiffEditorActions.kt # Accept/Reject logic
├── session/                    # STATE: Session & File Management
│   └── SessionManager.kt       # Tracks active session, file snapshots, and history
├── terminal/                   # INTEGRATION: Embedded Terminal
│   ├── TerminalHost.kt         # Editor tab TUI and context insertion
│   └── OpenCodeTerminalLinkFilter.kt # Hyperlinks in terminal (@file#lines)
├── review/
│   └── ReviewCoordinator.kt    # V2 turn review
├── runtime/
│   └── OpenCodeCli.kt          # Owns local V2 serve command
├── ui/                         # UI: Dialogs & Popups
│   └── OpenCodeConnectDialog.kt
└── util/                       # UTILS: Helpers
    └── PortFinder.kt           # Localhost port management
```

### Key Architectural Concepts
1.  **OpenCodeService**: The central hub. It owns the `ApiClient` and orchestrates the connection.
2.  **SessionManager**: Tracks the current turn and pending review entries, then checks file content before Git staging or restoration.
3.  **Terminal Integration**: The project-scoped TerminalHost owns one terminal widget. A FileEditor shows it in the editor area; local servers are separate owned processes.
4.  **Diff Strategy**: V2 per-turn patches drive review. `git add` accepts and server before content rejects after validating that the current file matches the reviewed after content.

## 5. Development Workflow for Agents

1.  **Explore**: Use `rg --files`, `rg`, and `cat` to understand the relevant files.
2.  **Plan**: Analyze the task. If it involves UI, check `ui/` or `terminal/`. If it involves logic, check `OpenCodeService` or `SessionManager`.
3.  **Verify Environment**: Check `build.gradle.kts` for dependencies.
4.  **Implement**: Make atomic changes.
5.  **Verify**:
    - **Compilation**: Run `./gradlew classes` to ensure code compiles.
    - **Tests**: Run `./gradlew test` (or specific tests) to verify behavior.
    - **Style**: Ensure no lint errors.

### Common Pitfalls
- **VirtualFiles vs. IoFiles**: IntelliJ uses `VirtualFile`. Use `LocalFileSystem.getInstance().findFileByIoFile()` to convert.
- **Paths (Cross-Platform)**: Always normalize/resolve paths via `ai.opencode.ide.jetbrains.util.PathUtil`. Avoid hardcoded separators or manual `substring` logic. Ensure Windows/macOS/Linux compatibility.
- **Read/Write Actions**: Modifying the PSI or VFS requires a Write Action (`runWriteAction`). Reading requires a Read Action.
- **SDK Compatibility**: Ensure APIs used are available in the target version (see `build.gradle.kts`).

## 6. 语言规则

- **必须使用中文回复**: 所有对用户的回复、解释和沟通**必须**使用中文进行。

---
*End of Developer Guide*
