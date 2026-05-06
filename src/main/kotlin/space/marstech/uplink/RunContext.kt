package space.marstech.uplink

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds configuration and mutable state for a single marstech-uplink run.
 * Passed by reference to all updater components.
 */
class RunContext(
    val dryRun: Boolean = false,
    val backupOnly: Boolean = false,
    val onlyTool: String? = null,
) {
    // Summary state — thread-safe collections for parallel producers
    val summaryUpdated  = CopyOnWriteArrayList<String>()
    val summarySkipped  = CopyOnWriteArrayList<String>()
    val summaryFailed   = CopyOnWriteArrayList<String>()
    val summaryWarnings = CopyOnWriteArrayList<String>()
    val restartRequired = AtomicBoolean(false)
    val startTimeMs: Long = System.currentTimeMillis()

    /**
     * Pre-populated before Phase 2 starts.
     * All updater functions check toolPresent() which reads from this map first,
     * falling back to a live `which` call if the key is absent.
     */
    var toolsPresent: Map<String, Boolean> = emptyMap()

    /**
     * Per-task output buffer — prevents interleaving on the terminal.
     * Set by initTaskBuffer(), flushed by flushTaskBuffer(), removed by clearTaskBuffer().
     */
    internal val taskBuffer = ThreadLocal<StringBuilder>()

    /**
     * Per-thread tool label used to tag log lines (e.g. "brew", "sdkman").
     * Set alongside taskBuffer so every buffered line carries its origin.
     */
    internal val threadLabel = ThreadLocal<String>()

    /** Returns true when no --only filter is active, or when the tool matches it. */
    fun shouldRun(tool: String): Boolean =
        onlyTool == null || onlyTool.equals(tool, ignoreCase = true)

    /** Returns cached tool-presence result; falls back to a live check if the key is unknown. */
    fun toolPresent(cmd: String): Boolean =
        toolsPresent[cmd] ?: commandExists(cmd)

    // -------------------------------------------------------------------------
    // Buffered output + real-time log
    // -------------------------------------------------------------------------

    /**
     * Writes [msg] to the log file immediately with a timestamp and the current
     * thread's tool label. Multi-line strings are split per line.
     */
    fun logImmediate(msg: String) {
        val label = threadLabel.get() ?: "main"
        Config.logLine(label, msg)
    }

    /**
     * Prints a message to the task buffer (async context) or directly to stdout
     * (main thread). In both cases the message is also written to the log file
     * immediately so entries appear in real-time regardless of buffer state.
     */
    fun bufPrint(msg: String = "") {
        logImmediate(msg)
        val buf = taskBuffer.get()
        when {
            buf != null -> buf.appendLine(msg)
            else        -> println(msg)
        }
    }

    /** Initializes the task buffer and label for the current thread. */
    fun initTaskBuffer(label: String) {
        threadLabel.set(label)
        taskBuffer.set(StringBuilder())
    }

    /**
     * Atomically flushes the task buffer to stdout (terminal only).
     * The log was already written line-by-line via [logImmediate]; no double-logging.
     */
    fun flushTaskBuffer() {
        val buf = taskBuffer.get() ?: return
        val text = buf.toString()
        if (text.isNotEmpty()) {
            synchronized(System.out) {
                print(text)
                System.out.flush()
            }
        }
        buf.clear()
    }

    /** Removes the task buffer and label for the current thread. */
    fun clearTaskBuffer() {
        taskBuffer.remove()
        threadLabel.remove()
    }
}