# AGENTS.md

## Project at a glance
- `MarsTech-Uplink` is a macOS-only Kotlin CLI that orchestrates local system/package-manager updates; it is not a service app.
- Runtime starts in `src/main/kotlin/space/marstech/uplink/Main.kt`: `main()` -> `MacUpdateCommand.call()` -> `runUpdate()`.
- `runUpdate()` is organized into fixed phases: pre-flight, backups, updates, final summary. Preserve that phase model when adding features.
- `--backup-only` flag (`RunContext.backupOnly`) skips all phases except backups, then exits 0; pre-flight and updates are never called.
- `--only <tool>` skips pre-flight and backups entirely — only the updater phase runs (see `Main.kt` lines 83–86).
- Exit code: `runUpdate()` returns `1` if `ctx.summaryFailed` is non-empty, otherwise `0`.

## Core architecture
- Shared mutable run state lives in `src/main/kotlin/space/marstech/uplink/RunContext.kt`.
- Most behavior is implemented as `RunContext` extension functions, not classes: see `Backups.kt`, `PreFlight.kt`, `Updaters.kt`, and `Summary.kt`.
- Updaters run concurrently through `launchAsync(...)` in `Main.kt`; output is buffered per thread via `RunContext.taskBuffer` so logs do not interleave.
- `brew` and `codex` are intentionally coupled: they run inside one async task because `codex` is upgraded through Homebrew and must come after `brewUpdate()`.
- Tool detection is front-loaded in `buildToolsPresent()` (`ProcessUtils.kt`) and then read through `RunContext.toolPresent()`; tests often stub this map instead of invoking real `which` calls.
- `RunContext.shouldRun(tool)` is the single gate controlling whether an updater executes: `--only` overrides config; otherwise the `[tools]` section of `config.toml` decides. Always call `shouldRun()` (via `launchIf`) rather than invoking updaters directly.

## Project-specific patterns to follow
- For a new updater, add a `fun RunContext.<name>Update()` in `Updaters.kt` and use this pattern:
  - `section("...")` for headings
  - early `dryRun` branch that only logs and updates summary state
  - `toolPresent(...)` / file existence guards
  - record outcomes in `summaryUpdated`, `summarySkipped`, `summaryWarnings`, `summaryFailed`
- Use `bufPrint(...)` instead of `println(...)` inside updater/backups/pre-flight code; direct prints will break the buffered parallel output model. `bufPrint` also writes every line to the log file in real-time via `logImmediate()`.
- Use `runProcess`, `runShell`, `captureOutput`, or `runCaptured` from `ProcessUtils.kt` instead of raw `ProcessBuilder` unless you need custom process handling.
- Keep CLI-facing strings explicit and operational; this tool is closer to an automation script than a domain model.

## Filesystem and external integration points
- `Config.repoRoot` resolves through `AppConfig` (loaded from the user config file); shell snapshots are written under `confs/snapshots/...` relative to that root.
- KeeWeb source and backup destination are configured via `AppConfig.keewebSource` / `AppConfig.keewebBackupDir`; paths default to `~/KeeWeb/myKeeweb.kdbx` and `~/Backup/Apps/KeeWeb`.
- User config file: `~/Library/Application Support/marstech/marstech-uplink/config.toml` — auto-created with placeholder defaults on first run. See `AppConfig.kt` for all keys.
  - The `[tools]` section maps every tool name to a boolean; set `ohmyzsh = false` to permanently skip a tool without touching the CLI. Managed via `ToolsConfig` and read through `RunContext.shouldRun()`.
  - If a key is missing from an existing config file, `AppConfig.repairMissingKeys()` injects it with its default value in-place on every startup — no manual migration needed when adding new config keys.
- Logs are always appended to `~/Library/Logs/marstech/marstech-uplink/marstech-uplink-YYYY-MM-DD.log`.
- External commands currently orchestrated include: `brew`, `sdk`, `npm`/`node`, `uv`, `rustup`, `pipx`, `gh`, `softwareupdate`, `mas`, `omz`, `zsh`, `osascript`, `scutil`, and `hostname`.
- ANSI colors are centralized in `Colors.kt` and rely on Jansi setup/teardown in `Main.kt`; do not add manual TTY detection.

## Build, test, and debug workflow
- Verified test command: `mvn test`.
- Common build commands from the repo:
  - `mvn verify`
  - `mvn package` -> produces `target/marstech-uplink.jar`
  - `mvn -Pnative package` -> GraalVM native binary build
  - `./build-install.sh` -> builds the native binary via `mvn -Pnative package -DskipTests`, copies it to `~/.local/bin/marstech-uplink`, and runs a smoke test. Requires GraalVM JDK declared in `.sdkmanrc`.
- Verified smoke test for the packaged CLI: `java -jar target/marstech-uplink.jar --dry-run --only brew`.
- Use `--dry-run` and `--only <tool>` for safe debugging of one updater without touching the full machine.

## Non-obvious caveats
- Tests are smoke-level, not hermetic. `BackupsTest.backupKeewebDb()` may copy a real KeeWeb database if the configured source path exists on the current machine.
- Config paths in `AppConfig` default to generic placeholders (`~/KeeWeb/…`, `~/MyWorkspace/…`); the real paths are user-defined in `config.toml`.
- `macosUpdate()` dry-run still calls `softwareupdate --list`; under Surefire this emits a known `Corrupted channel by directly writing to native stream` warning in `target/surefire-reports/*.dumpstream`.
- Ignore `target/` for source edits; the real implementation lives only under `src/main/kotlin` and `src/test/kotlin`.
