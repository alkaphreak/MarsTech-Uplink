# MarsTech-Uplink

macOS system updater — Kotlin/Maven CLI tool compiled to a GraalVM native binary. Manages Homebrew, SDKMAN, npm, and more from a single command.

## Download

Pre-built binaries are available on the [GitHub Releases](https://github.com/Alkaphreak/marstech-uplink/releases) page.

```zsh
# Apple Silicon (M-series)
curl -sL https://github.com/Alkaphreak/marstech-uplink/releases/latest/download/marstech-uplink-osx-aarch_64.zip \
  | tar -xz -C ~/.local/bin && chmod +x ~/.local/bin/marstech-uplink

# Intel
curl -sL https://github.com/Alkaphreak/marstech-uplink/releases/latest/download/marstech-uplink-osx-x86_64.zip \
  | tar -xz -C ~/.local/bin && chmod +x ~/.local/bin/marstech-uplink
```

A fat JAR (requires Java 21+) is also attached to each release as `marstech-uplink.jar`.

## Requirements

- macOS (Apple Silicon or Intel — see [Platform compatibility](#platform-compatibility))
- Java 21+ only needed to **build** or to run the fat JAR; the native binary has no runtime dependency
- GraalVM JDK with `native-image` for native compilation (SDKMAN: `sdk install java 25.0.1-graalce`)
- Maven 3.9+ for building

## Usage

```zsh
marstech-uplink                    # Run all updates
marstech-uplink --dry-run          # Preview actions without executing
marstech-uplink --only brew        # Run a single updater
marstech-uplink --only selfupdate  # Check for a newer marstech-uplink and install it
marstech-uplink --backup-only      # Backup shell configs and KeeWeb only
marstech-uplink --help
```

### Tools for `--only`

| Tool         | Description                                          |
|--------------|------------------------------------------------------|
| `brew`       | Homebrew formulae and casks                          |
| `sdkman`     | SDKMAN! candidates                                   |
| `npm`        | npm and global packages                              |
| `uv`         | UV Python package manager                            |
| `codex`      | Codex CLI (Homebrew-managed cask)                    |
| `rustup`     | Rust toolchain                                       |
| `cargo`      | Cargo-installed binaries (`cargo-update`)            |
| `pipx`       | pipx-managed tools                                   |
| `gh`         | GitHub CLI extensions                                |
| `macos`      | macOS software updates                               |
| `mas`        | Mac App Store applications                           |
| `ohmyzsh`    | Oh My Zsh framework                                  |
| `selfupdate` | Self-update — checks GitHub Releases and replaces the binary if a newer version exists |

## Build

```zsh
# Compile and run tests
mvn verify

# Fat JAR
mvn package
java -jar target/marstech-uplink.jar --dry-run

# GraalVM native binary — requires GraalVM JDK with native-image
mvn -Pnative package
```

## Install native binary

```zsh
mvn -Pnative package && cp target/marstech-uplink ~/.local/bin/marstech-uplink && chmod +x ~/.local/bin/marstech-uplink
```

Preferred automated flow (SDKMAN bootstrap + build + install + smoke test):

```zsh
./build-install.sh
```

## Platform compatibility

| Platform          | Binary type       | Status       | Notes                                      |
|-------------------|-------------------|--------------|--------------------------------------------|
| macOS Intel x86_64 | Native (x86_64)  | Supported    | Built on `macos-13` GitHub runner          |
| macOS Apple Silicon (M-series) | Native (aarch64) | Supported | Built on `macos-latest` (ARM64) GitHub runner |
| macOS Apple Silicon (M-series) | Intel binary via Rosetta 2 | Fallback | Runs transparently but slower than native |

- The `build-install.sh` script always compiles a binary native to the machine it runs on.
- GitHub Releases provide both `osx-x86_64` and `osx-aarch_64` pre-built binaries.
- On Apple Silicon, always prefer the `osx-aarch_64` binary for best performance.
- The fat JAR (`marstech-uplink.jar`) runs on any platform with Java 21+, but the tool itself only orchestrates macOS-specific commands.

## Project structure

```
src/main/kotlin/space/marstech/uplink/
├── Main.kt          # picocli @Command entry point, phase orchestration
├── Config.kt        # paths, env var resolution, log management
├── Colors.kt        # ANSI color constants (jansi-managed TTY detection)
├── RunContext.kt    # run state, buffered output, tool cache
├── ProcessUtils.kt  # runProcess, captureOutput, runCaptured, commandExists
├── Backups.kt       # backupShellConfigs, backupKeewebDb, pruneShellSnapshots, pruneKeewebBackups
├── Updaters.kt      # 13 update functions (brew, sdkman, npm, uv, codex, rustup, cargo, pipx, gh, macos, mas, ohmyzsh, selfupdate)
├── PreFlight.kt     # checkTouchIdSudo
└── Summary.kt       # printSummary, sendNotification, phase headers
```

## Tech stack

| Component | Choice                       |
|-----------|------------------------------|
| Language  | Kotlin 2.3+                  |
| Build     | Maven                        |
| CLI       | picocli 4.7.6                |
| ANSI      | jansi 2.4.1                  |
| Native    | GraalVM native-maven-plugin  |
| Release   | JReleaser 1.15.0             |
| Testing   | JUnit 5 + Mockito-Kotlin     |

## Configuration

On the first run, marstech-uplink creates a config file at:

```
~/Library/Application Support/marstech/marstech-uplink/config.toml
```

Edit it to set your actual paths:

```toml
[paths]
# Folder where .profile and .zshrc snapshots are stored
shell_snapshot_dir = "~/MyWorkspace/My-Configs"
# KeePass database to back up
keeweb_source      = "~/KeeWeb/myKeeweb.kdbx"
# Destination folder for KeeWeb backups
keeweb_backup_dir  = "~/Backup/Apps/KeeWeb"

[backups]
# Number of shell-config snapshots to keep per device
shell_snapshot_retention = 3
# Number of KeeWeb database backups to keep per device
keeweb_retention         = 5

[tools]
# Set to false to permanently skip a tool on every run
cargo = true
brew  = true
# ... (all tools listed in the generated config)
```

Changes take effect immediately on the next run — no restart needed.

## Log file

Default location (configurable via `log_dir` in `config.toml`):

`~/Library/Logs/marstech/marstech-uplink/marstech-uplink-YYYY-MM-DD.log`

## Related

- Source `.kts` (being retired): `Marstech-Configs/scripts/Mac/marstech-uplink.main.kts`
- YouTrack: MARSTECH-635
