package io.github.loinguyen.bandwidth.compiler.fir

import io.github.loinguyen.bandwidth.core.NetworkEffect
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol

/**
 * Effect suspended inside a Kotlin function value.
 *
 * [invocation] happens when the value is called. [returned] models a function
 * value returned by that call, allowing higher-order factories to compose.
 */
internal data class LatentNetworkEffect(
    val invocation: NetworkEffect,
    val returned: LatentNetworkEffect? = null,
) {
    fun join(other: LatentNetworkEffect): LatentNetworkEffect =
        LatentNetworkEffect(
            invocation = invocation.then(other.invocation),
            returned = returned.join(other.returned),
        )
}

/**
 * The effect component paired with the Kotlin type already resolved on FIR.
 */
internal data class KotlinExpressionEffect(
    val immediate: NetworkEffect = NetworkEffect.EMPTY,
    val latent: LatentNetworkEffect? = null,
    /**
     * A client cap carried while every non-empty immediate effect in this
     * expression uses that same client. The cap is erased once work from an
     * unbounded or differently bounded client is combined.
     */
    val boundedClientK: Int? = null,
)

internal fun KotlinExpressionEffect.then(other: KotlinExpressionEffect): KotlinExpressionEffect =
    combineImmediate(other, NetworkEffect::then, other.latent)

internal fun KotlinExpressionEffect.parallel(other: KotlinExpressionEffect): KotlinExpressionEffect =
    combineImmediate(other, NetworkEffect::parallel)

private fun KotlinExpressionEffect.combineImmediate(
    other: KotlinExpressionEffect,
    compose: (NetworkEffect, NetworkEffect) -> NetworkEffect,
    latent: LatentNetworkEffect? = null,
): KotlinExpressionEffect {
    val combined = compose(immediate, other.immediate)
    val bound = compatibleBound(other)
    return KotlinExpressionEffect(
        immediate = bound?.let(combined::capConcurrency) ?: combined,
        latent = latent,
        boundedClientK = bound,
    )
}

private fun KotlinExpressionEffect.compatibleBound(other: KotlinExpressionEffect): Int? = when {
    immediate == NetworkEffect.EMPTY -> other.boundedClientK
    other.immediate == NetworkEffect.EMPTY -> boundedClientK
    boundedClientK != null && boundedClientK == other.boundedClientK -> boundedClientK
    else -> null
}

/**
 * Interprocedural summary keyed by the FIR function symbol.
 */
internal data class KotlinFunctionEffect(
    val invocation: NetworkEffect = NetworkEffect.EMPTY,
    val returned: LatentNetworkEffect? = null,
) {
    fun asLatent(): LatentNetworkEffect =
        LatentNetworkEffect(
            invocation = invocation,
            returned = returned,
        )
}

/**
 * Lexical environment for latent function values and returned callbacks.
 */
internal class KotlinEffectContext(
    val function: FirFunction,
    private val session: FirSession,
    private val latentValues: MutableMap<FirBasedSymbol<*>, LatentNetworkEffect>,
) {
    var returnedLatent: LatentNetworkEffect? = null
        private set

    fun bind(
        symbol: FirBasedSymbol<*>?,
        latent: LatentNetworkEffect?,
    ) {
        if (symbol == null || latent == null) return
        latentValues[symbol] = latentValues[symbol]?.join(latent) ?: latent
    }

    fun latentOf(symbol: FirBasedSymbol<*>?): LatentNetworkEffect? {
        symbol ?: return null
        latentValues[symbol]?.let { return it }
        val declaration = symbol.fir
        val direct = (declaration as? FirAnnotationContainer)
            ?.effectContract(session)
            ?.toLatentEffect()
        if (direct != null) return direct
        return (declaration as? FirCallableDeclaration)
            ?.returnTypeRef
            ?.effectContract(session)
            ?.toLatentEffect()
    }

    fun recordReturn(latent: LatentNetworkEffect?) {
        if (latent == null) return
        returnedLatent = returnedLatent?.join(latent) ?: latent
    }
}

internal fun EffectContract.toLatentEffect(): LatentNetworkEffect =
    LatentNetworkEffect(invocation = toNetworkEffect())

internal fun LatentNetworkEffect?.join(
    other: LatentNetworkEffect?,
): LatentNetworkEffect? = when {
    this == null -> other
    other == null -> this
    else -> join(other)
}
