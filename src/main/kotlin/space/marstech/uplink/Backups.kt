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
    val snapshotsDir = File(Config.repoRoot)
    if (snapshotsDir.isDirectory) {
        snapshotsDir
            .listFiles { f -> f.isDirectory && f.name.endsWith("-$deviceName") }
            ?.sortedDescending()
            ?.drop(Config.appConfig.shellSnapshotRetention)
            ?.forEach { old -> old.deleteRecursively(); bufPrint("Deleted: ${old.absolutePath}") }
    }
    summaryUpdated += "Shell config backup"
}

private const val KEE_WEB_BACKUP = "KeeWeb backup"

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

    if (!src.exists()) {
        bufPrint("Warning: ${src.absolutePath} not found, skipping KeeWeb backup")
        summarySkipped += "KeeWeb backup (source not found)"
        return
    }

    if (dryRun) {
        bufPrint("[DRY-RUN] Would copy: ${src.absolutePath} -> ${dest.absolutePath}")
        summaryUpdated += KEE_WEB_BACKUP
        return
    }

    destDir.mkdirs()
    runCatching { src.copyTo(dest, overwrite = true) }
        .onSuccess { bufPrint("Saved: ${dest.absolutePath}") }
        .onFailure {
            bufPrint("Warning: Failed to backup KeePass database")
            summaryFailed += KEE_WEB_BACKUP
            return
        }
    summaryUpdated += KEE_WEB_BACKUP

    // Retain only the N most recent backups FOR THIS DEVICE
    pruneKeewebBackups(destDir, deviceName, src.name, Config.appConfig.keewebRetention) { old ->
        bufPrint("Deleted old backup: ${old.absolutePath}")
    }
}
