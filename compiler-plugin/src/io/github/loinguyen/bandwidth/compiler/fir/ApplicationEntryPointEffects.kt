package io.github.loinguyen.bandwidth.compiler.fir

import io.github.loinguyen.bandwidth.core.NetworkEffect
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment

/**
 * The virtual application root formed by all annotated framework roots.
 *
 * FIR records each fully inferred entry-point effect. The IR hook runs after
 * frontend analysis has visited the whole module and reports their parallel
 * composition; it does not inspect or transform IR.
 */
internal class ApplicationEntryPointEffects(
    private val messages: MessageCollector,
    private val reportEffects: Boolean,
) : IrGenerationExtension {
    private val lock = Any()
    private val effects = mutableListOf<NetworkEffect>()

    /** Adds one otherwise potentially unreachable root invocation. */
    fun record(effect: NetworkEffect) {
        synchronized(lock) {
            effects += effect
        }
    }

    /** Reports `entry1 || ... || entryN` after all FIR checks complete. */
    override fun generate(
        moduleFragment: IrModuleFragment,
        pluginContext: IrPluginContext,
    ) {
        if (!reportEffects) return
        val snapshot = synchronized(lock) { effects.toList() }
        if (snapshot.isEmpty()) return
        val application = snapshot.fold(NetworkEffect.EMPTY) { result, effect ->
            result.parallel(effect)
        }
        messages.report(
            CompilerMessageSeverity.INFO,
            "Inferred application entry-point effect from ${snapshot.size} entry point(s): " +
                "${application.render()}, " +
                "ReqBW=${application.requiredBandwidthBytesPerSecond()} bytes/s.",
        )
    }
}
