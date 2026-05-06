package space.marstech.uplink

import space.marstech.uplink.Colors.RESET
import space.marstech.uplink.Colors.YELLOW
import java.io.File

fun RunContext.checkTouchIdSudo() {
    section("Checking Touch ID for sudo")
    val pamFile = File("/etc/pam.d/sudo")
    val tidPattern = Regex("""^auth\s+.*sufficient\s+.*pam_tid\.so""")
    val enabled = pamFile.exists() && pamFile.readLines().any { tidPattern.containsMatchIn(it) }

    if (enabled) {
        bufPrint("Touch ID for sudo is already enabled.")
    } else {
        bufPrint("${YELLOW}Touch ID for sudo is NOT enabled.$RESET")
        bufPrint("To enable, run:")
        bufPrint("  echo 'auth       sufficient     pam_tid.so' | sudo tee -a /etc/pam.d/sudo")
        summaryWarnings += "Touch ID for sudo not enabled"
    }
}
