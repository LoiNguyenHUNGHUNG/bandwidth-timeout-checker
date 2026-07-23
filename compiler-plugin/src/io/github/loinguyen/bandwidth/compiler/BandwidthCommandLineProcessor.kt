package io.github.loinguyen.bandwidth.compiler

import io.github.loinguyen.bandwidth.BuildConfig
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.config.CompilerConfiguration

@Suppress("unused") // Loaded through ServiceLoader.
class BandwidthCommandLineProcessor : CommandLineProcessor {
    override val pluginId: String = BuildConfig.KOTLIN_PLUGIN_ID

    override val pluginOptions: Collection<CliOption> = listOf(ENABLED_OPTION)

    override fun processOption(
        option: AbstractCliOption,
        value: String,
        configuration: CompilerConfiguration,
    ) {
        when (option.optionName) {
            ENABLED_OPTION.optionName -> configuration.put(
                BandwidthConfiguration.ENABLED,
                value.toBooleanStrict(),
            )
            else -> error("Unexpected config option: '${option.optionName}'")
        }
    }

    private companion object {
        val ENABLED_OPTION: CliOption = CliOption(
            optionName = "enabled",
            valueDescription = "<true|false>",
            description = "Enable bandwidth-timeout checking.",
            required = false,
            allowMultipleOccurrences = false,
        )
    }
}
