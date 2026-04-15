package ch.obermuhlner.pdfmarkdown

import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loads configuration from YAML files and provides merged options.
 *
 * Configuration is loaded from the following sources (in order of precedence, highest first):
 * 1. CLI arguments / programmatic overrides
 * 2. Project-level config: `[current directory]/.pdf-markdown.yaml`
 * 3. User-level config: `[home directory]/.pdf-markdown.yaml`
 * 4. Default values
 *
 * Example config file:
 * ```yaml
 * # pdf-markdown configuration
 *
 * # Conversion mode: readable (default) or rag
 * mode: readable
 *
 * # Rule tuning parameters
 * ruleTuning:
 *   titleMinRatio: 1.15          # Require 15% larger for title
 *   listMinIndent: 25            # Increase list indent threshold
 *   tableMinRows: 4              # Require at least 4 rows for table
 * ```
 */
object ConfigLoader {

    private const val CONFIG_FILENAME = ".pdf-markdown.yaml"
    private val yaml = Yaml()

    /**
     * Loads configuration from all available sources and returns merged [ConversionOptions].
     *
     * @param overrides Map of direct overrides (CLI args, programmatic)
     * @param searchStartDir Starting directory for project-level config search
     * @return Merged [ConversionOptions] with values from all sources
     */
    fun loadConfig(
        overrides: Map<String, Any?> = emptyMap(),
        searchStartDir: Path = Path.of("").toAbsolutePath(),
    ): ConversionOptions {
        val userConfig = loadUserConfig()
        val projectConfig = loadProjectConfig(searchStartDir)

        return mergeConfigs(userConfig, projectConfig, overrides)
    }

    /**
     * Loads the user-level config from `~/.pdf-markdown.yaml`.
     */
    private fun loadUserConfig(): Map<String, Any>? {
        val homeDir = System.getProperty("user.home")
        if (homeDir.isNullOrBlank()) return emptyMap()

        val configFile = File(homeDir, CONFIG_FILENAME)
        return loadConfigFile(configFile)
    }

    /**
     * Loads the project-level config by searching upward from [startDir]
     * for `.pdf-markdown.yaml`.
     */
    private fun loadProjectConfig(startDir: Path): Map<String, Any>? {
        var currentDir: Path? = startDir.normalize().toAbsolutePath()

        while (currentDir != null && currentDir != currentDir.root) {
            val configFile = currentDir.resolve(CONFIG_FILENAME).toFile()
            if (configFile.exists() && configFile.isFile) {
                return loadConfigFile(configFile)
            }
            currentDir = currentDir.parent
        }

        return emptyMap()
    }

    private fun loadConfigFile(file: File): Map<String, Any>? {
        return try {
            FileInputStream(file).use { stream ->
                yaml.load<Map<String, Any>>(stream)
            }
        } catch (e: Exception) {
            System.err.println("Warning: Failed to load config from ${file.absolutePath}: ${e.message}")
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun mergeConfigs(
        userConfig: Map<String, Any>?,
        projectConfig: Map<String, Any>?,
        overrides: Map<String, Any?>,
    ): ConversionOptions {
        val ruleTuningOverrides = mutableMapOf<String, Any?>()

        overrides.forEach { (key, value) ->
            ruleTuningOverrides[key] = value
        }

        if (projectConfig != null) {
            extractRuleTuning(projectConfig, ruleTuningOverrides)
        }

        if (userConfig != null) {
            extractRuleTuning(userConfig, ruleTuningOverrides)
        }

        val ruleTuning = RuleTuning().withOverrides(ruleTuningOverrides)

        val mode = overrides["mode"] as? String
            ?: projectConfig?.get("mode") as? String
            ?: userConfig?.get("mode") as? String
            ?: "readable"

        val baseOptions = when (mode.lowercase()) {
            "rag" -> ConversionOptions.RAG
            else -> ConversionOptions.READABLE
        }

        val stripInline = overrides["stripInlineFormatting"] as? Boolean
            ?: projectConfig?.get("stripInlineFormatting") as? Boolean
            ?: userConfig?.get("stripInlineFormatting") as? Boolean
            ?: baseOptions.stripInlineFormatting

        val includeToc = overrides["includeToc"] as? Boolean
            ?: projectConfig?.get("includeToc") as? Boolean
            ?: userConfig?.get("includeToc") as? Boolean
            ?: baseOptions.includeToc

        val epigraphFormat = overrides["epigraphFormat"] as? String
            ?: projectConfig?.get("epigraphFormat") as? String
            ?: userConfig?.get("epigraphFormat") as? String
            ?: baseOptions.epigraphFormat.name.lowercase()

        val advisoryFormat = overrides["advisoryFormat"] as? String
            ?: projectConfig?.get("advisoryFormat") as? String
            ?: userConfig?.get("advisoryFormat") as? String
            ?: baseOptions.advisoryFormat.name.lowercase()

        val normalizeSpans = overrides["normalizeTableSpans"] as? Boolean
            ?: projectConfig?.get("normalizeTableSpans") as? Boolean
            ?: userConfig?.get("normalizeTableSpans") as? Boolean
            ?: baseOptions.normalizeTableSpans

        return ConversionOptions(
            stripInlineFormatting = stripInline,
            includeToc = includeToc,
            epigraphFormat = ConversionOptions.EpigraphFormat.valueOf(epigraphFormat.uppercase()),
            advisoryFormat = ConversionOptions.AdvisoryFormat.valueOf(advisoryFormat.uppercase()),
            normalizeTableSpans = normalizeSpans,
            ruleTuning = ruleTuning,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractRuleTuning(config: Map<String, Any>, target: MutableMap<String, Any?>) {
        val tuningSection = config["ruleTuning"]
        if (tuningSection is Map<*, *>) {
            tuningSection.forEach { (key, value) ->
                target[key.toString()] = value
            }
        }
    }

    /**
     * Returns the list of config file locations that would be checked,
     * in order of precedence (highest first).
     */
    fun getConfigFileLocations(searchStartDir: Path = Path.of("").toAbsolutePath()): List<String> {
        val locations = mutableListOf<String>()

        var currentDir: Path? = searchStartDir.normalize().toAbsolutePath()
        while (currentDir != null && currentDir != currentDir.root) {
            val configFile = currentDir.resolve(CONFIG_FILENAME).toFile()
            locations.add("[Project] ${configFile.absolutePath}")
            currentDir = currentDir.parent
        }

        val homeDir = System.getProperty("user.home")
        if (!homeDir.isNullOrBlank()) {
            locations.add("[User] ${File(homeDir, CONFIG_FILENAME).absolutePath}")
        }

        return locations
    }
}
