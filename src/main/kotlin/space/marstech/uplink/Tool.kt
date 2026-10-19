package space.marstech.uplink

/**
 * Single source of truth for every task marstech-uplink knows about: the `--only` values,
 * the `[tools]` keys of config.toml and the binaries probed by [buildToolsPresent].
 * Declaration order is the order of the generated `[tools]` section.
 *
 * @param key name used both on the CLI (`--only <key>`) and in config.toml (`<key> = true`)
 * @param binaries commands whose presence is checked in PATH before the update phase
 * @param selectable false for backup tasks, which can only be toggled in config.toml
 * @param aliases alternative spellings accepted by [fromName]
 */
enum class Tool(
    val key: String,
    val binaries: List<String> = emptyList(),
    val selectable: Boolean = true,
    val aliases: List<String> = emptyList(),
) {
    BREW("brew", listOf("brew")),
    CODEX("codex", listOf("codex")),
    SDKMAN("sdkman"),
    NPM("npm", listOf("npm", "node")),
    UV("uv", listOf("uv")),
    RUSTUP("rustup", listOf("rustup")),
    CARGO("cargo", listOf("cargo")),
    PIPX("pipx", listOf("pipx")),
    PIP("pip", listOf("pip", "pip3")),
    GH("gh", listOf("gh")),
    MACOS("macos", listOf("softwareupdate")),
    MAS("mas", listOf("mas")),
    OHMYZSH("ohmyzsh", listOf("omz")),
    SELFUPDATE("selfupdate", aliases = listOf("self-update", "self_update")),
    BACKUP_SHELLS("backup_shells", selectable = false, aliases = listOf("backup-shells")),
    BACKUP_KEEWEB("backup_keeweb", selectable = false, aliases = listOf("backup-keeweb"));

    companion object {
        /** Tools accepted by `--only`. */
        val selectable: List<Tool> = entries.filter { it.selectable }

        /** Every binary probed in PATH by [buildToolsPresent]. */
        val allBinaries: Set<String> = entries.flatMapTo(linkedSetOf()) { it.binaries }

        /** Resolves a CLI/config name (case-insensitive, aliases included); null when unknown. */
        fun fromName(name: String): Tool? = name.lowercase().let { n ->
            entries.firstOrNull { it.key == n || n in it.aliases }
        }
    }
}

/** Feeds picocli's `${COMPLETION-CANDIDATES}` for `--only`. */
class SelectableToolNames : Iterable<String> {
    override fun iterator(): Iterator<String> = Tool.selectable.map { it.key }.iterator()
}
