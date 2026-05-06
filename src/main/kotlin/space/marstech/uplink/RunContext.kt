package space.marstech.uplink

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Holds configuration and mutable state for a single mac-update run.
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
     * Per-task output buffer — prevents interleaving from parallel updaters.
     * Set by initTaskBuffer(), flushed by flushTaskBuffer(), removed by clearTaskBuffer().
     */
    internal val taskBuffer = ThreadLocal<StringBuilder>()

    /** Returns true when no --only filter is active, or when the tool matches it. */
    fun shouldRun(tool: String): Boolean =
        onlyTool == null || onlyTool.equals(tool, ignoreCase = true)

    /** Returns cached tool-presence result; falls back to a live check if the key is unknown. */
    fun toolPresent(cmd: String): Boolean =
        toolsPresent[cmd] ?: commandExists(cmd)

    // -------------------------------------------------------------------------
    // Buffered output
    // -------------------------------------------------------------------------

    /**
     * Prints a message to the task buffer (async context) or directly to
     * stdout+log (main thread).
     */
    fun bufPrint(msg: String = "") {
        val buf = taskBuffer.get()
        when {
            buf != null -> buf.appendLine(msg)
            else -> {
                println(msg)
                Config.appendLog("$msg\n")
            }
        }
    }

    /** Initialises the task buffer for the current thread. */
    fun initTaskBuffer() {
        taskBuffer.set(StringBuilder())
    }

    /** Atomically flushes the task buffer to stdout and log. Clears the buffer. */
    fun flushTaskBuffer() {
        val buf = taskBuffer.get() ?: return
        val text = buf.toString()
        if (text.isNotEmpty()) {
            synchronized(System.out) {
                print(text)
                System.out.flush()
                Config.appendLog(text)
            }
        }
        buf.clear()
    }

    /** Removes the task buffer for the current thread. */
    fun clearTaskBuffer() {
        taskBuffer.remove()
    }
}
