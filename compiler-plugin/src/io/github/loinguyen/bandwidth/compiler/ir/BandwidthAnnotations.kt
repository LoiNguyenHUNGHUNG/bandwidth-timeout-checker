package io.github.loinguyen.bandwidth.compiler.ir

import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.parentAsClass

internal const val NETWORK_DOWNLOAD_ANNOTATION: String =
    "io.github.loinguyen.bandwidth.annotations.NetworkDownload"
internal const val BANDWIDTH_EFFECT_ANNOTATION: String =
    "io.github.loinguyen.bandwidth.annotations.BandwidthEffect"
internal const val BOUNDED_SCOPE_ANNOTATION: String =
    "io.github.loinguyen.bandwidth.annotations.BoundedScope"

internal data class DownloadContract(
    val maxBytes: Long,
    val completeTimeoutMillis: Long,
)

internal data class EffectContract(
    val rMaxBytesPerSecond: Long,
    val nMax: Int,
)

internal fun IrAnnotationContainer.annotation(fqName: String): IrConstructorCall? =
    annotations.firstOrNull {
        it.symbol.owner.parentAsClass.fqNameWhenAvailable?.asString() == fqName
    }

internal fun IrAnnotationContainer.downloadContract(): DownloadContract? {
    val annotation: IrConstructorCall = annotation(NETWORK_DOWNLOAD_ANNOTATION) ?: return null
    val maxBytes: Long = annotation.longArgument(0) ?: return null
    val timeoutMillis: Long = annotation.longArgument(1) ?: return null
    if (maxBytes < 0 || timeoutMillis <= 0) return null
    return DownloadContract(maxBytes, timeoutMillis)
}

internal fun IrAnnotationContainer.effectContract(): EffectContract? {
    val annotation: IrConstructorCall = annotation(BANDWIDTH_EFFECT_ANNOTATION) ?: return null
    val rMax: Long = annotation.longArgument(0) ?: return null
    val nMax: Int = annotation.intArgument(1) ?: return null
    if (rMax < 0 || nMax < 0 || (rMax > 0 && nMax == 0)) return null
    return EffectContract(rMax, nMax)
}

internal fun IrConstructorCall.longArgument(index: Int): Long? =
    (arguments[index] as? IrConst)?.value as? Long

internal fun IrConstructorCall.intArgument(index: Int): Int? =
    (arguments[index] as? IrConst)?.value as? Int
