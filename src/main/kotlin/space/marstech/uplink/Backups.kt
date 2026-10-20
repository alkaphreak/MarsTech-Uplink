package space.marstech.uplink

import java.io.File

fun RunContext.backupShellConfigs() {
    section("Backing up shell configs (.profile and .zshrc)")
    val deviceName = Config.cachedDeviceName
    val destDir = File("${Config.repoRoot}/${Config.dateStr}-$deviceName")

    if (dryRun) {
        bufPrint("[DRY-RUN] Would create snapshot directory: ${destDir.absolutePath}")
        bufPrint("[DRY-RUN] Would backup: ~/.profile -> ${destDir.absolutePath}/profile.sh")
        bufPrint("[DRY-RUN] Would backup: ~/.zshrc   -> ${destDir.absolutePath}/zshrc.sh")
        summaryUpdated += "Shell config backup"
        return
    }

    destDir.mkdirs()

    fun saveWithHeader(src: File, dstName: String) {
        if (!src.exists()) {
            bufPrint("Warning: ${src.path} not found, skipping")
            return
        }
        File(destDir, dstName).writeText(buildString {
            appendLine("# Snapshot of ${src.absolutePath}")
            appendLine("# Device: $deviceName")
            appendLine("# Date:   ${Config.dateStr}")
            appendLine("# Repo:   ${Config.repoRoot}")
            appendLine()
            append(src.readText())
        })
        bufPrint("Saved: ${destDir.absolutePath}/$dstName")
    }

    saveWithHeader(File("${Config.HOME}/.profile"), "profile.sh")
    saveWithHeader(File("${Config.HOME}/.zshrc"), "zshrc.sh")

    // Rotate: keep only the N most recent snapshots for this device
    pruneShellSnapshots(File(Config.repoRoot), deviceName, Config.appConfig.shellSnapshotRetention) { old ->
        bufPrint("Deleted: ${old.absolutePath}")
    }
    summaryUpdated += "Shell config backup"
}

/**
 * Deletes all but the [retention] most-recent snapshot directories for [deviceName]
 * inside [snapshotsDir]. A snapshot directory is identified by having a name that ends
 * with `-<deviceName>` (format: `YYYY-MM-DD-<deviceName>`).
 *
 * Directories belonging to other devices are never touched.
 */
internal fun pruneShellSnapshots(
    snapshotsDir: File,
    deviceName: String,
    retention: Int,
    onDelete: (File) -> Unit = {},
) {
    if (!snapshotsDir.isDirectory) return
    snapshotsDir
        .listFiles { f -> f.isDirectory && f.name.endsWith("-$deviceName") }
        ?.sortedDescending()
        ?.drop(retention)
        ?.forEach { old ->
            old.deleteRecursively()
            onDelete(old)
        }
}

private const val KEE_WEB_BACKUP = "KeeWeb backup"

/** The database weighs about 1 MB: a copy still running after this is blocked, not slow. */
internal const val KEE_WEB_COPY_TIMEOUT_S = 60L

internal fun pruneKeewebBackups(
    destDir: File,
    deviceName: String,
    sourceName: String,
    retention: Int,
    onDelete: (File) -> Unit = {},
) {
    val deviceSuffix = "-$deviceName-$sourceName"
    destDir.listFiles { f -> f.isFile && f.name.endsWith(deviceSuffix) }
        ?.sortedDescending()
        ?.drop(retention)
        ?.forEach { old ->
            old.delete()
            onDelete(old)
        }
}

fun RunContext.backupKeewebDb() {
    section("Backing up KeePass database (KeeWeb)")
    val src        = File(Config.appConfig.keewebSource)
    val destDir    = File(Config.appConfig.keewebBackupDir)
    val deviceName = Config.cachedDeviceName
    // Filename includes the device name so backups from different machines coexist
    // in the same directory and rotation is scoped per device.
    val dest       = File(destDir, "${Config.dateStr}-$deviceName-${src.name}")

    if (dryRun) {
        if (!src.exists()) {
            bufPrint("Warning: ${src.absolutePath} not found, skipping KeeWeb backup")
            summarySkipped += "KeeWeb backup (source not found)"
            return
        }
        bufPrint("[DRY-RUN] Would copy: ${src.absolutePath} -> ${dest.absolutePath}")
        summaryUpdated += KEE_WEB_BACKUP
        return
    }

    destDir.mkdirs()
    // The source may sit on a cloud File Provider (Dropbox) whose read can wait forever on a macOS
    // permission prompt, and an in-JVM read cannot be interrupted: copy through a killable `cp` (MARSTECH-811).
    // Even the existence check goes through it, since stat on such a path can block the same way.
    val result = runCaptured("cp", src.absolutePath, dest.absolutePath, timeoutSeconds = KEE_WEB_COPY_TIMEOUT_S)
    when {
        result.exitCode == 0 -> bufPrint("Saved: ${dest.absolutePath}")
        result.exitCode == 124 -> {
            dest.delete()  // a killed cp leaves a truncated file that retention would count as a backup
            bufPrint("Warning: KeeWeb backup timed out after ${KEE_WEB_COPY_TIMEOUT_S}s, skipping")
            summaryWarnings += "KeeWeb backup timed out after ${KEE_WEB_COPY_TIMEOUT_S}s (${src.absolutePath})"
            return
        }
        "${src.absolutePath}: No such file" in result.output -> {
            bufPrint("Warning: ${src.absolutePath} not found, skipping KeeWeb backup")
            summarySkipped += "KeeWeb backup (source not found)"
            return
        }
        else -> {
            dest.delete()
            bufPrint("Warning: Failed to backup KeePass database: ${result.output.trim()}")
            summaryFailed += KEE_WEB_BACKUP
            return
        }
    }
    summaryUpdated += KEE_WEB_BACKUP

    // Retain only the N most recent backups FOR THIS DEVICE
    pruneKeewebBackups(destDir, deviceName, src.name, Config.appConfig.keewebRetention) { old ->
        bufPrint("Deleted old backup: ${old.absolutePath}")
    }
}
