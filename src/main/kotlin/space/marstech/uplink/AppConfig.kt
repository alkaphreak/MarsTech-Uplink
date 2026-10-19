package space.marstech.uplink

import java.io.File

/**
 * Per-tool enable/disable flags read from the [tools] section of config.toml.
 * All tools are enabled by default; only the disabled ones are stored.
 */
data class ToolsConfig(val disabled: Set<Tool> = emptySet()) {
    fun isEnabled(tool: Tool): Boolean = tool !in disabled

    /** Returns false when the tool has been explicitly disabled in config.toml; unknown names are enabled. */
    fun isEnabled(tool: String): Boolean = Tool.fromName(tool)?.let(::isEnabled) ?: true

    val backupShells: Boolean get() = isEnabled(Tool.BACKUP_SHELLS)
    val backupKeeweb: Boolean get() = isEnabled(Tool.BACKUP_KEEWEB)
}

/**
 * Homebrew-specific tuning, read from the [brew] section of config.toml.
 */
data class BrewConfig(
    /** Hard cap on 'brew upgrade --greedy' — kills the process past this many minutes. */
    val upgradeTimeoutMinutes: Int = 30,
    /**
     * When true, passes --force-bottle to 'brew upgrade' so formulae without a precompiled
     * bottle (common on Tier 3 platforms like Intel macOS) fail fast instead of compiling
     * from source, which can take hours.
     */
    val skipBuildFromSource: Boolean = false,
    /**
     * Casks never upgraded by the unattended run: those whose install/uninstall asks for an
     * admin password (pkg installers, system launchd services, kexts) hang until the timeout.
     */
    val excludeCasks: List<String> = emptyList(),
)

/**
 * Outcome of [AppConfig.loadWithMeta]: the resolved config, whether the file was just created,
 * and human-readable problems found in it (each one fell back to the default value).
 */
data class ConfigLoadResult(
    val config: AppConfig,
    val created: Boolean,
    val warnings: List<String> = emptyList(),
)

/**
 * User-editable application configuration.
 *
 * Loaded once (lazily) from [Config.configFile]:
 *   ~/Library/Application Support/marstech/marstech-uplink/config.toml
 *
 * If the file is absent it is created with sensible defaults on first run.
 * A missing key is injected with its default; an unknown, duplicated or invalid entry falls back to
 * the default and is reported in [ConfigLoadResult.warnings].
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
    // [brew]
    val brew: BrewConfig,
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
                brew                   = BrewConfig(),
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
            ${Tool.entries.joinToString("\n") { "|%-13s = true".format(it.key) }}
            |
            |[brew]
            |# Kill 'brew upgrade --greedy' past this many minutes (Intel Macs can otherwise
            |# spend hours compiling formulae from source when no bottle is available).
            |upgrade_timeout_minutes = 30
            |# When true, fail fast (--force-bottle) on formulae without a precompiled bottle
            |# instead of building from source.
            |skip_build_from_source  = false
            |# Casks to leave out of 'brew upgrade' (e.g. ones that prompt for an admin
            |# password and would hang the unattended run). One line: ["a", "b"]
            |exclude_casks           = []
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
            "tools" to Tool.entries.associateTo(linkedMapOf()) { it.key to "true" },
            "brew" to linkedMapOf(
                "upgrade_timeout_minutes" to "30",
                "skip_build_from_source"  to "false",
                "exclude_casks"           to "[]",
            ),
        )

        /** Minimum accepted value for integer keys (a retention or timeout of 0 would wipe or kill everything). */
        private val INT_MIN = mapOf(
            "backups.shell_snapshot_retention" to 1,
            "backups.keeweb_retention"         to 1,
            "backups.log_retention"            to 1,
            "brew.upgrade_timeout_minutes"     to 1,
        )

        /**
         * Reads the config file. Creates it with defaults when absent ([ConfigLoadResult.created] is true).
         * If the file exists but has missing keys, they are injected in-place with default
         * values so the user can see and edit them.
         * Falls back to [defaults] on any read error — never throws.
         */
        fun loadWithMeta(file: File): ConfigLoadResult {
            if (!file.exists()) {
                runCatching {
                    file.parentFile?.mkdirs()
                    file.writeText(DEFAULT_TOML)
                }
                return ConfigLoadResult(defaults(), created = true)
            }
            val parsed = runCatching { parseWithKeys(file) }.getOrElse { e ->
                return ConfigLoadResult(defaults(), created = false, listOf("cannot read ${file.name}: ${e.message} — using defaults"))
            }
            repairMissingKeys(file, parsed.foundKeys)
            return ConfigLoadResult(parsed.config, created = false, parsed.warnings)
        }

        private class Parsed(val config: AppConfig, val foundKeys: Set<String>, val warnings: List<String>)

        // -----------------------------------------------------------------
        // Minimal TOML parser — supports [sections], key = "string",
        // key = integer, key = boolean, single-line ["string", ...] arrays,
        // and # comments. Kept dependency-free for the native image; every
        // entry is validated against EXPECTED_KEYS (its default gives the type).
        // -----------------------------------------------------------------

        /** Strips an inline comment and the surrounding quotes; a `#` inside a quoted string is kept. */
        internal fun parseValue(rawValue: String): String {
            val v = rawValue.trim()
            if (v.startsWith("\"")) {
                val end = v.indexOf('"', 1)
                if (end > 0) return v.substring(1, end)
            }
            return v.substringBefore(" #").substringBefore("\t#").trim().removeSurrounding("\"")
        }

        /** Returns why [value] is invalid for [key] (typed by its default in [EXPECTED_KEYS]), or null when valid. */
        private fun invalidReason(key: String, value: String, default: String): String? = when {
            default == "true" || default == "false" ->
                "expected true or false".takeIf { value.lowercase() !in setOf("true", "false") }
            default.startsWith("[") ->
                "expected a single-line array like [\"a\", \"b\"]".takeIf { !(value.startsWith("[") && value.endsWith("]")) }
            default.startsWith("\"") ->
                "expected a non-empty string".takeIf { value.isBlank() }
            else -> {
                val min = INT_MIN[key] ?: 0
                "expected an integer >= $min".takeIf { (value.toIntOrNull() ?: Int.MIN_VALUE) < min }
            }
        }

        /** Parses and validates the file: resolved config, keys found (valid or not) and warnings. */
        private fun parseWithKeys(file: File): Parsed {
            val values   = mutableMapOf<String, String>()  // "section.key" -> raw value, valid entries only
            val found    = mutableSetOf<String>()
            val warnings = mutableListOf<String>()
            var section  = ""

            file.readLines().forEachIndexed { idx, raw ->
                val line = raw.trim()
                val at   = "${file.name} line ${idx + 1}"
                when {
                    line.isEmpty() || line.startsWith("#") -> Unit
                    line.startsWith("[") && line.endsWith("]") -> {
                        section = line.removeSurrounding("[", "]").trim()
                        if (section !in EXPECTED_KEYS) warnings += "$at: unknown section [$section] — ignored"
                    }
                    line.contains("=") -> {
                        val eqIdx = line.indexOf('=')
                        val key   = line.substring(0, eqIdx).trim()
                        val value = parseValue(line.substring(eqIdx + 1))
                        val full  = "$section.$key"
                        val default = EXPECTED_KEYS[section]?.get(key)
                        when {
                            section !in EXPECTED_KEYS -> Unit  // already reported with the section
                            default == null -> warnings += "$at: unknown key '$full' — ignored"
                            else -> {
                                if (!found.add(full)) warnings += "$at: duplicate key '$full' — this value wins"
                                val reason = invalidReason(full, value, default)
                                if (reason != null) {
                                    values -= full
                                    warnings += "$at: invalid $full = $value — $reason, using default $default"
                                } else {
                                    values[full] = value
                                }
                            }
                        }
                    }
                    else -> warnings += "$at: cannot parse '$line' — ignored"
                }
            }

            return Parsed(buildConfig(values), found, warnings)
        }

        /** Builds an [AppConfig] from a pre-parsed key→value map. */
        private fun buildConfig(values: Map<String, String>): AppConfig {
            fun str(key: String, default: String) =
                values[key]?.expandHome()?.takeIf { it.isNotBlank() } ?: default

            fun int(key: String, default: Int) =
                values[key]?.toIntOrNull() ?: default

            /** Single-line string array: ["a", "b"]. */
            fun list(key: String, default: List<String>) =
                values[key]?.takeIf { it.startsWith("[") && it.endsWith("]") }
                    ?.removeSurrounding("[", "]")
                    ?.split(",")
                    ?.map { it.trim().removeSurrounding("\"") }
                    ?.filter { it.isNotBlank() }
                    ?: default

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
                    disabled = Tool.entries.filterTo(mutableSetOf()) {
                        !bool("tools.${it.key}", d.tools.isEnabled(it))
                    },
                ),
                brew = BrewConfig(
                    upgradeTimeoutMinutes = int("brew.upgrade_timeout_minutes", d.brew.upgradeTimeoutMinutes),
                    skipBuildFromSource   = bool("brew.skip_build_from_source", d.brew.skipBuildFromSource),
                    excludeCasks          = list("brew.exclude_casks", d.brew.excludeCasks),
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