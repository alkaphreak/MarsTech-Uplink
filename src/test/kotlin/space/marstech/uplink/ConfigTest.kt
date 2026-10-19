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
    fun `a quoted value followed by an inline comment loses both`() {
        val file = java.io.File.createTempFile("uplink-config", ".toml").apply { deleteOnExit() }
        file.writeText("[paths]\nlog_dir = \"/tmp/uplink-logs\" # moved\n")
        val (config, _) = AppConfig.loadWithMeta(file)
        assertEquals("/tmp/uplink-logs", config.logDir)
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

    @Test
    fun `tools section disables only the tools set to false`() {
        val file = java.io.File.createTempFile("uplink-config", ".toml").apply { deleteOnExit() }
        file.writeText("[tools]\nohmyzsh = false\nbackup_keeweb = false\nbrew = true\n")
        val (config, _) = AppConfig.loadWithMeta(file)
        assertEquals(setOf(Tool.OHMYZSH, Tool.BACKUP_KEEWEB), config.tools.disabled)
        assertFalse(config.tools.isEnabled("ohmyzsh"))
        assertFalse(config.tools.backupKeeweb)
        assertTrue(config.tools.backupShells)
        assertTrue(config.tools.isEnabled("unknown-tool"))
    }

    @Test
    fun `a missing tools key is injected for every Tool`() {
        val file = java.io.File.createTempFile("uplink-config", ".toml").apply { deleteOnExit() }
        file.writeText("[tools]\nbrew = true\n")
        AppConfig.loadWithMeta(file)
        val text = file.readText()
        Tool.entries.forEach { assertTrue(text.contains("${it.key} = true"), "missing ${it.key}") }
    }

    @Test
    fun `the default config lists every Tool in the tools section`() {
        val dir = java.nio.file.Files.createTempDirectory("uplink-config").toFile().apply { deleteOnExit() }
        val file = java.io.File(dir, "config.toml")
        val (_, created) = AppConfig.loadWithMeta(file)
        assertTrue(created)
        val toolsSection = file.readText().substringAfter("[tools]").substringBefore("[brew]")
        Tool.entries.forEach {
            assertTrue(Regex("(?m)^${it.key}\\s+= true$").containsMatchIn(toolsSection), "missing ${it.key}")
        }
    }

    @Test
    fun `UPLINK_VERSION is the pom version stamped at build time`() {
        val pomVersion = Regex("<artifactId>uplink</artifactId>\\s*<version>([^<]+)</version>")
            .find(java.io.File("pom.xml").readText())!!.groupValues[1]
        assertEquals(pomVersion, Config.UPLINK_VERSION)
        assertNotEquals(Config.DEV_VERSION, Config.UPLINK_VERSION)
    }

    @Test
    fun `--version prints the stamped version`() {
        val provider = UplinkVersionProvider()
        assertArrayEquals(arrayOf("marstech-uplink ${Config.UPLINK_VERSION}"), provider.version)
    }

    @Test
    fun `fromName resolves keys and aliases case-insensitively`() {
        assertEquals(Tool.PIP, Tool.fromName("PIP"))
        assertEquals(Tool.SELFUPDATE, Tool.fromName("self-update"))
        assertEquals(Tool.BACKUP_SHELLS, Tool.fromName("backup-shells"))
        assertNull(Tool.fromName("nope"))
        assertTrue(Tool.PIP in Tool.selectable)
        assertFalse(Tool.BACKUP_KEEWEB in Tool.selectable)
    }

    @Test
    fun `--only accepts pip and rejects backup tasks`() {
        val cmd = picocli.CommandLine(MacUpdateCommand())
        val usage = cmd.usageMessage
        Tool.selectable.forEach { assertTrue(usage.contains(it.key), "help must list ${it.key}") }
        assertEquals(1, cmd.execute("--only", "backup_shells"))
        assertEquals(1, cmd.execute("--only", "nope"))
    }
}
