package space.marstech.uplink

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConfigTest {

    @Test
    fun `opening the log writer keeps what was already in the log file`() {
        Config.logLine("test", "append-check")
        val log = Config.logFile.readText()
        assertTrue(log.contains("--- Run started:"), "the writer must append, not truncate the day's log")
        assertTrue(log.contains("append-check"))
    }

    @Test
    fun `tests log under target, never to the real log`() {
        assertTrue(Config.logFile.path.contains("/target/test-home/"), "surefire must point HOME at target/test-home")
    }
}
