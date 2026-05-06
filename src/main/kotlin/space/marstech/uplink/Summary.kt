package space.marstech.uplink

import space.marstech.uplink.Colors.BOLD
import space.marstech.uplink.Colors.CYAN
import space.marstech.uplink.Colors.GREEN
import space.marstech.uplink.Colors.RED
import space.marstech.uplink.Colors.RESET
import space.marstech.uplink.Colors.YELLOW

/** Formats elapsed milliseconds as a human-readable string (e.g. "2m 34s"). */
fun formatElapsed(ms: Long): String {
    val minutes = ms / 60_000
    val seconds = (ms % 60_000) / 1_000
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

/** Prints a section header — buffered, coloured. */
fun RunContext.section(title: String) {
    bufPrint()
    bufPrint("$CYAN== $title ==$RESET")
}

/** Prints a prominent phase banner on the main thread; also logs it. */
fun phaseHeader(phase: Int, title: String) {
    val bar = "$BOLD$CYAN##############################################$RESET"
    println(); println(bar)
    println("$BOLD$CYAN  Phase $phase — $title$RESET")
    println(bar); println()
    Config.appendLog("\n### Phase $phase — $title ###\n")
}

/** Prints and logs the final update summary, then sends a macOS notification. */
fun RunContext.printSummary() {
    val elapsed = formatElapsed(System.currentTimeMillis() - startTimeMs)
    val hasFailed = summaryFailed.isNotEmpty()

    println()
    println("$BOLD===============================================$RESET")
    println("$BOLD                UPDATE SUMMARY               $RESET")
    println("$BOLD===============================================$RESET")

    if (summaryUpdated.isNotEmpty()) {
        val label = if (dryRun) "Would update:" else "Updated:     "
        println("$GREEN$label $RESET${summaryUpdated.joinToString(", ")}")
    }
    if (summarySkipped.isNotEmpty())
        println("Skipped:      ${summarySkipped.joinToString(", ")}")
    if (summaryFailed.isNotEmpty())
        println("${RED}Failed:       ${summaryFailed.joinToString(", ")}$RESET")
    if (summaryWarnings.isNotEmpty())
        println("${YELLOW}Warnings:     ${summaryWarnings.joinToString("; ")}$RESET")
    println("Duration:     $elapsed")
    println("$BOLD===============================================$RESET")
    println()

    if (dryRun) {
        println("[DRY-RUN] No changes were made.")
    } else {
        println("Please restart your terminal to apply any changes.")
        if (restartRequired.get()) {
            println("${RED}${BOLD}A system restart is required to complete the macOS update.$RESET")
        } else {
            println("Some updates may require a system restart to take full effect.")
        }
    }

    println()
    println("$BOLD===============================================$RESET")
    if (hasFailed) {
        println("${RED}${BOLD}  mac-update COMPLETED WITH FAILURES        $RESET")
    } else {
        println("${GREEN}${BOLD}  mac-update COMPLETED SUCCESSFULLY         $RESET")
    }
    println("$BOLD  Finished in $elapsed$RESET")
    println("$BOLD===============================================$RESET")
    println("${CYAN}  Log: ${Config.logFile.absolutePath}$RESET")
    println()

    if (!dryRun) {
        val notifTitle = if (hasFailed) "mac-update: Failures" else "mac-update: Done"
        val notifMsg = buildString {
            if (summaryUpdated.isNotEmpty()) append("Updated: ${summaryUpdated.size} tool(s). ")
            if (hasFailed) append("Failed: ${summaryFailed.joinToString(", ")}. ")
            if (restartRequired.get()) append("Restart required. ")
            append("Finished in $elapsed.")
        }
        sendNotification(notifTitle, notifMsg)
    }

    Config.appendLog(buildString {
        appendLine(); appendLine("=== SUMMARY ===")
        if (summaryUpdated.isNotEmpty())  appendLine("Updated:  ${summaryUpdated.joinToString(", ")}")
        if (summarySkipped.isNotEmpty())  appendLine("Skipped:  ${summarySkipped.joinToString(", ")}")
        if (summaryFailed.isNotEmpty())   appendLine("Failed:   ${summaryFailed.joinToString(", ")}")
        if (summaryWarnings.isNotEmpty()) appendLine("Warnings: ${summaryWarnings.joinToString("; ")}")
        appendLine("Duration: $elapsed")
        if (restartRequired.get()) appendLine("RESTART REQUIRED")
    })
}

fun sendNotification(title: String, message: String) {
    if (!commandExists("osascript")) return
    runCatching {
        ProcessBuilder(
            "osascript", "-e",
            """display notification "${message.replace("\"", "\\\"")}" with title "${title.replace("\"", "\\\"")}""""
        ).start().waitFor()
    }
}
