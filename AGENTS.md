# AGENTS.md

## Project at a glance
- `MarsTech-Uplink` is a macOS-only Kotlin CLI that orchestrates local system/package-manager updates; it is not a service app.
- Runtime starts in `src/main/kotlin/space/marstech/uplink/Main.kt`: `main()` -> `MacUpdateCommand.call()` -> `runUpdate()`.
- `runUpdate()` is organized into fixed phases: pre-flight, backups, updates, final summary. Preserve that phase model when adding features.
- `--backup-only` flag (`RunContext.backupOnly`) skips all phases except backups, then exits 0; pre-flight and updates are never called.
- `--only <tool>` skips pre-flight and backups entirely — only the updater phase runs (see `Main.kt` lines 83–86). Valid tools: every `Tool` with `selectable = true` (`brew`, `codex`, `sdkman`, `npm`, `uv`, `rustup`, `cargo`, `pipx`, `pip`, `gh`, `macos`, `mas`, `ohmyzsh`, `selfupdate`); the backup tasks are config-only.
- `--config <path>` overrides the default config file location (`Config.configFileOverride`).
- Exit code: `runUpdate()` returns `1` if `ctx.summaryFailed` is non-empty, otherwise `0`.

## Core architecture
- Shared mutable run state lives in `src/main/kotlin/space/marstech/uplink/RunContext.kt`.
- Most behavior is implemented as `RunContext` extension functions, not classes: see `Backups.kt`, `PreFlight.kt`, `Summary.kt`, and the `updaters/` package (one `<Tool>Updater.kt` per tool, package `space.marstech.uplink.updaters`).
- Updaters run concurrently through `launchAsync(...)` in `Main.kt`; output is buffered per thread via `RunContext.taskBuffer` so logs do not interleave.
- `brew` and `codex` are intentionally coupled: they run inside one async task because `codex` is upgraded through Homebrew and must come after `brewUpdate()`.
- `selfUpdate()` runs **last** in the pipeline to avoid replacing the binary while other tasks are still in flight. It prefers `gh` CLI for tag lookup and falls back to the GitHub REST API via `curl`.
- `pip` (`pipUpdate()`) upgrades `pip`/`pip3` itself via `pip install --upgrade pip`; it is wired into `buildUpdateFutures`, controlled by `[tools] pip` in `config.toml`, and selectable with `--only pip`.
- `Tool.kt` is the single source of truth for tools: the `Tool` enum drives the `--only` values (help text and validation), the `[tools]` keys (default TOML, missing-key repair, parsing) and the binaries probed by `buildToolsPresent()`. Adding a tool = one enum entry + its updater + a `launchIf(ctx, Tool.X, ...)` line.
- Tool detection is front-loaded in `buildToolsPresent()` (`ProcessUtils.kt`) and then read through `RunContext.toolPresent()`; tests often stub this map instead of invoking real `which` calls.
- `RunContext.shouldRun(tool)` is the single gate controlling whether an updater executes: `--only` overrides config; otherwise the `[tools]` section of `config.toml` decides. Always call `shouldRun()` (via `launchIf`) rather than invoking updaters directly.

## Project-specific patterns to follow
- For a new updater, add a `Tool` entry in `Tool.kt`, a `fun RunContext.<name>Update()` in a new `updaters/<Name>Updater.kt` (imported explicitly in `Main.kt`), and use this pattern:
  - `section("...")` for headings
  - early `dryRun` branch that only logs and updates summary state
  - `toolPresent(...)` / file existence guards
  - record outcomes in `summaryUpdated`, `summarySkipped`, `summaryWarnings`, `summaryFailed`
- Use `bufPrint(...)` instead of `println(...)` inside updater/backups/pre-flight code; direct prints will break the buffered parallel output model. `bufPrint` also writes every line to the log file in real-time via `logImmediate()`.
- Use `runProcess`, `runShell`, `captureOutput`, or `runCaptured` from `ProcessUtils.kt` instead of raw `ProcessBuilder` unless you need custom process handling, and `toolPresent(...)` instead of `commandExists(...)` inside updaters: both go through the injectable `RunContext.processRunner` seam that tests fake.
- Architecture decisions live in `docs/adr/` (MADR; create it from the Marstech-Configs `project-init` template with the first ADR). Read them before changing a design they cover.
- Keep CLI-facing strings explicit and operational; this tool is closer to an automation script than a domain model.

## Filesystem and external integration points
- `Config.repoRoot` resolves through `AppConfig` (loaded from the user config file); shell snapshots are written under `confs/snapshots/...` relative to that root.
- The pom `<version>` is the single source of truth for the version: Maven resource filtering stamps it into `src/main/resources/space/marstech/uplink/version.properties`, read lazily by `Config.UPLINK_VERSION` (also feeds `--version` via `UplinkVersionProvider`). Never hardcode a version string; `release.yml` only runs `mvn versions:set`. The native image embeds that file through `META-INF/native-image/space.marstech/uplink/resource-config.json`. An unstamped build reports `dev` and `selfUpdate()` skips itself.
- Task durations are accumulated in `RunContext.taskDurations` and printed as a `Timings:` table in the final summary (sorted slowest-first).
- KeeWeb source and backup destination are configured via `AppConfig.keewebSource` / `AppConfig.keewebBackupDir`; paths default to `~/KeeWeb/myKeeweb.kdbx` and `~/Backup/Apps/KeeWeb`.
- KeeWeb backups are device-scoped: `backupKeewebDb()` writes files as `YYYY-MM-DD-<device>-<source>.kdbx` and enforces retention per device pattern, not globally per source filename.
- User config file: `~/Library/Application Support/marstech/marstech-uplink/config.toml` — auto-created with placeholder defaults on first run. See `AppConfig.kt` for all keys.
  - The `[tools]` section maps every tool name to a boolean; set `ohmyzsh = false` to permanently skip a tool without touching the CLI. Managed via `ToolsConfig` and read through `RunContext.shouldRun()`. Includes `backup_shells` and `backup_keeweb` flags for the backup phase.
  - The `[brew]` section holds `upgrade_timeout_minutes` (one budget shared by the formula then cask upgrade), `skip_build_from_source` (`--force-bottle`, formulae only) and `exclude_casks` (single-line `["a", "b"]` array, MARSTECH-782): casks left out of the unattended upgrade because their install/uninstall waits on an admin password. The minimal TOML parser supports no other array form.
  - If a key is missing from an existing config file, `AppConfig.repairMissingKeys()` injects it with its default value in-place on every startup — no manual migration needed when adding new config keys.
  - Every entry is validated against `EXPECTED_KEYS` (the default value gives the type; `INT_MIN` gives integer floors): unknown/duplicate/invalid entries fall back to the default and become `Config.configWarnings`, printed in the start banner and added to `summaryWarnings`. A new key only needs its default in `EXPECTED_KEYS` to be validated.
- Logs are always appended to `~/Library/Logs/marstech/marstech-uplink/marstech-uplink-YYYY-MM-DD.log`, one `--- Run started` block per run. Never open that file in a truncating mode: it wipes the day's earlier runs (MARSTECH-765).
- External commands currently orchestrated include: `brew`, `sdk`, `npm`/`node`, `uv`, `rustup`, `cargo`/`cargo-install-update`, `pipx`, `pip`/`pip3`, `gh`, `softwareupdate`, `mas`, `omz`, `zsh`, `osascript`, `scutil`, `hostname`, `curl`, `unzip`.
- ANSI colors are centralized in `Colors.kt` and rely on Jansi setup/teardown in `Main.kt`; do not add manual TTY detection.

## Build, test, and debug workflow
- Verified test command: `mvn test`.
- CI: `.github/workflows/ci.yml` runs `mvn -B verify` on `macos-latest` for every pull request and push to `main` (macOS runner because the tests call real macOS commands). `release.yml` is the separate manual native-release workflow.
- `./build-install.sh` starts with `mvn clean`: never write anything you want to keep (e.g. a build log) under `target/`.
- Common build commands from the repo:
  - `mvn verify`
  - `mvn package` -> produces `target/marstech-uplink.jar`
  - `mvn -Pnative package` -> GraalVM native binary build
  - `./build-install.sh` -> bootstraps SDKMAN from `.sdkmanrc`, installs/activates the required GraalVM JDK if missing, runs `mvn clean install` then `mvn -Pnative package -DskipTests`, copies the native binary to `~/.local/bin/marstech-uplink`, and runs a smoke test.
  - First run after a fresh clone, or after an sdkman update, may stop with `Stop! kotlin X is
    not installed` — run `sdk env install` once (installs the `.sdkmanrc`-pinned Kotlin candidate),
    then re-run `./build-install.sh` (confirmed 2026-09-20, MARSTECH-744).
- Verified smoke test for the packaged CLI: `java -jar target/marstech-uplink.jar --dry-run --only brew`.
- Use `--dry-run` and `--only <tool>` for safe debugging of one updater without touching the full machine.

## Non-obvious caveats
- Surefire runs the tests with `HOME=target/test-home` (MARSTECH-765): the config, log and KeeWeb paths resolved through `Config.HOME` stay under `target/`, never the real ones. Updater tests are hermetic: they inject a `FakeProcessRunner` (`src/test/kotlin/.../FakeProcessRunner.kt`) through `RunContext(processRunner = …)`, so no external command runs and failure/timeout branches are testable; only `ProcessUtilsTest` runs real read-only commands (`which`, `sh`) to cover the real `ProcessBuilder` path.
- Config paths in `AppConfig` default to generic placeholders (`~/KeeWeb/…`, `~/MyWorkspace/…`); the real paths are user-defined in `config.toml`.
- `macosUpdate()` dry-run still calls `softwareupdate --list` for real runs; tests avoid it via the fake runner (it used to emit a `Corrupted channel` warning in `target/surefire-reports/*.dumpstream`).
- Ignore `target/` for source edits; the real implementation lives only under `src/main/kotlin` and `src/test/kotlin`.
