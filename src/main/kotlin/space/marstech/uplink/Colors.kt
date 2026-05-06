package space.marstech.uplink

/**
 * ANSI color constants.
 * AnsiConsole.systemInstall() (called in Main) strips these automatically
 * when stdout is not a TTY — no manual TTY detection needed.
 */
object Colors {
    const val RESET  = "\u001B[0m"
    const val BOLD   = "\u001B[1m"
    const val GREEN  = "\u001B[32m"
    const val YELLOW = "\u001B[33m"
    const val RED    = "\u001B[31m"
    const val CYAN   = "\u001B[36m"
}
