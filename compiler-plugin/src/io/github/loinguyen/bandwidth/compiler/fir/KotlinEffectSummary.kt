package io.github.loinguyen.bandwidth.compiler.fir

import io.github.loinguyen.bandwidth.core.NetworkEffect
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol

/** Effect suspended inside a Kotlin function value. */
internal data class LatentNetworkEffect(
    val network: NetworkEffect = NetworkEffect.EMPTY,
    val returned: LatentNetworkEffect? = null,
) {
    fun join(other: LatentNetworkEffect): LatentNetworkEffect =
        LatentNetworkEffect(
            network = network.choice(other.network),
            returned = returned.join(other.returned),
        )

    fun materialize(): NetworkEffect = network
}

/** The effect component paired with Kotlin's already resolved type. */
internal data class KotlinExpressionEffect(
    val network: NetworkEffect = NetworkEffect.EMPTY,
    val latent: LatentNetworkEffect? = null,
) {
    fun materialize(): NetworkEffect = network
}

internal fun KotlinExpressionEffect.then(other: KotlinExpressionEffect): KotlinExpressionEffect =
    KotlinExpressionEffect(
        network = network.then(other.network),
        latent = other.latent,
    )

internal fun KotlinExpressionEffect.parallel(other: KotlinExpressionEffect): KotlinExpressionEffect =
    KotlinExpressionEffect(
        network = network.parallel(other.network),
    )

/** Interprocedural summary keyed by the FIR function symbol. */
internal data class KotlinFunctionEffect(
    val network: NetworkEffect = NetworkEffect.EMPTY,
    val returned: LatentNetworkEffect? = null,
) {
    fun asLatent(): LatentNetworkEffect =
        LatentNetworkEffect(
            network = network,
            returned = returned,
        )

    fun materialize(): NetworkEffect = network
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
    LatentNetworkEffect(network = toNetworkEffect())

internal fun LatentNetworkEffect?.join(other: LatentNetworkEffect?): LatentNetworkEffect? = when {
    this == null -> other
    other == null -> this
    else -> join(other)
}
