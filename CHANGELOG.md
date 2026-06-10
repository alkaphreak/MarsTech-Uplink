# Changelog

All notable changes to this project will be documented in this file.

Format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).
Versions follow [Semantic Versioning](https://semver.org/).

---

## [Unreleased]

### Added

- `selfUpdate()` updater — checks GitHub Releases for a newer version, downloads the correct
  platform binary (`aarch_64` or `x86_64`), and replaces the installed binary atomically via `mv`
- `selfupdate` config key in `[tools]` section — disable with `selfupdate = false` in `config.toml`
- `compareVersions()` — semver comparison helper (internal, unit-tested)
- Task timing breakdown in SUMMARY terminal (`Timings:` table, sorted slowest-first) and in log
- `Config.UPLINK_VERSION` constant — single source of truth for the current version string
- `parseBrewCannotUpgradeCasks()` — detects `Warning: The cask '...' cannot be upgraded as-is`
  lines in `brew upgrade` output; routes to `summaryWarnings` with a `brew reinstall --cask` hint
- `extractDeprecatedFromDoctorOutput()` — parses `brew doctor` deprecated formula/cask names
- `brew doctor` now always runs (not only on upgrade failure); deprecated packages surface in SUMMARY

### Fixed

- Race condition between `brew outdated` and `brew update`: `CompletableFuture.supplyAsync`
  removed — both commands now run sequentially, eliminating `lockf: already locked` errors
- `codexUpdate()` now uses `--cask` flag (`brew outdated --cask codex` / `brew upgrade --cask codex`),
  fixing `Error: No such keg: /usr/local/Cellar/codex`
- `brewLinkUnlinkedKegs()` now uses `runCaptured` instead of `runProcess` — detects
  `is not writable` errors and surfaces an actionable `sudo chown` fix in `summaryWarnings`

---

## [1.0.0] — 2026-05-14

### Added

- Initial release — replaces the legacy `marstech-uplink.main.kts` Kotlin script
- GraalVM native binary compilation via `native-maven-plugin` (no JVM at runtime)
- `build-install.sh` — automated SDKMAN bootstrap, native build, install, and smoke test
- Phase model: pre-flight, backups, parallel updates via `launchAsync`, summary
- Parallel updaters: `brew`, `codex`, `sdkman`, `npm`, `uv`, `rustup`, `cargo`, `pipx`, `gh`, `macos`, `mas`, `ohmyzsh`
- `cargo` updater via `cargo install-update -a` (requires `cargo-update` crate)
- Backups: shell config snapshots (`.profile` / `.zshrc`) and KeeWeb database
- Retention: per-device rotation for shell snapshots and KeeWeb backups
- Device-scoped backup filenames (`YYYY-MM-DD-<device>-<source>`)
- `~/.local/bin` install target + `~/.local/bin` PATH hint
- TOML config auto-created with defaults on first run; missing keys auto-injected on subsequent runs
- Configurable paths, retention counts, and per-tool enable/disable flags
- Structured real-time logs with daily rotation (`~/Library/Logs/marstech/marstech-uplink/`)
- `--dry-run`, `--only <tool>`, `--backup-only`, `--config <path>` CLI flags
- ANSI color output via Jansi with automatic TTY detection
- JUnit 5 unit tests: retention (KeeWeb + shell snapshots), updater smoke tests, ProcessUtils
- JReleaser 1.15.0 release pipeline — pre-built binaries for both Intel and Apple Silicon

### Fixed

- Double path `repoRoot/confs/snapshots/confs/snapshots` in shell snapshot destination
- GraalVM/Jansi native-access warning (`--enable-native-access=ALL-UNNAMED`)
- `DynamicProxyConfigurationResources` deprecation warning (empty `proxy-config.json` removed)
- Maven kapt `Duplicate source root` warning (redundant `<sourceDirs>` removed from `pom.xml`)
- SDKMAN bootstrap in `build-install.sh` — `auto_env` cd-hook race fixed

[Unreleased]: https://github.com/Alkaphreak/marstech-uplink/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/Alkaphreak/marstech-uplink/releases/tag/v1.0.0
