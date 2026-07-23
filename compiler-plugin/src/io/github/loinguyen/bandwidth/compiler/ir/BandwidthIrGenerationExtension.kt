package io.github.loinguyen.bandwidth.compiler.ir

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/**
 * First compiler milestone: validates the annotation discipline and provides
 * the IR seam where Kotlin code will be lowered to the language-independent
 * network IR. The bounded-scope transformation deliberately comes later.
 */
internal class BandwidthIrGenerationExtension(
    private val messages: MessageCollector,
) : IrGenerationExtension {
    override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
        moduleFragment.acceptChildrenVoid(AnnotationValidator(messages))
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
        container.annotation(NETWORK_DOWNLOAD)?.let { annotation ->
            val maxBytes: Long? = annotation.longArgument(0)
            val timeoutMillis: Long? = annotation.longArgument(1)
            if (maxBytes == null || maxBytes < 0) {
                error("@NetworkDownload maxBytes must be a non-negative constant.")
            }
            if (timeoutMillis == null || timeoutMillis <= 0) {
                error("@NetworkDownload completeTimeoutMillis must be a positive constant.")
            }
        }

        container.annotation(BANDWIDTH_EFFECT)?.let { annotation ->
            val rMax: Long? = annotation.longArgument(0)
            val nMax: Int? = annotation.intArgument(1)
            if (rMax == null || rMax < 0) {
                error("@BandwidthEffect rMaxBytesPerSecond must be a non-negative constant.")
            }
            if (nMax == null || nMax < 0) {
                error("@BandwidthEffect nMax must be a non-negative constant.")
            }
            if (rMax != null && rMax > 0 && nMax == 0) {
                error("@BandwidthEffect with a positive rMax must have nMax greater than zero.")
            }
        }

        container.annotation(BOUNDED_SCOPE)?.let { annotation ->
            val bound: Int? = annotation.intArgument(0)
            if (bound == null || bound <= 0) {
                error("@BoundedScope k must be a positive constant.")
            }
        }
    }

    private fun IrAnnotationContainer.annotation(fqName: String): IrConstructorCall? =
        annotations.firstOrNull {
            it.symbol.owner.parentAsClass.fqNameWhenAvailable?.asString() == fqName
        }

    private fun IrConstructorCall.longArgument(index: Int): Long? =
        (arguments[index] as? IrConst)?.value as? Long

    private fun IrConstructorCall.intArgument(index: Int): Int? =
        (arguments[index] as? IrConst)?.value as? Int

    private fun error(message: String) {
        messages.report(CompilerMessageSeverity.ERROR, message)
    }

    private companion object {
        const val NETWORK_DOWNLOAD: String =
            "io.github.loinguyen.bandwidth.annotations.NetworkDownload"
        const val BANDWIDTH_EFFECT: String =
            "io.github.loinguyen.bandwidth.annotations.BandwidthEffect"
        const val BOUNDED_SCOPE: String =
            "io.github.loinguyen.bandwidth.annotations.BoundedScope"
    }
}
