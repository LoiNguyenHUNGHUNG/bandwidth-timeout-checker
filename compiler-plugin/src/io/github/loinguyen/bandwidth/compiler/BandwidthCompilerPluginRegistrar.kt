package io.github.loinguyen.bandwidth.compiler

import io.github.loinguyen.bandwidth.BuildConfig
import io.github.loinguyen.bandwidth.compiler.fir.BandwidthFirExtensionRegistrar
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

@Suppress("unused") // Loaded through ServiceLoader.
class BandwidthCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val pluginId: String = BuildConfig.KOTLIN_PLUGIN_ID
    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        if (!configuration.get(BandwidthConfiguration.ENABLED, true)) return

        val messages: MessageCollector =
            configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        FirExtensionRegistrarAdapter.registerExtension(
            BandwidthFirExtensionRegistrar(
                messages = messages,
                reportEffects = configuration.get(BandwidthConfiguration.REPORT_EFFECTS, false),
            ),
        )
    }
}
