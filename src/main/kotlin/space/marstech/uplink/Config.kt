package space.marstech.uplink

import java.io.File
import java.io.PrintWriter
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Application-wide constants and paths.
 * Does not depend on run context — safe to access from any component.
 */
object Config {

    const val UPLINK_VERSION = "1.0.0"

    val HOME: String = System.getenv("HOME") ?: error("HOME environment variable not set")
    val dateStr: String = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    /** Optional override set via --config CLI flag; must be assigned before first access of [configFile]. */
    var configFileOverride: File? = null

    /** User config file — created with defaults on first run if absent. */
    val configFile: File by lazy {
        configFileOverride ?: File("$HOME/Library/Application Support/marstech/marstech-uplink/config.toml")
    }

    /**
     * Lazily loaded user configuration. See [AppConfig] for all available keys.
     * The pair carries (config, wasJustCreated) — evaluated once on first access.
     */
    private val configLoadResult: Pair<AppConfig, Boolean> by lazy {
        AppConfig.loadWithMeta(configFile)
    }

    val appConfig: AppConfig get() = configLoadResult.first

    /** True only on the very first run, when the config file did not yet exist. */
    val configWasCreated: Boolean get() = configLoadResult.second

    /** Convenience shorthand — resolved through [appConfig]. */
    val repoRoot: String get() = appConfig.shellSnapshotDir

    private val ANSI_PATTERN = Regex("\u001B\\[[0-9;]*[mKJHFA-Za-z]")
    private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
    private const val LABEL_WIDTH = 12

    /** Strips all ANSI escape sequences from a string. */
    fun stripAnsi(text: String): String = ANSI_PATTERN.replace(text, "")

    // Log file — path resolved from config.toml [paths] log_dir.
    // Appends across runs on the same day; rotates keeping the 5 most recent files.
    val logFile: File by lazy {
        val dir = File(appConfig.logDir)
        dir.mkdirs()
        rotateLogFiles(dir, keep = appConfig.logRetention)
        val file = File(dir, "marstech-uplink-$dateStr.log")
        val runStamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        // Separate runs with a visible banner; create header only on a brand-new file
        if (!file.exists()) {
            file.writeText("=== marstech-uplink log — $dateStr ===\n\n")
        }
        file.appendText("\n--- Run started: $runStamp ---\n\n")
        file
    }

    /** Deletes all but the [keep] most-recently-modified log files in [dir]. */
    private fun rotateLogFiles(dir: File, keep: Int) {
        runCatching {
            dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(keep)
                ?.forEach { it.delete() }
        }
    }

    /**
     * PrintWriter opened in append mode with autoFlush.
     * Used for real-time structured log output.
     * Initialised after [logFile] so the header is written first.
     */
    val logWriter: PrintWriter by lazy {
        PrintWriter(logFile.bufferedWriter().let { java.io.BufferedWriter(java.io.FileWriter(logFile, true)) }, true)
    }

    /**
     * Writes a single log line immediately with timestamp and tool label.
     * Multi-line messages are split and each line is written separately.
     * ANSI codes are stripped.
     */
    fun logLine(label: String, message: String) {
        val time = LocalTime.now().format(TIME_FMT)
        val paddedLabel = label.padEnd(LABEL_WIDTH).take(LABEL_WIDTH)
        val cleaned = stripAnsi(message)
        runCatching {
            synchronized(logWriter) {
                cleaned.lines().forEach { line ->
                    logWriter.println("[$time] [$paddedLabel] $line")
                }
            }
        }
    }

    /** Appends raw unstructured text to the log (used for phase headers and summary). */
    fun appendLog(text: String) = runCatching { logFile.appendText(text) }.getOrElse {}

    /**
     * macOS computer name, sanitized for use as a filename segment.
     * Cached to avoid repeated scutil subprocess forks.
     */
    val cachedDeviceName: String by lazy {
        val raw = captureSimple("scutil", "--get", "ComputerName") ?: captureSimple("hostname", "-s") ?: "unknown"
        raw.replace(' ', '-').replace(Regex("[^a-zA-Z0-9\\-._]"), "").lowercase()
    }

    /** Runs a command and returns trimmed stdout, or null on failure or empty output. */
    private fun captureSimple(vararg cmd: String): String? = runCatching {
        val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        out.takeIf { it.isNotEmpty() }
    }.getOrNull()
}