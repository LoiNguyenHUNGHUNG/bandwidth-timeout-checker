package io.github.loinguyen.bandwidth.compiler.fir

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.ConeStarProjection
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.hasError
import org.jetbrains.kotlin.fir.types.impl.ConeClassLikeTypeImpl
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.isSubtypeOf
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.StandardClassIds

private val bandwidthFlowClassId = ClassId.topLevel(FqName("kotlinx.coroutines.flow.Flow"))
private val bandwidthReceiveChannelClassId =
    ClassId.topLevel(FqName("kotlinx.coroutines.channels.ReceiveChannel"))

/**
 * Flow contracts describe collection, not construction or an already-running
 * producer. Use Kotlin's resolved subtype relation so StateFlow, SharedFlow,
 * aliases, custom interfaces and Flow-bounded type parameters obey the same
 * parameter, return and storage contract discipline as Flow itself.
 */
internal fun ConeKotlinType.isBandwidthFlow(session: FirSession): Boolean =
    isBandwidthSubtypeOf(bandwidthFlowClassId, session)

/**
 * Identifies channel receivers solely for checked channel metadata boundaries.
 * Channels are not themselves classified as latent Flow/function values.
 */
internal fun ConeKotlinType.isBandwidthReceiveChannel(session: FirSession): Boolean =
    isBandwidthSubtypeOf(bandwidthReceiveChannelClassId, session)

/** Tests a resolved nullable one-parameter protocol without permissive error recovery. */
private fun ConeKotlinType.isBandwidthSubtypeOf(
    protocolClassId: ClassId,
    session: FirSession,
): Boolean {
    val expanded = fullyExpandedType(session)
    // Nothing is a subtype of every type, but does not denote a stored
    // protocol value. Error types must not pass through type recovery.
    if (expanded.hasError() || expanded.classId == StandardClassIds.Nothing) return false
    if (expanded.classId == protocolClassId) return true
    val protocolSymbol = session.symbolProvider.getClassLikeSymbolByClassId(protocolClassId)
        ?: return false
    val nullableProtocol = ConeClassLikeTypeImpl(
        protocolSymbol.toLookupTag(),
        arrayOf(ConeStarProjection),
        true,
    )
    // Nullable Flow storage already has checked null-value behavior. Channel
    // classification likewise must not miss a mutable nullable alias.
    return expanded.isSubtypeOf(nullableProtocol, session, false)
}

/** Values whose invocation/collection effect is carried separately from eager evaluation. */
internal fun ConeKotlinType.isBandwidthLatentValue(session: FirSession): Boolean =
    isSomeFunctionType(session) || isBandwidthFlow(session)
