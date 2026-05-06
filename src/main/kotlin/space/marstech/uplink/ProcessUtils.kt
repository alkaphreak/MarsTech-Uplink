package space.marstech.uplink

import java.io.File
import java.util.concurrent.CompletableFuture

/** Checks whether a command is available in PATH. */
fun commandExists(cmd: String): Boolean =
    runCatching { ProcessBuilder("which", cmd).start().waitFor() == 0 }.getOrDefault(false)

/**
 * Runs a command. Inside an async task (ThreadLocal buffer set), captures
 * stdout+stderr into the task buffer. On the main thread, inherits IO directly.
 * Returns the process exit code.
 */
fun RunContext.runProcess(vararg cmd: String, workDir: File? = null): Int = runCatching {
    val buf = taskBuffer.get()
    val pb = ProcessBuilder(*cmd).apply { workDir?.let { directory(it) } }
    if (buf != null) {
        pb.redirectErrorStream(true)
        val proc = pb.start()
        proc.inputStream.bufferedReader().forEachLine { buf.appendLine(it) }
        proc.waitFor()
    } else {
        pb.inheritIO().start().waitFor()
    }
}.getOrElse { e ->
    bufPrint("Warning: Failed to run '${cmd.first()}': ${e.message}")
    1
}

/** Runs a command via a shell interpreter. Returns the process exit code. */
fun RunContext.runShell(command: String, shell: String = "zsh"): Int =
    runProcess(shell, "-c", command)

/** Runs a command, captures combined stdout+stderr. Returns null on failure or empty output. */
fun RunContext.captureOutput(vararg cmd: String): String? = runCatching {
    val proc = ProcessBuilder(*cmd).redirectErrorStream(true).start()
    val out = proc.inputStream.bufferedReader().readText().trim()
    proc.waitFor()
    out.takeIf { it.isNotEmpty() }
}.getOrNull()

/** Result of a captured process execution. */
data class ProcessResult(val exitCode: Int, val output: String)

/** Runs a command, captures output and returns exit code. */
fun RunContext.runCaptured(vararg cmd: String, workDir: File? = null): ProcessResult = runCatching {
    val proc = ProcessBuilder(*cmd)
        .redirectErrorStream(true)
        .apply { workDir?.let { directory(it) } }
        .start()
    val out = proc.inputStream.bufferedReader().readText()
    ProcessResult(proc.waitFor(), out)
}.getOrElse { e -> ProcessResult(1, "Error running ${cmd.first()}: ${e.message}") }

/**
 * Builds the tool-presence map by running all `which` checks in parallel.
 * Populate RunContext.toolsPresent before Phase 2 starts.
 */
fun buildToolsPresent(): Map<String, Boolean> {
    val tools = setOf(
        "brew", "mas", "npm", "node", "uv", "rustup", "pipx",
        "gh", "omz", "softwareupdate", "codex"
    )
    return tools
        .map { tool -> tool to CompletableFuture.supplyAsync { commandExists(tool) } }
        .associate { (tool, f) -> tool to f.get() }
}
