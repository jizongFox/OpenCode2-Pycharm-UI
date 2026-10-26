# OpenCode 2.1.1 detached editor focus fix

- A visible terminal now receives focus through its existing Swing window. The
  plugin no longer asks `FileEditorManager` to reopen that editor file when a
  code reference is sent, which could reposition a detached editor window.
- An editor tab that is hidden or closed still uses the existing open/reopen
  path. The user confirmed the 2.1.0 editor-terminal workflow before reporting
  this detached-window issue; the new placement behavior still needs a GUI
  check in PyCharm.

## Earlier 2.1.0 editor terminal notes

- `TerminalHost` owns the running TUI. The `FileEditor` objects only display its
  component, so closing or moving a tab does not dispose the process.
- The OpenCode sidebar icon remains a launcher and hides after opening the
  editor tab. The first tab opens to the right of the current source editor.
- The code reference shortcut still uses the existing paste queue, then focuses
  the terminal. Terminal hyperlinks open source files outside the terminal split.
- A clean build passed 21 tests (3 optional tests skipped), plugin structure
  verification, and Plugin Verifier against IU 2024.3 and PyCharm 2026.2.3.
- A real PyCharm GUI check is still needed for drag/move, close/reopen, Escape,
  and code-reference focus. The verifier reports existing deprecated Terminal
  and Diff API calls, with no new internal API use.

## Earlier 2.0.2 migration snapshot

This is `2.0.2`, based on the OpenCode V2 sidebar plugin. The plugin ID and
user facing actions remain the same. The integration targets OpenCode CLI
2.x only.

## Changed boundaries

- `OpenCodeCli` starts an owned `opencode serve` process and attaches the TUI
  using `opencode --server`. `OpenCodeService` owns process teardown and
  authenticated connection lifecycle.
- `OpenCodeApiClient` uses `/api/info`, `/api/session/active`, `/api/event`, and
  the per-turn `/api/session/{sessionID}/diff?from=msg_...` route.
- `ReviewCoordinator` pairs the first user inbox item with the busy/idle cycle
  and requests that turn's diff after completion. On reconnection it checks
  the active sessions to finish a tracked turn whose idle event was missed.
- `TerminalHost` inserts `@path#start-end` through a bracketed paste into the
  owned TTY; on POSIX the TUI replaces the shell, so closing the TUI also
  closes the process used for paste readiness. After a successful paste, the
  OpenCode tool window activates and gives keyboard focus to its terminal.
- `SessionManager` stores review entries from V2 per-turn diffs. Accept and
  Reject act on the exact shown entry and check disk and unsaved document
  contents before staging or restoring a file. Reset clears the busy turn.
- Diff fetch and parse failures are reported after one retry instead of being
  treated as an empty diff.

## Verification

- `./gradlew test buildPlugin verifyPluginStructure`: successful. 21 tests
  passed; three optional real-process tests were skipped by default.
- Two IDE fixture tests exercised the real Accept and Reject code paths after
  a user edit; neither staged nor overwrote the later edit.
- PyCharm 2026.2.3 verifier preparation did not finish in this build
  environment. Compatibility with that exact IDE still needs a GUI check.
- `verifyPluginProjectConfiguration` reported the existing Java 17 target as
  lower than the 2024.3 platform's Java 21 target. The plugin compiles against
  2024.3, but that configuration warning remains.

## Areas to review and test in the IDE

1. The initial TUI paste still uses a startup grace period because the terminal
   API does not expose a precise ready event. Check a slow shell and repeated
   `Ctrl+Alt+K` presses during startup.
2. The diff parser reconstructs before/after from the V2 full-file patch. It
   rejects unexpected patch formats instead of presenting a potentially wrong
   review. Check added, deleted, binary, and no-final-newline files.
3. SSE has no replay. Reconnection can finish a previously tracked busy turn,
   but a whole turn that begins and ends during an outage requires manual
   review. Check reconnect and owned-server teardown in the IDE.
4. Test the sidebar, Escape handling, shortcut, and review in PyCharm 2026.2.3.
   Automated IDE tests do not replace this GUI check.
