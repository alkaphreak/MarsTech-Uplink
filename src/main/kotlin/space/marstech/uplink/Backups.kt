package space.marstech.uplink

import java.io.File

fun RunContext.backupShellConfigs() {
    section("Backing up shell configs (.profile and .zshrc)")
    val deviceName = Config.cachedDeviceName
    val destDir = File("${Config.repoRoot}/confs/snapshots/${Config.dateStr}-$deviceName")

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
    val snapshotsDir = File("${Config.repoRoot}/confs/snapshots")
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

fun RunContext.backupKeewebDb() {
    section("Backing up KeePass database (KeeWeb)")
    val src     = File(Config.appConfig.keewebSource)
    val destDir = File(Config.appConfig.keewebBackupDir)
    val dest    = File(destDir, "${Config.dateStr}-${src.name}")

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

    // Retain only the N most recent backups
    destDir.listFiles { f -> f.isFile && f.name.endsWith("-${src.name}") }
        ?.sortedDescending()?.drop(Config.appConfig.keewebRetention)
        ?.forEach { old -> old.delete(); bufPrint("Deleted old backup: ${old.absolutePath}") }
}
