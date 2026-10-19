package space.marstech.uplink.updaters

import space.marstech.uplink.RunContext
import space.marstech.uplink.runProcess
import space.marstech.uplink.section

fun RunContext.pipUpdate() {
    section("pip upgrade")

    val pipCmd = when {
        toolPresent("pip")  -> "pip"
        toolPresent("pip3") -> "pip3"
        else -> {
            bufPrint("pip / pip3 not found, skipping")
            summarySkipped += "pip (not installed)"
            return
        }
    }

    if (dryRun) {
        bufPrint("[DRY-RUN] Would run: $pipCmd install --upgrade pip")
        summaryUpdated += "pip"
        return
    }

    if (runProcess(pipCmd, "install", "--upgrade", "pip") == 0) {
        summaryUpdated += "pip"
        return
    }

    // pipCmd resolved via 'which' but failed to exec — typically a broken/dangling shim
    // (pyenv, pipx-managed venv, ...) or a PATH mismatch between 'which' and the actual
    // process environment. Fall back to invoking pip as a module through python3, which
    // resolves the interpreter directly instead of relying on a separate 'pip' binary on PATH.
    if (toolPresent("python3")) {
        bufPrint("$pipCmd failed to run directly — retrying via 'python3 -m pip'")
        if (runProcess("python3", "-m", "pip", "install", "--upgrade", "pip") != 0) {
            bufPrint("Warning: pip upgrade failed (both '$pipCmd' and 'python3 -m pip' failed)")
            summaryWarnings += "pip upgrade failed (both '$pipCmd' and 'python3 -m pip' failed)"
        } else {
            summaryUpdated += "pip"
        }
    } else {
        bufPrint("Warning: pip upgrade failed")
        summaryWarnings += "pip upgrade failed"
    }
}
