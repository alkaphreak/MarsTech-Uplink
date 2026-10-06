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
    fun `exclude_casks is read as a single-line string array`() {
        val file = java.io.File.createTempFile("uplink-config", ".toml").apply { deleteOnExit() }
        file.writeText("[brew]\nexclude_casks = [\"docker-desktop\", \"protonvpn\"] # sudo prompts\n")
        val (config, _) = AppConfig.loadWithMeta(file)
        assertEquals(listOf("docker-desktop", "protonvpn"), config.brew.excludeCasks)
    }

    @Test
    fun `exclude_casks defaults to empty and is injected into an older config`() {
        val file = java.io.File.createTempFile("uplink-config", ".toml").apply { deleteOnExit() }
        file.writeText("[brew]\nupgrade_timeout_minutes = 240\n")
        val (config, _) = AppConfig.loadWithMeta(file)
        assertEquals(emptyList<String>(), config.brew.excludeCasks)
        assertTrue(file.readText().contains("exclude_casks = []"))
    }

    @Test
    fun `tests log under target, never to the real log`() {
        assertTrue(Config.logFile.path.contains("/target/test-home/"), "surefire must point HOME at target/test-home")
    }
}
