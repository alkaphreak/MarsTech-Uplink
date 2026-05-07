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
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.system.exitProcess

@Command(
    name = "marstech-uplink",
    description = ["Updates all development tools, packages, and system software on macOS."],
    mixinStandardHelpOptions = true,
    version = ["marstech-uplink 1.0.0"],
    footer = [
        "",
        "Tools for --only: brew  sdkman  npm  uv  codex  rustup  pipx  gh  macos  mas  ohmyzsh",
        "",
        "Examples:",
        "  marstech-uplink",
        "  marstech-uplink --dry-run",
        "  marstech-uplink --only brew",
        "  marstech-uplink --backup-only",
        "  marstech-uplink --config ~/myteam/uplink.toml",
        "",
        "Log file: ~/Library/Logs/marstech/marstech-uplink/marstech-uplink-YYYY-MM-DD.log",
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

    @Option(
        names = ["--config"],
        description = ["Path to a custom config file (overrides the default location)"]
    )
    var configPath: String? = null

    private val validTools = setOf(
        "brew", "sdkman", "npm", "uv", "codex", "rustup", "pipx", "gh", "macos", "mas", "ohmyzsh"
    )

    override fun call(): Int {
        // Must be set before any Config lazy val is accessed
        configPath?.let { Config.configFileOverride = File(it.expandHome()) }

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
        println("$BOLD$CYAN  marstech-uplink — macOS System Update Script $RESET")
        println("$BOLD$CYAN  Started : ${Config.dateStr}                $RESET")
        println("$BOLD$CYAN  Host    : ${Config.cachedDeviceName}       $RESET")
        println("$BOLD$CYAN##############################################$RESET")
        println()

        if (Config.configWasCreated) {
            println("$YELLOW${BOLD}  ★  Config file created for the first time:$RESET")
            println("$YELLOW     ${Config.configFile.absolutePath}$RESET")
            println("$YELLOW     Edit it to customise paths and retention settings.$RESET")
        } else {
            println("$CYAN  Config : ${Config.configFile.absolutePath}$RESET")
        }
        println("$CYAN  Log    : ${Config.logFile.absolutePath}$RESET")
        println()

        Config.appendLog("Started: ${Config.dateStr} | Host: ${Config.cachedDeviceName}\n")
        Config.appendLog("Config:  ${Config.configFile.absolutePath} (created=${Config.configWasCreated})\n")
    }

    @Suppress("SameReturnValue")
    private fun runBackupOnly(ctx: RunContext): Int {
        ctx.backupShellConfigs()
        ctx.backupKeewebDb()
        val elapsed = formatElapsed(System.currentTimeMillis() - ctx.startTimeMs)
        println()
        println("$BOLD===============================================$RESET")
        println("${GREEN}${BOLD}  marstech-uplink BACKUP-ONLY COMPLETED       $RESET")
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
        val futures = buildList {
            if (Config.appConfig.tools.backupShells)
                add(ctx.launchAsync("backup-shells", executor, ctx::backupShellConfigs))
            else
                ctx.summarySkipped += "Shell config backup (disabled in config)"

            if (Config.appConfig.tools.backupKeeweb)
                add(ctx.launchAsync("backup-keeweb", executor, ctx::backupKeewebDb))
            else
                ctx.summarySkipped += "KeeWeb backup (disabled in config)"
        }
        CompletableFuture.allOf(*futures.toTypedArray()).join()
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
                else {
                    ctx.bufPrint("npm not found, skipping")
                    ctx.summarySkipped += "NPM (not installed)"
                }
            }
            launchIf(ctx, "rustup",  executor, ctx::rustupUpdate)
            launchIf(ctx, "pipx",    executor, ctx::pipxUpdate)
            launchIf(ctx, "gh",      executor, ctx::ghExtUpdate)
            launchIf(ctx, "macos",   executor) {
                if (ctx.toolPresent("softwareupdate")) ctx.macosUpdate()
                else {
                    ctx.bufPrint("softwareupdate not found, skipping macOS update")
                    ctx.summarySkipped += "macOS update (softwareupdate not available)"
                }
            }
            launchIf(ctx, "mas",     executor) {
                if (ctx.toolPresent("mas")) ctx.masUpdate()
                else {
                    ctx.bufPrint("mas not found, skipping")
                    ctx.summarySkipped += "Mac App Store (mas not installed)"
                }
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
                if (ctx.shouldRun("brew")) {
                    ctx.bufPrint("Homebrew not found, skipping")
                    ctx.summarySkipped += "Homebrew (not installed)"
                }
                if (ctx.shouldRun("codex")) {
                    ctx.bufPrint("Codex CLI skipped (brew not installed)")
                    ctx.summarySkipped += "Codex CLI (brew not installed)"
                }
            }
        })
    }

    /** Conditionally launches [block] as an async task when [tool] should run.
     *  When the tool is disabled in config, records a skip entry with a hint on how to re-enable it. */
    private fun MutableList<CompletableFuture<Void>>.launchIf(
        ctx: RunContext,
        tool: String,
        executor: Executor,
        block: () -> Unit,
    ) {
        when {
            ctx.shouldRun(tool) -> add(ctx.launchAsync(tool, executor, block))
            ctx.onlyTool == null && !Config.appConfig.tools.isEnabled(tool) ->
                ctx.summarySkipped += "$tool (disabled in config — set $tool = true in [tools] to re-enable)"
        }
    }
}

/**
 * Submits a block to an executor as a CompletableFuture.
 * Initializes a per-task output buffer, flushes it atomically on completion.
 * Logs task start and elapsed time directly to the log file (not buffered) so
 * slow or hanging tools are immediately visible in real-time log tailing.
 * Exceptions are caught and recorded instead of crashing the whole run.
 */
fun RunContext.launchAsync(
    label: String,
    executor: Executor,
    block: () -> Unit,
): CompletableFuture<Void> = CompletableFuture.runAsync({
    val taskStart = System.currentTimeMillis()
    Config.logLine(label, "▶ started")
    initTaskBuffer(label)
    try {
        block()
    } catch (e: Exception) {
        bufPrint("Error in $label: ${e.message}")
        summaryFailed += "$label (unexpected error)"
    } finally {
        val elapsed = formatElapsed(System.currentTimeMillis() - taskStart)
        Config.logLine(label, "■ finished in $elapsed")
        flushTaskBuffer()
        clearTaskBuffer()
    }
}, executor)

fun main(args: Array<String>): Unit = CommandLine(MacUpdateCommand()).execute(*args).run {
    exitProcess(this)
}