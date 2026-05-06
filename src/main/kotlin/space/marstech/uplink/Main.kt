package space.marstech.uplink

import org.fusesource.jansi.AnsiConsole
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import space.marstech.uplink.Colors.BOLD
import space.marstech.uplink.Colors.CYAN
import space.marstech.uplink.Colors.GREEN
import space.marstech.uplink.Colors.RESET
import space.marstech.uplink.Colors.YELLOW
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.system.exitProcess

@Command(
    name = "mac-update",
    description = ["Updates all development tools, packages, and system software on macOS."],
    mixinStandardHelpOptions = true,
    version = ["mac-update 1.0.0"],
    footer = [
        "",
        "Tools for --only: brew  sdkman  npm  uv  codex  rustup  pipx  gh  macos  mas  ohmyzsh",
        "",
        "Examples:",
        "  mac-update",
        "  mac-update --dry-run",
        "  mac-update --only brew",
        "  mac-update --backup-only",
        "",
        "Log file: ~/Library/Logs/marstech/mac-update/mac-update-YYYY-MM-DD.log",
    ]
)
class MacUpdateCommand : Callable<Int> {

    @Option(names = ["-n", "--dry-run"], description = ["Preview actions without executing them"])
    var dryRun = false

    @Option(
        names = ["--backup-only"],
        description = ["Only backup shell configs and KeeWeb database, skip all updates"]
    )
    var backupOnly = false

    @Option(
        names = ["--only"],
        description = ["Run a single updater. Valid: brew, sdkman, npm, uv, codex, rustup, pipx, gh, macos, mas, ohmyzsh"]
    )
    var onlyTool: String? = null

    private val validTools = setOf(
        "brew", "sdkman", "npm", "uv", "codex", "rustup", "pipx", "gh", "macos", "mas", "ohmyzsh"
    )

    override fun call(): Int {
        val tool = onlyTool?.lowercase()
        if (tool != null && tool !in validTools) {
            System.err.println("Unknown tool for --only: $onlyTool. Valid: ${validTools.sorted().joinToString(", ")}")
            return 1
        }

        AnsiConsole.systemInstall()
        return try {
            runUpdate(tool)
        } finally {
            AnsiConsole.systemUninstall()
        }
    }

    private fun runUpdate(normalizedTool: String?): Int {
        val ctx = RunContext(dryRun = dryRun, backupOnly = backupOnly, onlyTool = normalizedTool)

        // Startup banner
        println()
        println("$BOLD$CYAN##############################################$RESET")
        println("$BOLD$CYAN  mac-update — macOS System Update Script   $RESET")
        println("$BOLD$CYAN  Started : ${Config.dateStr}                $RESET")
        println("$BOLD$CYAN  Host    : ${Config.cachedDeviceName}       $RESET")
        println("$BOLD$CYAN##############################################$RESET")
        println()
        Config.appendLog("Started: ${Config.dateStr} | Host: ${Config.cachedDeviceName}\n")

        if (ctx.backupOnly) {
            ctx.backupShellConfigs()
            ctx.backupKeewebDb()
            val elapsed = formatElapsed(System.currentTimeMillis() - ctx.startTimeMs)
            println()
            println("$BOLD===============================================$RESET")
            println("${GREEN}${BOLD}  mac-update BACKUP-ONLY COMPLETED           $RESET")
            println("$BOLD  Finished in $elapsed$RESET")
            println("$BOLD===============================================$RESET")
            println()
            return 0
        }

        if (ctx.dryRun) {
            println()
            println("$BOLD===============================================$RESET")
            println("${YELLOW}${BOLD}           DRY-RUN MODE ENABLED              $RESET")
            println("${YELLOW}  No changes will be made to your system     $RESET")
            println("$BOLD===============================================$RESET")
            println()
        }

        if (ctx.onlyTool != null) {
            println()
            println("$BOLD===============================================$RESET")
            println("${CYAN}${BOLD}  Running only: ${ctx.onlyTool}$RESET")
            println("$BOLD===============================================$RESET")
            println()
        }

        val executor = Executors.newCachedThreadPool()

        // Phase 0 — Pre-flight
        if (ctx.onlyTool == null) {
            phaseHeader(0, "Pre-flight checks")
            ctx.checkTouchIdSudo()
        }

        // Phase 1 — Backups
        if (ctx.onlyTool == null) {
            phaseHeader(1, "Backups")
            CompletableFuture.allOf(
                ctx.launchAsync("backup-shells", executor, ctx::backupShellConfigs),
                ctx.launchAsync("backup-keeweb", executor, ctx::backupKeewebDb)
            ).join()
        }

        // Phase 2 — Updaters (parallel; codex must run after brew)
        phaseHeader(2, "Updates")
        ctx.toolsPresent = buildToolsPresent()
        val presentTools = ctx.toolsPresent.filter { it.value }.keys
        println("${CYAN}Tools detected: ${presentTools.sorted().joinToString(", ")}$RESET")
        Config.appendLog("Tools detected: ${presentTools.sorted().joinToString(", ")}\n")

        val updateFutures = mutableListOf<CompletableFuture<Void>>()

        if (ctx.shouldRun("brew") || ctx.shouldRun("codex")) {
            updateFutures += ctx.launchAsync("brew", executor) {
                if (ctx.toolPresent("brew")) {
                    if (ctx.shouldRun("brew"))  ctx.brewUpdate()
                    if (ctx.shouldRun("codex")) ctx.codexUpdate()
                } else {
                    if (ctx.shouldRun("brew"))  ctx.summarySkipped += "Homebrew (not installed)"
                    if (ctx.shouldRun("codex")) ctx.summarySkipped += "Codex CLI (brew not installed)"
                }
            }
        }
        if (ctx.shouldRun("sdkman")) updateFutures += ctx.launchAsync("sdkman",  executor, ctx::sdkmanUpdate)
        if (ctx.shouldRun("uv"))     updateFutures += ctx.launchAsync("uv",      executor, ctx::uvUpdate)
        if (ctx.shouldRun("npm")) updateFutures += ctx.launchAsync("npm", executor) {
            if (ctx.toolPresent("npm")) ctx.npmUpdate() else ctx.summarySkipped += "NPM (not installed)"
        }
        if (ctx.shouldRun("rustup")) updateFutures += ctx.launchAsync("rustup",  executor, ctx::rustupUpdate)
        if (ctx.shouldRun("pipx"))   updateFutures += ctx.launchAsync("pipx",    executor, ctx::pipxUpdate)
        if (ctx.shouldRun("gh"))     updateFutures += ctx.launchAsync("gh",      executor, ctx::ghExtUpdate)
        if (ctx.shouldRun("macos")) updateFutures += ctx.launchAsync("macos", executor) {
            if (ctx.toolPresent("softwareupdate")) ctx.macosUpdate()
        }
        if (ctx.shouldRun("mas")) updateFutures += ctx.launchAsync("mas", executor) {
            if (ctx.toolPresent("mas")) ctx.masUpdate() else ctx.summarySkipped += "Mac App Store (mas not installed)"
        }
        if (ctx.shouldRun("ohmyzsh")) updateFutures += ctx.launchAsync("ohmyzsh", executor, ctx::ohmyzshUpdate)

        CompletableFuture.allOf(*updateFutures.toTypedArray()).join()
        executor.shutdown()

        ctx.printSummary()
        return if (ctx.summaryFailed.isNotEmpty()) 1 else 0
    }
}

/**
 * Submits a block to an executor as a CompletableFuture.
 * Initialises a per-task output buffer, flushes it atomically on completion.
 * Exceptions are caught and recorded instead of crashing the whole run.
 */
fun RunContext.launchAsync(
    label: String,
    executor: Executor,
    block: () -> Unit,
): CompletableFuture<Void> = CompletableFuture.runAsync({
    initTaskBuffer(label)
    try {
        block()
    } catch (e: Exception) {
        bufPrint("Error in $label: ${e.message}")
        summaryFailed += "$label (unexpected error)"
    } finally {
        flushTaskBuffer()
        clearTaskBuffer()
    }
}, executor)

fun main(args: Array<String>) {
    val exitCode = CommandLine(MacUpdateCommand()).execute(*args)
    exitProcess(exitCode)
}
