# AGENTS.md

## Project at a glance
- `MarsTech-Uplink` is a macOS-only Kotlin CLI that orchestrates local system/package-manager updates; it is not a service app.
- Runtime starts in `src/main/kotlin/space/marstech/uplink/Main.kt`: `main()` -> `MacUpdateCommand.call()` -> `runUpdate()`.
- `runUpdate()` is organized into fixed phases: pre-flight, backups, updates, final summary. Preserve that phase model when adding features.

## Core architecture
- Shared mutable run state lives in `src/main/kotlin/space/marstech/uplink/RunContext.kt`.
- Most behavior is implemented as `RunContext` extension functions, not classes: see `Backups.kt`, `PreFlight.kt`, `Updaters.kt`, and `Summary.kt`.
- Updaters run concurrently through `launchAsync(...)` in `Main.kt`; output is buffered per thread via `RunContext.taskBuffer` so logs do not interleave.
- `brew` and `codex` are intentionally coupled: they run inside one async task because `codex` is upgraded through Homebrew and must come after `brewUpdate()`.
- Tool detection is front-loaded in `buildToolsPresent()` (`ProcessUtils.kt`) and then read through `RunContext.toolPresent()`; tests often stub this map instead of invoking real `which` calls.

## Project-specific patterns to follow
- For a new updater, add a `fun RunContext.<name>Update()` in `Updaters.kt` and use this pattern:
  - `section("...")` for headings
  - early `dryRun` branch that only logs and updates summary state
  - `toolPresent(...)` / file existence guards
  - record outcomes in `summaryUpdated`, `summarySkipped`, `summaryWarnings`, `summaryFailed`
- Use `bufPrint(...)` instead of `println(...)` inside updater/backups/pre-flight code; direct prints will break the buffered parallel output model.
- Use `runProcess`, `runShell`, `captureOutput`, or `runCaptured` from `ProcessUtils.kt` instead of raw `ProcessBuilder` unless you need custom process handling.
- Keep CLI-facing strings explicit and operational; this tool is closer to an automation script than a domain model.

## Filesystem and external integration points
- `Config.kt` hard-codes `Config.repoRoot` to `~/IdeaProjects/Marstech-Configs`; shell snapshots are written under `confs/snapshots/...` there.
- KeeWeb backups copy `~/Dropbox/Alkaphreak.kdbx` to `~/Sync/Backup/Apps/KeeWeb` (`Backups.kt`). This project has real machine-specific path coupling.
- Logs are always appended to `~/Library/Logs/marstech/mac-update/mac-update-YYYY-MM-DD.log`.
- External commands currently orchestrated include: `brew`, `sdk`, `npm`/`node`, `uv`, `rustup`, `pipx`, `gh`, `softwareupdate`, `mas`, `omz`, `zsh`, `osascript`, `scutil`, and `hostname`.
- ANSI colors are centralized in `Colors.kt` and rely on Jansi setup/teardown in `Main.kt`; do not add manual TTY detection.

## Build, test, and debug workflow
- Verified test command: `mvn test`.
- Common build commands from the repo:
  - `mvn verify`
  - `mvn package` -> produces `target/mac-update.jar`
  - `mvn -Pnative package` -> GraalVM native binary build
- Verified smoke test for the packaged CLI: `java -jar target/mac-update.jar --dry-run --only brew`.
- Use `--dry-run` and `--only <tool>` for safe debugging of one updater without touching the full machine.

## Non-obvious caveats
- Tests are smoke-level, not hermetic. `BackupsTest.backupKeewebDb()` may copy the real KeeWeb database if it exists on the current machine.
- `macosUpdate()` dry-run still calls `softwareupdate --list`; under Surefire this emits a known `Corrupted channel by directly writing to native stream` warning in `target/surefire-reports/*.dumpstream`.
- Running the fat JAR on Java 25 shows Jansi native-access warnings; they are environmental, not Kotlin compile failures.
- Ignore `target/` for source edits; the real implementation lives only under `src/main/kotlin` and `src/test/kotlin`.
