package space.marstech.uplink

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Scripted [ProcessRunner] for hermetic tests: records every command and answers from [responses]
 * (first matching command prefix wins). `which <cmd>` answers "absent" unless listed in [installed];
 * any other unmatched command succeeds with empty output.
 */
class FakeProcessRunner(
    private val installed: Set<String> = emptySet(),
    private val responses: List<Pair<List<String>, ProcessResult>> = emptyList(),
) : ProcessRunner {
    val calls = CopyOnWriteArrayList<List<String>>()

    override fun run(cmd: List<String>, workDir: File?, timeoutSeconds: Long?): ProcessResult {
        calls += cmd
        if (cmd.size == 2 && cmd[0] == "which") return ProcessResult(if (cmd[1] in installed) 0 else 1, "")
        return responses.firstOrNull { (prefix, _) -> cmd.take(prefix.size) == prefix }?.second
            ?: ProcessResult(0, "")
    }
}
