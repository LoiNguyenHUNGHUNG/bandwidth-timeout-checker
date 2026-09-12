package io.github.loinguyen.bandwidth.compiler

import io.github.loinguyen.bandwidth.compiler.fir.BandwidthFirExtensionRegistrar
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

/** Shared compiler registration independent of the Kotlin compiler version. */
abstract class AbstractBandwidthCompilerPluginRegistrar : CompilerPluginRegistrar() {
    override val supportsK2: Boolean = true

    /**
     * Registers the FIR checker when enabled in [configuration].
     *
     * The configured message collector and effect-reporting flag are forwarded
     * to the version-aligned FIR extension.
     */
    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        if (!configuration.get(BandwidthConfiguration.ENABLED, true)) return

        val messages: MessageCollector =
            configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        FirExtensionRegistrarAdapter.registerExtension(
            BandwidthFirExtensionRegistrar(
                messages = messages,
                reportEffects = configuration.get(BandwidthConfiguration.REPORT_EFFECTS, false),
                entryPoints = configuration.get(BandwidthConfiguration.ENTRY_POINTS, emptySet()),
            ),
        )
    }
}
