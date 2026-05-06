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

        printStartBanner()

        if (ctx.backupOnly) return runBackupOnly(ctx)

        printModeBanners(ctx)

        val executor = Executors.newCachedThreadPool()
        try {
            if (ctx.onlyTool == null) {
                runPreFlight(ctx)
                runBackups(ctx, executor)
            }
            runUpdaters(ctx, executor)
        } finally {
            executor.shutdown()
        }

        ctx.printSummary()
        return if (ctx.summaryFailed.isNotEmpty()) 1 else 0
    }

    private fun printStartBanner() {
        println()
        println("$BOLD$CYAN##############################################$RESET")
        println("$BOLD$CYAN  mac-update — macOS System Update Script   $RESET")
        println("$BOLD$CYAN  Started : ${Config.dateStr}                $RESET")
        println("$BOLD$CYAN  Host    : ${Config.cachedDeviceName}       $RESET")
        println("$BOLD$CYAN##############################################$RESET")
        println()
        Config.appendLog("Started: ${Config.dateStr} | Host: ${Config.cachedDeviceName}\n")
    }

    @Suppress("SameReturnValue")
    private fun runBackupOnly(ctx: RunContext): Int {
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

    private fun printModeBanners(ctx: RunContext) {
        if (ctx.dryRun) {
            println()
            println("$BOLD===============================================$RESET")
            println("${YELLOW}${BOLD}           DRY-RUN MODE ENABLED              $RESET")
            println("$YELLOW  No changes will be made to your system     $RESET")
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
    }

    private fun runPreFlight(ctx: RunContext) {
        phaseHeader(0, "Pre-flight checks")
        ctx.checkTouchIdSudo()
    }

    private fun runBackups(ctx: RunContext, executor: Executor) {
        phaseHeader(1, "Backups")
        CompletableFuture.allOf(
            ctx.launchAsync("backup-shells", executor, ctx::backupShellConfigs),
            ctx.launchAsync("backup-keeweb", executor, ctx::backupKeewebDb)
        ).join()
    }

    private fun runUpdaters(ctx: RunContext, executor: Executor) {
        phaseHeader(2, "Updates")
        ctx.toolsPresent = buildToolsPresent()
        val presentTools = ctx.toolsPresent.filter { it.value }.keys
        println("${CYAN}Tools detected: ${presentTools.sorted().joinToString(", ")}$RESET")
        Config.appendLog("Tools detected: ${presentTools.sorted().joinToString(", ")}\n")

        CompletableFuture.allOf(*buildUpdateFutures(ctx, executor).toTypedArray()).join()
    }

    private fun buildUpdateFutures(ctx: RunContext, executor: Executor): List<CompletableFuture<Void>> =
        buildList {
            addBrewAndCodex(ctx, executor)
            launchIf(ctx, "sdkman",  executor, ctx::sdkmanUpdate)
            launchIf(ctx, "uv",      executor, ctx::uvUpdate)
            launchIf(ctx, "npm",     executor) {
                if (ctx.toolPresent("npm")) ctx.npmUpdate()
                else ctx.summarySkipped += "NPM (not installed)"
            }
            launchIf(ctx, "rustup",  executor, ctx::rustupUpdate)
            launchIf(ctx, "pipx",    executor, ctx::pipxUpdate)
            launchIf(ctx, "gh",      executor, ctx::ghExtUpdate)
            launchIf(ctx, "macos",   executor) {
                if (ctx.toolPresent("softwareupdate")) ctx.macosUpdate()
            }
            launchIf(ctx, "mas",     executor) {
                if (ctx.toolPresent("mas")) ctx.masUpdate()
                else ctx.summarySkipped += "Mac App Store (mas not installed)"
            }
            launchIf(ctx, "ohmyzsh", executor, ctx::ohmyzshUpdate)
        }

    /**
     * Adds the brew+codex future when either tool is requested.
     * Codex is intentionally coupled to brew — it must run after brewUpdate().
     */
    private fun MutableList<CompletableFuture<Void>>.addBrewAndCodex(ctx: RunContext, executor: Executor) {
        if (!ctx.shouldRun("brew") && !ctx.shouldRun("codex")) return
        add(ctx.launchAsync("brew", executor) {
            if (ctx.toolPresent("brew")) {
                if (ctx.shouldRun("brew"))  ctx.brewUpdate()
                if (ctx.shouldRun("codex")) ctx.codexUpdate()
            } else {
                if (ctx.shouldRun("brew"))  ctx.summarySkipped += "Homebrew (not installed)"
                if (ctx.shouldRun("codex")) ctx.summarySkipped += "Codex CLI (brew not installed)"
            }
        })
    }

    /** Conditionally launches [block] as an async task when [tool] should run. */
    private fun MutableList<CompletableFuture<Void>>.launchIf(
        ctx: RunContext,
        tool: String,
        executor: Executor,
        block: () -> Unit,
    ) {
        if (ctx.shouldRun(tool)) add(ctx.launchAsync(tool, executor, block))
    }
}

/**
 * Submits a block to an executor as a CompletableFuture.
 * Initializes a per-task output buffer, flushes it atomically on completion.
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

fun main(args: Array<String>): Unit = CommandLine(MacUpdateCommand()).execute(*args).run {
    exitProcess(this)
}