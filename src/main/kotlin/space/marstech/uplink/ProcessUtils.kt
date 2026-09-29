package space.marstech.uplink

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Checks whether a command is available in PATH. */
fun commandExists(cmd: String): Boolean =
    runCatching { ProcessBuilder("which", cmd).start().waitFor() == 0 }
        .getOrDefault(false)

/**
 * Runs a command. Inside an async task (ThreadLocal buffer set), captures
 * stdout+stderr into the task buffer. On the main thread, inherits IO directly.
 * Returns the process exit code.
 * @param timeoutSeconds if set, kills the process after the given number of seconds and returns exit code 124.
 */
fun RunContext.runProcess(vararg cmd: String, workDir: File? = null, timeoutSeconds: Long? = null): Int = runCatching {
    val buf = taskBuffer.get()
    if (buf != null) {
        // Streams each line to the log as it arrives; the buffer is flushed at task end anyway.
        val result = runCaptured(*cmd, workDir = workDir, timeoutSeconds = timeoutSeconds, stream = true)
        buf.append(result.output)
        return@runCatching result.exitCode
    }
    val proc = ProcessBuilder(*cmd).apply { workDir?.let { directory(it) } }.inheritIO().start()
    if (timeoutSeconds != null) {
        val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            proc.killTree()
            System.err.println("Warning: '${cmd.first()}' timed out after ${timeoutSeconds}s — process killed")
            return@runCatching 124
        }
        proc.exitValue()
    } else {
        proc.waitFor()
    }
}.getOrElse { e ->
    bufPrint("Warning: Failed to run '${cmd.first()}': ${e.message}")
    1
}

/** Runs a command via a shell interpreter. Returns the process exit code. */
fun RunContext.runShell(command: String, shell: String = "zsh", timeoutSeconds: Long? = null): Int =
    runProcess(shell, "-c", command, timeoutSeconds = timeoutSeconds)

/** Runs a command, captures combined stdout+stderr. Returns null on failure or empty output.
 * @param timeoutSeconds if set, kills the process after the given number of seconds.
 */
fun RunContext.captureOutput(vararg cmd: String, timeoutSeconds: Long? = null): String? = runCatching {
    val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val out = proc.inputStream.bufferedReader().readText().trim()
    if (timeoutSeconds != null) {
        val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly(); return@runCatching null
        }
    } else {
        proc.waitFor()
    }
    out.takeIf { it.isNotEmpty() }
}.getOrNull()

/** Result of a captured process execution. */
data class ProcessResult(val exitCode: Int, val output: String)

/** Runs a command, captures output and returns exit code.
 * Output is read on a separate thread so [timeoutSeconds] is enforced even while the process
 * keeps writing (reading to EOF first would block until the process exits on its own).
 * @param timeoutSeconds if set, kills the process tree after the given number of seconds and returns exit code 124.
 * @param stream if true, writes each output line to the log file as it arrives (see [RunContext.logImmediate]);
 *   the caller must then not log [ProcessResult.output] again.
 */
fun RunContext.runCaptured(
    vararg cmd: String,
    workDir: File? = null,
    timeoutSeconds: Long? = null,
    stream: Boolean = false,
): ProcessResult =
    runCatching {
        val proc = ProcessBuilder(*cmd)
            .redirectErrorStream(true)
            .apply { workDir?.let { directory(it) } }
            .start()
        val label = threadLabel.get() ?: "main"
        val out = StringBuffer()
        val reader = thread(isDaemon = true, name = "uplink-reader-${cmd.first()}") {
            runCatching {
                proc.inputStream.bufferedReader().forEachLine { line ->
                    out.append(line).append('\n')
                    if (stream) Config.logLine(label, line)
                }
            }
        }
        if (timeoutSeconds != null && !proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            proc.killTree()
            reader.join(5_000)
            val msg = "Warning: '${cmd.first()}' timed out after ${timeoutSeconds}s — process killed"
            if (stream) Config.logLine(label, msg)
            return@runCatching ProcessResult(124, "$out\n$msg")
        }
        val exit = proc.waitFor()
        reader.join()
        ProcessResult(exit, out.toString())
    }.getOrElse { e ->
        val msg = "Error running ${cmd.first()}: ${e.message}"
        if (stream) logImmediate(msg)
        ProcessResult(1, msg)
    }

/** Kills the process and its children (brew runs as bash -> ruby -> build tools; killing only the root leaves orphans). */
private fun Process.killTree() {
    descendants().forEach { it.destroyForcibly() }
    destroyForcibly()
}

/**
 * Builds the tool-presence map by running all `which` checks in parallel.
 * Populate RunContext.toolsPresent before Phase 2 starts.
 */
fun buildToolsPresent(): Map<String, Boolean> {
    val tools = setOf(
        "brew", "mas", "npm", "node", "uv", "rustup", "pipx", "pip", "pip3",
        "gh", "omz", "softwareupdate", "codex", "cargo"
    )
    return tools
        .map { tool -> tool to CompletableFuture.supplyAsync { commandExists(tool) } }
        .associate { (tool, f) -> tool to f.get() }
}
