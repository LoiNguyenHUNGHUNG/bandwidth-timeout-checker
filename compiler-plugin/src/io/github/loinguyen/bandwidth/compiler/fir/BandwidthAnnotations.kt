package io.github.loinguyen.bandwidth.compiler.fir

import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.declarations.evaluateAs
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal val NETWORK_DOWNLOAD_ANNOTATION: ClassId =
    ClassId.topLevel(FqName("io.github.loinguyen.bandwidth.annotations.NetworkDownload"))
internal val BANDWIDTH_EFFECT_ANNOTATION: ClassId =
    ClassId.topLevel(FqName("io.github.loinguyen.bandwidth.annotations.BandwidthEffect"))
internal val BOUNDED_SCOPE_ANNOTATION: ClassId =
    ClassId.topLevel(FqName("io.github.loinguyen.bandwidth.annotations.BoundedScope"))

private val MAX_BYTES: Name = Name.identifier("maxBytes")
private val COMPLETE_TIMEOUT_MILLIS: Name = Name.identifier("completeTimeoutMillis")
private val R_MAX_BYTES_PER_SECOND: Name = Name.identifier("rMaxBytesPerSecond")
private val N_MAX: Name = Name.identifier("nMax")
private val K: Name = Name.identifier("k")

internal data class DownloadContract(
    val maxBytes: Long,
    val completeTimeoutMillis: Long,
)

internal data class EffectContract(
    val rMaxBytesPerSecond: Long,
    val nMax: Int,
)

internal data class AnnotationProblem(
    val source: KtSourceElement?,
    val message: String,
)

internal fun FirAnnotationContainer.annotation(
    classId: ClassId,
    session: FirSession,
): FirAnnotation? = getAnnotationByClassId(classId, session)

internal fun FirAnnotationContainer.downloadContract(session: FirSession): DownloadContract? {
    val annotation: FirAnnotation = annotation(NETWORK_DOWNLOAD_ANNOTATION, session) ?: return null
    val maxBytes: Long = annotation.longArgument(MAX_BYTES, session) ?: return null
    val timeoutMillis: Long =
        annotation.longArgument(COMPLETE_TIMEOUT_MILLIS, session) ?: return null
    if (maxBytes < 0 || timeoutMillis <= 0) return null
    return DownloadContract(maxBytes, timeoutMillis)
}

internal fun FirAnnotationContainer.effectContract(session: FirSession): EffectContract? {
    val annotation: FirAnnotation = annotation(BANDWIDTH_EFFECT_ANNOTATION, session) ?: return null
    val rMax: Long = annotation.longArgument(R_MAX_BYTES_PER_SECOND, session) ?: return null
    val nMax: Int = annotation.intArgument(N_MAX, session) ?: return null
    if (rMax < 0 || nMax < 0 || (rMax > 0 && nMax == 0)) return null
    return EffectContract(rMax, nMax)
}

internal fun FirDeclaration.validateBandwidthAnnotations(
    session: FirSession,
): List<AnnotationProblem> = buildList {
    val container: FirAnnotationContainer = this@validateBandwidthAnnotations
    val download = container.annotation(NETWORK_DOWNLOAD_ANNOTATION, session)
    val effect = container.annotation(BANDWIDTH_EFFECT_ANNOTATION, session)
    if (download != null && effect != null) {
        add(
            AnnotationProblem(
                source = source,
                message = "@NetworkDownload and @BandwidthEffect cannot annotate " +
                    "the same declaration.",
            ),
        )
    }

    download?.let { validateDownloadAnnotation(it, session) }
    effect?.let { validateEffectAnnotation(it, session) }
    container.annotation(BOUNDED_SCOPE_ANNOTATION, session)?.let { annotation ->
        val bound: Int? = annotation.intArgument(K, session)
        if (bound == null || bound <= 0) {
            add(
                AnnotationProblem(
                    annotation.source ?: source,
                    "@BoundedScope k must be a positive constant.",
                ),
            )
        }
    }

    if (this@validateBandwidthAnnotations is FirCallableDeclaration) {
        returnTypeRef.annotation(BANDWIDTH_EFFECT_ANNOTATION, session)?.let { annotation ->
            validateEffectAnnotation(annotation, session)
        }
    }
}

private fun MutableList<AnnotationProblem>.validateDownloadAnnotation(
    annotation: FirAnnotation,
    session: FirSession,
) {
    val maxBytes: Long? = annotation.longArgument(MAX_BYTES, session)
    val timeoutMillis: Long? = annotation.longArgument(COMPLETE_TIMEOUT_MILLIS, session)
    if (maxBytes == null || maxBytes < 0) {
        add(
            AnnotationProblem(
                annotation.source,
                "@NetworkDownload maxBytes must be a non-negative constant.",
            ),
        )
    }
    if (timeoutMillis == null || timeoutMillis <= 0) {
        add(
            AnnotationProblem(
                annotation.source,
                "@NetworkDownload completeTimeoutMillis must be a positive constant.",
            ),
        )
    }
}

private fun MutableList<AnnotationProblem>.validateEffectAnnotation(
    annotation: FirAnnotation,
    session: FirSession,
) {
    val rMax: Long? = annotation.longArgument(R_MAX_BYTES_PER_SECOND, session)
    val nMax: Int? = annotation.intArgument(N_MAX, session)
    if (rMax == null || rMax < 0) {
        add(
            AnnotationProblem(
                annotation.source,
                "@BandwidthEffect rMaxBytesPerSecond must be a non-negative constant.",
            ),
        )
    }
    if (nMax == null || nMax < 0) {
        add(
            AnnotationProblem(
                annotation.source,
                "@BandwidthEffect nMax must be a non-negative constant.",
            ),
        )
    }
    if (rMax != null && rMax > 0 && nMax == 0) {
        add(
            AnnotationProblem(
                annotation.source,
                "@BandwidthEffect with a positive rMax must have nMax greater than zero.",
            ),
        )
    }
}

private fun FirAnnotation.longArgument(name: Name, session: FirSession): Long? =
    argument(name)?.constantValue(session) as? Long

private fun FirAnnotation.intArgument(name: Name, session: FirSession): Int? =
    argument(name)?.constantValue(session) as? Int

private fun FirAnnotation.argument(name: Name): FirExpression? =
    argumentMapping.mapping[name]

private fun FirExpression.constantValue(session: FirSession): Any? =
    (this as? FirLiteralExpression)?.value
        ?: evaluateAs<FirLiteralExpression>(session)?.value
