package space.marstech.uplink

import java.io.File

/**
 * Per-tool enable/disable flags read from the [tools] section of config.toml.
 * All tools are enabled by default.
 */
data class ToolsConfig(
    val brew: Boolean = true,
    val codex: Boolean = true,
    val sdkman: Boolean = true,
    val npm: Boolean = true,
    val uv: Boolean = true,
    val rustup: Boolean = true,
    val pipx: Boolean = true,
    val gh: Boolean = true,
    val macos: Boolean = true,
    val mas: Boolean = true,
    val ohmyzsh: Boolean = true,
    val backupShells: Boolean = true,
    val backupKeeweb: Boolean = true,
) {
    /** Returns false when the tool has been explicitly disabled in config.toml. */
    fun isEnabled(tool: String): Boolean = when (tool.lowercase()) {
        "brew"           -> brew
        "codex"          -> codex
        "sdkman"         -> sdkman
        "npm"            -> npm
        "uv"             -> uv
        "rustup"         -> rustup
        "pipx"           -> pipx
        "gh"             -> gh
        "macos"          -> macos
        "mas"            -> mas
        "ohmyzsh"        -> ohmyzsh
        "backup-shells",
        "backup_shells"  -> backupShells
        "backup-keeweb",
        "backup_keeweb"  -> backupKeeweb
        else             -> true   // unknown tools are enabled by default
    }
}

/**
 * User-editable application configuration.
 *
 * Loaded once (lazily) from [Config.configFile]:
 *   ~/Library/Application Support/marstech/marstech-uplink/config.toml
 *
 * If the file is absent it is created with sensible defaults on first run.
 * If a key is missing or the file is malformed the default value is used silently.
 */
data class AppConfig(
    // [paths]
    val shellSnapshotDir: String,
    val keewebSource: String,
    val keewebBackupDir: String,
    val logDir: String,
    // [backups]
    val shellSnapshotRetention: Int,
    val keewebRetention: Int,
    val logRetention: Int,
    // [tools]
    val tools: ToolsConfig,
) {
    companion object {

        /** Fallback values — used when the config file is absent or a key is missing. */
        fun defaults(): AppConfig {
            val home = Config.HOME
            return AppConfig(
                shellSnapshotDir       = "$home/MyWorkspace/My-Configs",
                keewebSource           = "$home/KeeWeb/myKeeweb.kdbx",
                keewebBackupDir        = "$home/Backup/Apps/KeeWeb",
                logDir                 = "$home/Library/Logs/marstech/marstech-uplink",
                shellSnapshotRetention = 3,
                keewebRetention        = 5,
                logRetention           = 5,
                tools                  = ToolsConfig(),
            )
        }

        private val DEFAULT_TOML = """
            |# marstech-uplink configuration
            |# ~/Library/Application Support/marstech/marstech-uplink/config.toml
            |#
            |# Paths support ~ expansion (e.g. ~/MyWorkspace/…).
            |# Changes take effect on the next run — no restart needed.
            |
            |[paths]
            |# Folder where to backup .profile and .zshrc files
            |shell_snapshot_dir = "~/MyWorkspace/My-Configs"
            |keeweb_source      = "~/KeeWeb/myKeeweb.kdbx"
            |keeweb_backup_dir  = "~/Backup/Apps/KeeWeb"
            |# Directory where daily log files are written
            |log_dir            = "~/Library/Logs/marstech/marstech-uplink"
            |
            |[backups]
            |# Number of shell-config snapshots to keep per device
            |shell_snapshot_retention = 3
            |# Number of KeeWeb database backups to keep
            |keeweb_retention         = 5
            |# Number of daily log files to keep
            |log_retention            = 5
            |
            |[tools]
            |# Set to false to permanently skip a tool on every run.
            |# (Use --only <tool> on the CLI to run a single tool regardless of this setting.)
            |brew          = true
            |codex         = true
            |sdkman        = true
            |npm           = true
            |uv            = true
            |rustup        = true
            |pipx          = true
            |gh            = true
            |macos         = true
            |mas           = true
            |ohmyzsh       = true
            |backup_shells = true
            |backup_keeweb = true
        """.trimMargin()

        /**
         * Default TOML value for each expected key, grouped by section.
         * Used by [repairMissingKeys] to inject new keys into existing config files.
         */
        private val EXPECTED_KEYS: LinkedHashMap<String, LinkedHashMap<String, String>> = linkedMapOf(
            "paths" to linkedMapOf(
                "shell_snapshot_dir" to "\"~/MyWorkspace/My-Configs\"",
                "keeweb_source"      to "\"~/KeeWeb/myKeeweb.kdbx\"",
                "keeweb_backup_dir"  to "\"~/Backup/Apps/KeeWeb\"",
                "log_dir"            to "\"~/Library/Logs/marstech/marstech-uplink\"",
            ),
            "backups" to linkedMapOf(
                "shell_snapshot_retention" to "3",
                "keeweb_retention"         to "5",
                "log_retention"            to "5",
            ),
            "tools" to linkedMapOf(
                "brew"          to "true",
                "codex"         to "true",
                "sdkman"        to "true",
                "npm"           to "true",
                "uv"            to "true",
                "rustup"        to "true",
                "pipx"          to "true",
                "gh"            to "true",
                "macos"         to "true",
                "mas"           to "true",
                "ohmyzsh"       to "true",
                "backup_shells" to "true",
                "backup_keeweb" to "true",
            ),
        )

        /**
         * Reads the config file and returns a [Pair] of ([AppConfig], wasJustCreated).
         * Creates the file with defaults when absent — wasJustCreated is true in that case.
         * If the file exists but has missing keys, they are injected in-place with default
         * values so the user can see and edit them.
         * Falls back to [defaults] on any parse error — never throws.
         */
        fun loadWithMeta(file: File): Pair<AppConfig, Boolean> {
            if (!file.exists()) {
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeText(DEFAULT_TOML)
                }
                return Pair(defaults(), true)
            }
            val (config, foundKeys) = runCatching { parseWithKeys(file) }
                .getOrElse { Pair(defaults(), emptySet()) }
            repairMissingKeys(file, foundKeys)
            return Pair(config, false)
        }

        // -----------------------------------------------------------------
        // Minimal TOML parser — supports [sections], key = "string",
        // key = integer, key = boolean, and # comments.
        // No external dependencies required.
        // -----------------------------------------------------------------

        /** Parses the file and returns the resolved config plus the set of keys that were found. */
        private fun parseWithKeys(file: File): Pair<AppConfig, Set<String>> {
            val values = mutableMapOf<String, String>()  // "section.key" -> raw value
            var section = ""

            file.forEachLine { raw ->
                val line = raw.trim()
                when {
                    line.isEmpty() || line.startsWith("#") -> Unit
                    line.startsWith("[") && line.endsWith("]") ->
                        section = line.removeSurrounding("[", "]").trim()
                    line.contains("=") -> {
                        val eqIdx = line.indexOf('=')
                        val key   = line.substring(0, eqIdx).trim()
                        val value = line.substring(eqIdx + 1).trim()
                            .removePrefix("\"").removeSuffix("\"")
                            .substringBefore(" #")   // strip inline comments
                            .trim()
                        values["$section.$key"] = value
                    }
                }
            }

            return Pair(buildConfig(values), values.keys.toSet())
        }

        /** Builds an [AppConfig] from a pre-parsed key→value map. */
        private fun buildConfig(values: Map<String, String>): AppConfig {
            fun str(key: String, default: String) =
                values[key]?.expandHome()?.takeIf { it.isNotBlank() } ?: default

            fun int(key: String, default: Int) =
                values[key]?.toIntOrNull() ?: default

            fun bool(key: String, default: Boolean) =
                when (values[key]?.lowercase()) {
                    "true"  -> true
                    "false" -> false
                    else    -> default
                }

            val d = defaults()
            return AppConfig(
                shellSnapshotDir       = str("paths.shell_snapshot_dir",    d.shellSnapshotDir),
                keewebSource           = str("paths.keeweb_source",         d.keewebSource),
                keewebBackupDir        = str("paths.keeweb_backup_dir",     d.keewebBackupDir),
                logDir                 = str("paths.log_dir",               d.logDir),
                shellSnapshotRetention = int("backups.shell_snapshot_retention", d.shellSnapshotRetention),
                keewebRetention        = int("backups.keeweb_retention",          d.keewebRetention),
                logRetention           = int("backups.log_retention",             d.logRetention),
                tools = ToolsConfig(
                    brew          = bool("tools.brew",          d.tools.brew),
                    codex         = bool("tools.codex",         d.tools.codex),
                    sdkman        = bool("tools.sdkman",        d.tools.sdkman),
                    npm           = bool("tools.npm",           d.tools.npm),
                    uv            = bool("tools.uv",            d.tools.uv),
                    rustup        = bool("tools.rustup",        d.tools.rustup),
                    pipx          = bool("tools.pipx",          d.tools.pipx),
                    gh            = bool("tools.gh",            d.tools.gh),
                    macos         = bool("tools.macos",         d.tools.macos),
                    mas           = bool("tools.mas",           d.tools.mas),
                    ohmyzsh       = bool("tools.ohmyzsh",       d.tools.ohmyzsh),
                    backupShells  = bool("tools.backup_shells", d.tools.backupShells),
                    backupKeeweb  = bool("tools.backup_keeweb", d.tools.backupKeeweb),
                ),
            )
        }

        /**
         * Rewrites [file] in-place, injecting any keys absent from [foundKeys] with their
         * default values — immediately after their section header (or as a new section at
         * the end of the file when the whole section is missing).
         * Does nothing when all expected keys are already present.
         */
        private fun repairMissingKeys(file: File, foundKeys: Set<String>) {
            val anyMissing = EXPECTED_KEYS.any { (section, keys) ->
                keys.any { (key, _) -> "$section.$key" !in foundKeys }
            }
            if (!anyMissing) return

            val lines      = file.readLines()
            val result     = mutableListOf<String>()
            var curSection = ""

            /** Appends missing keys for [section] into [result] with a marker comment. */
            fun injectMissing(section: String) {
                val missing = EXPECTED_KEYS[section]
                    ?.filter { (key, _) -> "$section.$key" !in foundKeys }
                    ?: return
                if (missing.isEmpty()) return
                result += "# ↓ Added automatically — new key(s) missing from this config:"
                missing.forEach { (key, value) -> result += "$key = $value" }
            }

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                    // Inject missing keys for the section we just finished
                    injectMissing(curSection)
                    curSection = trimmed.removeSurrounding("[", "]").trim()
                }
                result += line
            }
            // Inject missing keys for the last section in the file
            injectMissing(curSection)

            // Append sections that were entirely absent from the file
            EXPECTED_KEYS.forEach { (section, keys) ->
                val sectionPresent = foundKeys.any { it.startsWith("$section.") }
                if (!sectionPresent) {
                    result += ""
                    result += "# ↓ Section added automatically — was missing from this config:"
                    result += "[$section]"
                    keys.forEach { (key, value) -> result += "$key = $value" }
                }
            }

            runCatching { file.writeText(result.joinToString("\n") + "\n") }
        }
    }
}

/** Expands a leading `~/` to the user's home directory. */
fun String.expandHome(): String =
    if (startsWith("~/")) "${Config.HOME}/${removePrefix("~/")}" else this