package space.marstech.uplink

import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Application-wide constants and paths.
 * Does not depend on run context — safe to access from any component.
 */
object Config {

    val HOME: String = System.getenv("HOME") ?: error("HOME environment variable not set")
    val dateStr: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    val repoRoot: String = "$HOME/IdeaProjects/Marstech-Configs"

    // Log file — ~/Library/Logs/marstech/mac-update/mac-update-YYYY-MM-DD.log
    val logFile: File by lazy {
        val dir = File("$HOME/Library/Logs/marstech/mac-update")
        dir.mkdirs()
        File(dir, "mac-update-$dateStr.log").also { f ->
            f.writeText("=== mac-update log — $dateStr ===\n\n")
        }
    }

    fun appendLog(text: String) = runCatching { logFile.appendText(text) }.getOrElse {}

    /**
     * macOS computer name, sanitised for use as a filename segment.
     * Cached to avoid repeated scutil subprocess forks.
     */
    val cachedDeviceName: String by lazy {
        val raw = captureSimple("scutil", "--get", "ComputerName")
            ?: captureSimple("hostname", "-s")
            ?: "unknown"
        raw.replace(' ', '-')
            .replace(Regex("[^a-zA-Z0-9\\-._]"), "")
            .lowercase()
    }

    /** Runs a command and returns trimmed stdout, or null on failure or empty output. */
    private fun captureSimple(vararg cmd: String): String? = runCatching {
        val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        out.takeIf { it.isNotEmpty() }
    }.getOrNull()
}
