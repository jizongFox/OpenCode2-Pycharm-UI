# OpenCode UI for JetBrains (V2) — 2.2.1

OpenCode UI puts the OpenCode V2 terminal in a right-side editor split and shows completed file changes in the IDE's diff viewer.

## Requirements

- The latest stable PyCharm release with its bundled Terminal plugin. This release targets PyCharm 2026.2.3 (build 262).
- OpenCode V2 CLI. Install it using the [V2 installation guide](https://opencode.ai/v2/docs), then run `opencode --version` to confirm a `2.x` release.

## Use

1. Choose **Tools → Open OpenCode** or press `Ctrl+\` (`Cmd+Esc` on macOS).
2. Enter the local address shown in the dialog. If the port is free, the plugin starts `opencode serve` and opens `opencode --server` in a right-side editor tab. If an OpenCode V2 server is already listening, enter its password to attach to it.
3. Select code in the editor and press `Ctrl+Alt+K` (`Option+Cmd+K` on macOS). The plugin appends a file reference to the TUI composer and focuses it after insertion. Edit your request and press Enter to submit it.

The selection syntax is `@src/example.kt#10-25`. With no selection it uses `@src/example.kt`; Project View selections can include multiple files. Clicking the OpenCode sidebar icon also opens the editor tab. Closing the tab leaves the terminal running; the next launch reopens the same session. The plugin does not open a connection on IDE startup.

References wait in order while you choose a connection or the local server starts. Paste retries begin after the terminal is created. Cancelling the connection discards the queued references; restarting it retains them and starts a fresh retry budget. Focus moves to the terminal only after a successful paste. An already visible detached terminal keeps its position.

On each completed turn, the plugin reads the V2 per-turn diff and opens a native IDE review. **Accept** stages the file with `git add`; **Reject** restores the before content from the V2 turn diff. Accept and Reject refuse to act if the file has changed since the review was shown.

A stale review asks you to refresh it. A staging or restoration failure reports the failed operation separately and keeps the entry pending. Connection resets invalidate old review events, delayed requests, and pending paste callbacks.

## Connection lifecycle

The plugin owns only local servers it started. A new local server gets a random password passed to both the server and attached TUI. Closing the IDE terminates that server. Connecting to an existing server never terminates it. A remote server requires a local V2 CLI for the embedded TUI and the server password if configured.

The V2 server and TUI must be the same version. If you upgrade OpenCode while the editor tab is open, restart the connection. The plugin does not automatically open a window or connection when the IDE starts.

Connection choices, startup workers, and reconnect callbacks share one lifecycle. Resetting the connection or closing the project invalidates old work before it can publish state. A worker whose newly started server cannot be adopted shuts that process down itself. Connection probes distinguish password rejection, unsupported V2 endpoints/data, and server/network failures; both V2 identity and active-session capability are required for readiness.

The Diff service owns the current review tab or window. Accept/Reject callbacks carry that review's identity, so completing an older review cannot navigate or close a newer one, and completing a manually closed review does not reopen it. The IDE's default tab/window preference is preserved.

## Implementation

| Component | Responsibility |
| --- | --- |
| `OpenCodeService` | Connection and owned process lifecycle |
| `ConnectionLifecycle` | Serialize state publication with connection reset and project disposal |
| `OpenCodeCli` | V2 `serve` and `--server` command construction |
| `TerminalHost` | Project-owned TUI, editor tab placement, and bracketed-paste context insertion |
| `PromptDelivery` | Ordered citations, bounded retries after terminal creation, and reset cancellation |
| `OpenCodeApiClient` | `/api/info`, `/api/event`, per-turn `/api/session/{id}/diff` |
| `ReviewCoordinator` | Match busy/idle and user message ID to one turn |
| `SessionManager` | Current turn, pending file reviews, and guarded accept/reject |

The V2 API does not expose a prompt-append route. The plugin writes references to its own terminal TTY after startup, without submitting them. The terminal shell is replaced by the OpenCode TUI on Unix-like systems so exiting the TUI ends that process. A terminal that takes unusually long to initialize may require retrying the shortcut.

## Build

On Linux x86_64 with Python 3.12+, use the standard release entry point:

```bash
python3 scripts/build_release.py
```

It resolves the latest official stable PyCharm release, verifies the SDK download checksum, caches the SDK and bootstrap JDK, configures Java's proxy from `HTTP_PROXY`/`HTTPS_PROXY`, and runs the Gradle wrapper for tests, packaging, structure checks, and then Plugin Verifier. EAP and RC builds are excluded. Later runs reuse `~/.cache/opencode-ui-build`; `--cache-dir PATH` selects another cache. `--skip-verifier` runs tests and packaging; `--verify-only` resumes compatibility verification; `--verbose` includes dependency diagnostics. Java and Kotlin targets follow the selected SDK's required Java version.

For a manually prepared environment, use the Gradle wrapper directly. Supply `-PlocalIdePath=/path/to/pycharm` to reuse an installed SDK and register its bundled JBR with Gradle's `org.gradle.java.installations.paths` property. The exact default SDK version is pinned in `build.gradle.kts` for reproducibility; the release script resolves the stable version anew on each run and passes it explicitly.

```bash
./gradlew test buildPlugin
```

The installable ZIP is generated under `build/distributions/`. Use **Settings → Plugins → Install Plugin from Disk** to install it.

Release 2.2.1 adds 17 regression cases for connection publication, protocol/authentication errors, and review navigation. Against PyCharm 2026.2.3 (262.10968.92), 49 tests passed, three original optional external-process tests were skipped, and plugin structure validation passed. Plugin Verifier reported Compatible, with existing deprecated, scheduled-for-removal, and experimental API usage notices. Real detached-terminal placement and native GUI interactions still require an installation check.

## License

MIT. See [LICENSE](LICENSE).
