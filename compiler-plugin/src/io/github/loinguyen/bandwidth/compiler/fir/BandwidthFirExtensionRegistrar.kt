package io.github.loinguyen.bandwidth.compiler.fir

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

internal class BandwidthFirExtensionRegistrar(
    private val messages: MessageCollector,
    private val reportEffects: Boolean,
    private val applicationEntryPoints: ApplicationEntryPointEffects,
) : FirExtensionRegistrar() {
    /** Registers the declaration checkers and their diagnostic definitions. */
    override fun ExtensionRegistrarContext.configurePlugin() {
        +{ session: FirSession ->
            BandwidthFirCheckersExtension(
                session = session,
                messages = messages,
                reportEffects = reportEffects,
                applicationEntryPoints = applicationEntryPoints,
            )
        }
        registerDiagnosticContainers(BandwidthDiagnostics)
    }
}
