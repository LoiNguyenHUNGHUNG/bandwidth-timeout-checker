package io.github.loinguyen.bandwidth.compiler.fir

import io.github.loinguyen.bandwidth.core.NetworkEffect
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol

/** Effect suspended inside a Kotlin function value. */
internal data class LatentNetworkEffect(
    val standard: NetworkEffect = NetworkEffect.EMPTY,
    val longLived: NetworkEffect = NetworkEffect.EMPTY,
    val returned: LatentNetworkEffect? = null,
) {
    fun join(other: LatentNetworkEffect): LatentNetworkEffect =
        LatentNetworkEffect(
            standard = standard.then(other.standard),
            longLived = longLived.then(other.longLived),
            returned = returned.join(other.returned),
        )

    fun materialize(): NetworkEffect = standard.parallel(longLived)
}

/** The effect component paired with Kotlin's already resolved type. */
internal data class KotlinExpressionEffect(
    val standard: NetworkEffect = NetworkEffect.EMPTY,
    val longLived: NetworkEffect = NetworkEffect.EMPTY,
    val latent: LatentNetworkEffect? = null,
) {
    fun materialize(): NetworkEffect = standard.parallel(longLived)
}

internal fun KotlinExpressionEffect.then(other: KotlinExpressionEffect): KotlinExpressionEffect =
    KotlinExpressionEffect(
        standard = standard.then(other.standard),
        longLived = longLived.parallel(other.longLived),
        latent = other.latent,
    )

internal fun KotlinExpressionEffect.parallel(other: KotlinExpressionEffect): KotlinExpressionEffect =
    KotlinExpressionEffect(
        standard = standard.parallel(other.standard),
        longLived = longLived.parallel(other.longLived),
    )

/** Interprocedural summary keyed by the FIR function symbol. */
internal data class KotlinFunctionEffect(
    val standard: NetworkEffect = NetworkEffect.EMPTY,
    val longLived: NetworkEffect = NetworkEffect.EMPTY,
    val returned: LatentNetworkEffect? = null,
) {
    fun asLatent(): LatentNetworkEffect =
        LatentNetworkEffect(
            standard = standard,
            longLived = longLived,
            returned = returned,
        )

    fun materialize(): NetworkEffect = standard.parallel(longLived)
}

/** Lexical environment for latent function values and returned callbacks. */
internal class KotlinEffectContext(
    val function: FirFunction,
    private val session: FirSession,
    private val latentValues: MutableMap<FirBasedSymbol<*>, LatentNetworkEffect>,
) {
    var returnedLatent: LatentNetworkEffect? = null
        private set

    fun bind(symbol: FirBasedSymbol<*>?, latent: LatentNetworkEffect?) {
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
    LatentNetworkEffect(standard = toNetworkEffect())

internal fun LatentNetworkEffect?.join(other: LatentNetworkEffect?): LatentNetworkEffect? = when {
    this == null -> other
    other == null -> this
    else -> join(other)
}
