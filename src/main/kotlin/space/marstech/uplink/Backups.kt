package space.marstech.uplink

import java.io.File

fun RunContext.backupShellConfigs() {
    section("Backing up shell configs (.profile and .zshrc)")
    val dev = Config.cachedDeviceName
    val destDir = File("${Config.repoRoot}/confs/snapshots/${Config.dateStr}-$dev")

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
            appendLine("# Device: $dev")
            appendLine("# Date:   ${Config.dateStr}")
            appendLine("# Repo:   ${Config.repoRoot}")
            appendLine()
            append(src.readText())
        })
        bufPrint("Saved: ${destDir.absolutePath}/$dstName")
    }

    saveWithHeader(File("${Config.HOME}/.profile"), "profile.sh")
    saveWithHeader(File("${Config.HOME}/.zshrc"),   "zshrc.sh")

    // Rotate: keep only the 3 most recent snapshots for this device
    val snapshotsDir = File("${Config.repoRoot}/confs/snapshots")
    if (snapshotsDir.isDirectory) {
        snapshotsDir.listFiles { f -> f.isDirectory && f.name.endsWith("-$dev") }
            ?.sortedDescending()?.drop(3)
            ?.forEach { old -> old.deleteRecursively(); bufPrint("Deleted: ${old.absolutePath}") }
    }
    summaryUpdated += "Shell config backup"
}

fun RunContext.backupKeewebDb() {
    section("Backing up KeePass database (KeeWeb)")
    val src = File("${Config.HOME}/Dropbox/Alkaphreak.kdbx")
    val destDir = File("${Config.HOME}/Sync/Backup/Apps/KeeWeb")
    val dest = File(destDir, "${Config.dateStr}-Alkaphreak.kdbx")

    if (!src.exists()) {
        bufPrint("Warning: ${src.absolutePath} not found, skipping KeeWeb backup")
        summarySkipped += "KeeWeb backup (source not found)"
        return
    }

    if (dryRun) {
        bufPrint("[DRY-RUN] Would copy: ${src.absolutePath} -> ${dest.absolutePath}")
        summaryUpdated += "KeeWeb backup"
        return
    }

    destDir.mkdirs()
    runCatching { src.copyTo(dest, overwrite = true) }
        .onSuccess { bufPrint("Saved: ${dest.absolutePath}") }
        .onFailure {
            bufPrint("Warning: Failed to backup KeePass database")
            summaryFailed += "KeeWeb backup"
            return
        }
    summaryUpdated += "KeeWeb backup"

    // Retain only the 5 most recent backups
    destDir.listFiles { f -> f.isFile && f.name.endsWith("-Alkaphreak.kdbx") }
        ?.sortedDescending()?.drop(5)
        ?.forEach { old -> old.delete(); bufPrint("Deleted old backup: ${old.absolutePath}") }
}
