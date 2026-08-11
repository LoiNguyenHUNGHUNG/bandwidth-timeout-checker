package io.github.loinguyen.bandwidth.compiler.fir

import io.github.loinguyen.bandwidth.core.DownloadLifetime
import io.github.loinguyen.bandwidth.core.NetworkEffect
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirDeclaration
import org.jetbrains.kotlin.fir.declarations.getAnnotationByClassId
import org.jetbrains.kotlin.fir.expressions.FirAnnotation
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirSpreadArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.FirWrappedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirWrappedExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirResolvedArgumentList
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
internal val BOUNDED_CLIENT_ANNOTATION: ClassId =
    ClassId.topLevel(FqName("io.github.loinguyen.bandwidth.annotations.BoundedClient"))

private val MAX_BYTES: Name = Name.identifier("maxBytes")
private val COMPLETE_TIMEOUT_MILLIS: Name = Name.identifier("completeTimeoutMillis")
private val R_MAX_BYTES_PER_SECOND: Name = Name.identifier("rMaxBytesPerSecond")
private val N_MAX: Name = Name.identifier("nMax")
private val DOWNLOADS: Name = Name.identifier("downloads")
private val MAY_OUTLIVE_CALL: Name = Name.identifier("mayOutliveCall")
private val SELF_BOUND: Name = Name.identifier("selfBound")
private val SELF_BOUND_ENFORCED: Name = Name.identifier("selfBoundEnforced")
private val K: Name = Name.identifier("k")
private val ENFORCED: Name = Name.identifier("enforced")

internal data class DownloadContract(
    val maxBytes: Long,
    val completeTimeoutMillis: Long,
)

internal data class EffectContract(
    val network: NetworkEffect,
)

private data class BandwidthDownloadContract(
    val source: KtSourceElement?,
    val rMaxBytesPerSecond: Long?,
    val nMax: Int?,
    val mayOutliveCall: Boolean,
    val selfBound: Int?,
    val selfBoundEnforced: Boolean,
)

internal data class BoundedClientContract(
    val k: Int,
    val enforced: Boolean,
)

internal data class AnnotationProblem(
    val source: KtSourceElement?,
    val message: String,
)

/**
 * Finds the annotation identified by [classId] on this container.
 *
 * @return the resolved annotation, or `null` when it is absent.
 */
internal fun FirAnnotationContainer.annotation(
    classId: ClassId,
    session: FirSession,
): FirAnnotation? = getAnnotationByClassId(classId, session)

/**
 * Parses a valid `@NetworkDownload` contract from this container.
 *
 * @return the parsed contract, or `null` when the annotation is absent or invalid.
 */
internal fun FirAnnotationContainer.downloadContract(session: FirSession): DownloadContract? {
    val annotation: FirAnnotation = annotation(NETWORK_DOWNLOAD_ANNOTATION, session) ?: return null
    val maxBytes: Long = annotation.longArgument(MAX_BYTES, session) ?: return null
    val timeoutMillis: Long =
        annotation.longArgument(COMPLETE_TIMEOUT_MILLIS, session) ?: return null
    if (maxBytes < 0 || timeoutMillis <= 0) return null
    return DownloadContract(maxBytes, timeoutMillis)
}

/**
 * Parses and combines the shorthand and list entries in a valid
 * `@BandwidthEffect` annotation.
 *
 * @return the parsed effect, or `null` when the annotation is absent or invalid.
 */
internal fun FirAnnotationContainer.effectContract(session: FirSession): EffectContract? {
    val annotation: FirAnnotation = annotation(BANDWIDTH_EFFECT_ANNOTATION, session) ?: return null
    val effects = mutableListOf<NetworkEffect>()
    val rMax: Long = annotation.longArgument(R_MAX_BYTES_PER_SECOND, session) ?: 0
    val nMax: Int = annotation.intArgument(N_MAX, session) ?: 0
    if (rMax < 0 || nMax < 0 || (rMax > 0 && nMax == 0)) return null
    if (rMax > 0 || nMax > 0) {
        effects += NetworkEffect.summary(rMax, nMax)
    }
    annotation.downloadContracts(session).forEach { download ->
        val rate = download.rMaxBytesPerSecond ?: return null
        val concurrency = download.nMax ?: return null
        val selfBound = download.selfBound ?: return null
        if (rate < 0 || concurrency <= 0 || selfBound < 0) return null
        val lifetime = if (download.mayOutliveCall) {
            DownloadLifetime.MAY_OUTLIVE_CALL
        } else {
            DownloadLifetime.COMPLETES_WITH_CALL
        }
        val effect = NetworkEffect.summary(rate, concurrency, lifetime)
        effects += if (selfBound == 0 || !download.selfBoundEnforced) {
            effect
        } else {
            effect.withSelfBound(selfBound)
        }
    }
    return EffectContract(
        effects.fold(NetworkEffect.EMPTY) { result, effect -> result.choice(effect) },
    )
}

/**
 * Parses a positive `@BoundedClient` declaration from this container.
 *
 * @return the declaration, or `null` when the annotation is absent or invalid.
 */
internal fun FirAnnotationContainer.boundedClientContract(
    session: FirSession,
): BoundedClientContract? {
    val annotation: FirAnnotation = annotation(BOUNDED_CLIENT_ANNOTATION, session) ?: return null
    val bound: Int = annotation.intArgument(K, session) ?: return null
    if (bound <= 0) return null
    return BoundedClientContract(
        k = bound,
        enforced = annotation.booleanArgument(ENFORCED, session) == true,
    )
}

/**
 * Validates every supported bandwidth annotation on this declaration and its
 * callable return type.
 *
 * @return all source-located validation problems found on the declaration.
 */
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
    container.annotation(BOUNDED_CLIENT_ANNOTATION, session)?.let { annotation ->
        val bound: Int? = annotation.intArgument(K, session)
        if (bound == null || bound <= 0) {
            add(
                AnnotationProblem(
                    annotation.source ?: source,
                    "@BoundedClient k must be a positive constant.",
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

/** Appends validation problems for one `@NetworkDownload` [annotation]. */
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

/** Appends validation problems for one `@BandwidthEffect` [annotation]. */
private fun MutableList<AnnotationProblem>.validateEffectAnnotation(
    annotation: FirAnnotation,
    session: FirSession,
) {
    val rMax: Long = annotation.longArgument(R_MAX_BYTES_PER_SECOND, session) ?: 0
    val nMax: Int = annotation.intArgument(N_MAX, session) ?: 0
    if (rMax < 0) {
        add(
            AnnotationProblem(
                annotation.source,
                "@BandwidthEffect rMaxBytesPerSecond must be a non-negative constant.",
            ),
        )
    }
    if (nMax < 0) {
        add(
            AnnotationProblem(
                annotation.source,
                "@BandwidthEffect nMax must be a non-negative constant.",
            ),
        )
    }
    if (rMax > 0 && nMax == 0) {
        add(
            AnnotationProblem(
                annotation.source,
                "@BandwidthEffect with a positive rMax must have nMax greater than zero.",
            ),
        )
    }
    annotation.downloadContracts(session).forEach { download ->
        val entryRate = download.rMaxBytesPerSecond
        val entryConcurrency = download.nMax
        if (entryRate == null || entryRate < 0) {
            add(
                AnnotationProblem(
                    download.source ?: annotation.source,
                    "BandwidthDownload rMaxBytesPerSecond must be a non-negative constant.",
                ),
            )
        }
        if (entryConcurrency == null || entryConcurrency <= 0) {
            add(
                AnnotationProblem(
                    download.source ?: annotation.source,
                    "BandwidthDownload nMax must be a positive constant.",
                ),
            )
        }
        val entrySelfBound = download.selfBound
        if (entrySelfBound == null || entrySelfBound < 0) {
            add(
                AnnotationProblem(
                    download.source ?: annotation.source,
                    "BandwidthDownload selfBound must be a non-negative constant.",
                ),
            )
        }
    }
}

/** Returns the integral constant [name] as a [Long], or `null` if unavailable. */
private fun FirAnnotation.longArgument(name: Name, session: FirSession): Long? =
    argument(name)?.constantValue(session).integralLongValue()

/**
 * Returns the integral constant [name] as an [Int], or `null` when unavailable
 * or outside the [Int] range.
 */
private fun FirAnnotation.intArgument(name: Name, session: FirSession): Int? =
    argument(name)?.constantValue(session)
        .integralLongValue()
        ?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }
        ?.toInt()

/** Parses every `BandwidthDownload` entry in this annotation's `downloads` argument. */
private fun FirAnnotation.downloadContracts(session: FirSession): List<BandwidthDownloadContract> {
    return argument(DOWNLOADS)?.downloadContracts(session).orEmpty()
}

/**
 * Recursively extracts `BandwidthDownload` constructor arguments from FIR
 * array, vararg, and wrapper expressions.
 */
private fun FirExpression.downloadContracts(
    session: FirSession,
): List<BandwidthDownloadContract> {
    annotationArrayElements()?.let { elements ->
        return elements.flatMap { it.downloadContracts(session) }
    }
    return when (this) {
        is FirAnnotation -> listOf(
            BandwidthDownloadContract(
                source = source,
                rMaxBytesPerSecond = longArgument(R_MAX_BYTES_PER_SECOND, session),
                nMax = intArgument(N_MAX, session),
                mayOutliveCall = booleanArgument(MAY_OUTLIVE_CALL, session) == true,
                selfBound = intArgument(SELF_BOUND, session) ?: 0,
                selfBoundEnforced = booleanArgument(SELF_BOUND_ENFORCED, session) == true,
            ),
        )
        is FirFunctionCall -> {
            val rate = argument(R_MAX_BYTES_PER_SECOND)
            val concurrency = argument(N_MAX)
            if (rate != null && concurrency != null) {
                listOf(
                    BandwidthDownloadContract(
                        source = source,
                        rMaxBytesPerSecond = rate.constantValue(session).integralLongValue(),
                        nMax = concurrency.constantValue(session)
                            .integralLongValue()
                            ?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }
                            ?.toInt(),
                        mayOutliveCall = argument(MAY_OUTLIVE_CALL)
                            ?.constantValue(session) as? Boolean ?: false,
                        selfBound = argument(SELF_BOUND)
                            ?.constantValue(session)
                            .integralLongValue()
                            ?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }
                            ?.toInt() ?: 0,
                        selfBoundEnforced = argument(SELF_BOUND_ENFORCED)
                            ?.constantValue(session) as? Boolean ?: false,
                    ),
                )
            } else {
                argumentList.arguments.flatMap { it.downloadContracts(session) }
            }
        }
        is FirVarargArgumentsExpression -> arguments.flatMap { it.downloadContracts(session) }
        is FirNamedArgumentExpression -> expression.downloadContracts(session)
        is FirSpreadArgumentExpression -> expression.downloadContracts(session)
        is FirWrappedArgumentExpression -> expression.downloadContracts(session)
        is FirWrappedExpression -> expression.downloadContracts(session)
        else -> emptyList()
    }
}

/** Returns the resolved call argument mapped to the parameter named [name]. */
private fun FirFunctionCall.argument(name: Name): FirExpression? {
    val mapping = (argumentList as? FirResolvedArgumentList)?.mapping ?: return null
    return mapping.entries.firstOrNull { (_, parameter) -> parameter.name == name }?.key
}

/** Returns the constant Boolean [name], or `null` when it cannot be evaluated. */
private fun FirAnnotation.booleanArgument(name: Name, session: FirSession): Boolean? =
    argument(name)?.constantValue(session) as? Boolean

/** Returns the raw annotation argument named [name], if present. */
private fun FirAnnotation.argument(name: Name): FirExpression? =
    argumentMapping.mapping[name]

/** Evaluates this expression as a compiler-known literal when possible. */
private fun FirExpression.constantValue(session: FirSession): Any? =
    (this as? FirLiteralExpression)?.value
        ?: evaluateAs<FirLiteralExpression>(session)?.value

/** Converts a boxed Kotlin integral value to [Long], rejecting other values. */
private fun Any?.integralLongValue(): Long? =
    when (this) {
        is Byte -> toLong()
        is Short -> toLong()
        is Int -> toLong()
        is Long -> this
        else -> null
    }
