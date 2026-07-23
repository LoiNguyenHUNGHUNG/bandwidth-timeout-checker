package io.github.loinguyen.bandwidth.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/**
 * Validates the annotation discipline and infers quantitative effects directly
 * from Kotlin IR. The bounded-scope transformation deliberately comes later.
 */
internal class BandwidthIrGenerationExtension(
    private val messages: MessageCollector,
    private val reportEffects: Boolean,
) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        moduleFragment.acceptChildrenVoid(AnnotationValidator(messages))
        KotlinNetworkEffectInference(
            moduleFragment = moduleFragment,
            messages = messages,
            reportEffects = reportEffects,
        ).analyze()
    }
}

private class AnnotationValidator(
    private val messages: MessageCollector,
) : IrVisitorVoid() {
    override fun visitElement(element: IrElement) {
        if (element is IrAnnotationContainer) {
            validate(element)
        }
        element.acceptChildrenVoid(this)
    }

    private fun validate(container: IrAnnotationContainer) {
        val location = (container as? IrDeclaration)?.messageLocation()
        val downloadAnnotation = container.annotation(NETWORK_DOWNLOAD_ANNOTATION)
        val effectAnnotation = container.annotation(BANDWIDTH_EFFECT_ANNOTATION)
        if (downloadAnnotation != null && effectAnnotation != null) {
            error(
                "@NetworkDownload and @BandwidthEffect cannot annotate the same declaration.",
                location,
            )
        }

        downloadAnnotation?.let { annotation ->
            val maxBytes: Long? = annotation.longArgument(0)
            val timeoutMillis: Long? = annotation.longArgument(1)
            if (maxBytes == null || maxBytes < 0) {
                error("@NetworkDownload maxBytes must be a non-negative constant.", location)
            }
            if (timeoutMillis == null || timeoutMillis <= 0) {
                error(
                    "@NetworkDownload completeTimeoutMillis must be a positive constant.",
                    location,
                )
            }
        }

        effectAnnotation?.let { annotation ->
            val rMax: Long? = annotation.longArgument(0)
            val nMax: Int? = annotation.intArgument(1)
            if (rMax == null || rMax < 0) {
                error(
                    "@BandwidthEffect rMaxBytesPerSecond must be a non-negative constant.",
                    location,
                )
            }
            if (nMax == null || nMax < 0) {
                error("@BandwidthEffect nMax must be a non-negative constant.", location)
            }
            if (rMax != null && rMax > 0 && nMax == 0) {
                error(
                    "@BandwidthEffect with a positive rMax must have nMax greater than zero.",
                    location,
                )
            }
        }

        container.annotation(BOUNDED_SCOPE_ANNOTATION)?.let { annotation ->
            val bound: Int? = annotation.intArgument(0)
            if (bound == null || bound <= 0) {
                error("@BoundedScope k must be a positive constant.", location)
            }
        }
    }

    private fun error(
        message: String,
        location: org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation?,
    ) {
        messages.report(CompilerMessageSeverity.ERROR, message, location)
    }
}
