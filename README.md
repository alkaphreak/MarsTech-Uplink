# MarsTech-Uplink

macOS system updater — Kotlin/Maven CLI tool compiled to a GraalVM native binary. Manages Homebrew, SDKMAN, npm, and more from a single command.

## Requirements

- Java 21+ (Eclipse Temurin recommended)
- Maven 3.9+
- macOS (Apple Silicon or Intel)
- GraalVM JDK with `native-image` for native compilation (SDKMAN: `sdk install java 25.0.1-graalce`)

## Usage

```zsh
marstech-uplink                 # Run all updates
marstech-uplink --dry-run       # Preview actions without executing
marstech-uplink --only brew     # Run a single updater
marstech-uplink --backup-only   # Backup shell configs and KeeWeb only
marstech-uplink --help
```

### Tools for `--only`

| Tool      | Description                      |
|-----------|----------------------------------|
| `brew`    | Homebrew formulae and casks      |
| `sdkman`  | SDKMAN! candidates               |
| `npm`     | npm and global packages          |
| `uv`      | UV Python package manager        |
| `codex`   | Codex CLI (Homebrew-managed)     |
| `rustup`  | Rust toolchain                   |
| `pipx`    | pipx-managed tools               |
| `gh`      | GitHub CLI extensions            |
| `macos`   | macOS software updates           |
| `mas`     | Mac App Store applications       |
| `ohmyzsh` | Oh My Zsh framework              |

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

Preferred automated flow (SDKMAN bootstrap + install + smoke test):

```zsh
./build-install.sh
```

## Project structure

```
src/main/kotlin/space/marstech/uplink/
├── Main.kt          # picocli @Command entry point, phase orchestration
├── Config.kt        # paths, env var resolution, log management
├── Colors.kt        # ANSI color constants (jansi-managed TTY detection)
├── RunContext.kt    # run state, buffered output, tool cache
├── ProcessUtils.kt  # runProcess, captureOutput, runCaptured, commandExists
├── Backups.kt       # backupShellConfigs, backupKeewebDb
├── Updaters.kt      # all 11 update functions
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
```

Changes take effect immediately on the next run — no restart needed.

## Log file

Default location (configurable via `log_dir` in `config.toml`):

`~/Library/Logs/marstech/marstech-uplink/marstech-uplink-YYYY-MM-DD.log`

## Related

- Source `.kts` (being retired): `Marstech-Configs/scripts/Mac/marstech-uplink.main.kts`
- YouTrack: MARSTECH-635
