package space.marstech.uplink

import java.io.File

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
    // [backups]
    val shellSnapshotRetention: Int,
    val keewebRetention: Int,
) {
    companion object {

        /** Fallback values — used when the config file is absent or a key is missing. */
        fun defaults(): AppConfig {
            val home = Config.HOME
            return AppConfig(
                shellSnapshotDir = "$home/MyWorkspace/My-Configs",
                keewebSource = "$home/KeeWeb/myKeeweb.kdbx",
                keewebBackupDir = "$home/Backup/Apps/KeeWeb",
                shellSnapshotRetention = 3,
                keewebRetention = 5,
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
            |# Folder where to backup .profile and .zhsrc files
            |shell_snapshot_dir = "~/MyWorkspace/My-Configs"
            |keeweb_source      = "~/KeeWeb/myKeeweb.kdbx"
            |keeweb_backup_dir  = "~/Backup/Apps/KeeWeb"
            |
            |[backups]
            |# Number of shell-config snapshots to keep per device
            |shell_snapshot_retention = 3
            |# Number of KeeWeb database backups to keep
            |keeweb_retention         = 5
        """.trimMargin()

        /**
         * Reads the config file and returns a [Pair] of ([AppConfig], wasJustCreated).
         * Creates the file with defaults when absent — wasJustCreated is true in that case.
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
            return Pair(runCatching { parse(file) }.getOrElse { defaults() }, false)
        }

        // -----------------------------------------------------------------
        // Minimal TOML parser — supports [sections], key = "string",
        // key = integer, and # comments. No external dependencies required.
        // -----------------------------------------------------------------
        private fun parse(file: File): AppConfig {
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

            fun str(key: String, default: String) =
                values[key]?.expandHome()?.takeIf { it.isNotBlank() } ?: default

            fun int(key: String, default: Int) =
                values[key]?.toIntOrNull() ?: default

            val d = defaults()
            return AppConfig(
                shellSnapshotDir               = str("paths.shell_snapshot_dir",       d.shellSnapshotDir),
                keewebSource             = str("paths.keeweb_source",          d.keewebSource),
                keewebBackupDir          = str("paths.keeweb_backup_dir",      d.keewebBackupDir),
                shellSnapshotRetention   = int("backups.shell_snapshot_retention", d.shellSnapshotRetention),
                keewebRetention          = int("backups.keeweb_retention",     d.keewebRetention),
            )
        }
    }
}

/** Expands a leading `~/` to the user's home directory. */
fun String.expandHome(): String =
    if (startsWith("~/")) "${Config.HOME}/${removePrefix("~/")}" else this
