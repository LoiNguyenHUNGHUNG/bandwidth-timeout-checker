package io.github.loinguyen.bandwidth.compiler.fir

import io.github.loinguyen.bandwidth.core.NetworkEffect
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isSomeFunctionType

/** Effect suspended inside a value, such as a function or lazy Flow. */
internal data class LatentNetworkEffect(
    val network: NetworkEffect = NetworkEffect.EMPTY,
    val returned: LatentNetworkEffect? = null,
) {
    /**
     * Conservatively joins this latent effect with [other], including latent
     * values returned when either operand is materialized.
     */
    fun join(other: LatentNetworkEffect): LatentNetworkEffect =
        LatentNetworkEffect(
            network = network.choice(other.network),
            returned = returned.join(other.returned),
        )

    /** Returns the network work performed when this latent value is materialized. */
    fun materialize(): NetworkEffect = network
}

/** The effect component paired with Kotlin's already resolved type. */
internal data class KotlinExpressionEffect(
    val network: NetworkEffect = NetworkEffect.EMPTY,
    val latent: LatentNetworkEffect? = null,
) {
    /** Returns the eager network effect of evaluating the expression. */
    fun materialize(): NetworkEffect = network
}

/**
 * Sequentially composes expression effects, preserving only the value produced
 * by [other] as the resulting latent value.
 */
internal fun KotlinExpressionEffect.then(other: KotlinExpressionEffect): KotlinExpressionEffect =
    KotlinExpressionEffect(
        network = network.then(other.network),
        latent = other.latent,
    )

/**
 * Composes the eager work of two concurrently evaluated expressions.
 *
 * Latent values are discarded because parallel composition does not select a
 * single resulting Kotlin value.
 */
internal fun KotlinExpressionEffect.parallel(other: KotlinExpressionEffect): KotlinExpressionEffect =
    KotlinExpressionEffect(
        network = network.parallel(other.network),
    )

/** Interprocedural summary keyed by the FIR function symbol. */
internal data class KotlinFunctionEffect(
    val network: NetworkEffect = NetworkEffect.EMPTY,
    val returned: LatentNetworkEffect? = null,
) {
    /** Converts this interprocedural summary to a storable latent function effect. */
    fun asLatent(): LatentNetworkEffect =
        LatentNetworkEffect(
            network = network,
            returned = returned,
        )

    /** Returns the network work performed by invoking the summarized function. */
    fun materialize(): NetworkEffect = network
}

/** Lexical environment for latent function and Flow values. */
internal class KotlinEffectContext(
    val function: FirFunction,
    private val session: FirSession,
    private val latentValues: MutableMap<FirBasedSymbol<*>, LatentNetworkEffect>,
) {
    var returnedLatent: LatentNetworkEffect? = null
        private set

    /**
     * Associates [latent] with [symbol], joining it with any prior branch value.
     *
     * Null symbols and expressions without latent values are ignored.
     */
    fun bind(symbol: FirBasedSymbol<*>?, latent: LatentNetworkEffect?) {
        if (symbol == null || latent == null) return
        latentValues[symbol] = latentValues[symbol]?.join(latent) ?: latent
    }

    /**
     * Resolves the latent effect of [symbol] from lexical bindings or an
     * annotation on the declaration or its return type.
     */
    fun latentOf(symbol: FirBasedSymbol<*>?): LatentNetworkEffect? {
        symbol ?: return null
        latentValues[symbol]?.let { return it }
        val declaration = symbol.fir
        val direct = (declaration as? FirAnnotationContainer)
            ?.effectContract(session)
            ?.takeIf { it.variables.isEmpty() }
            ?.toLatentEffect()
        if (direct != null) return direct
        if (
            declaration is FirValueParameter &&
            declaration.returnTypeRef.coneType.isSomeFunctionType(session)
        ) {
            return LatentNetworkEffect()
        }
        return (declaration as? FirCallableDeclaration)
            ?.returnTypeRef
            ?.effectContract(session)
            ?.takeIf { it.variables.isEmpty() }
            ?.toLatentEffect()
    }

    /** Joins a latent-valued return into this function's returned effect. */
    fun recordReturn(latent: LatentNetworkEffect?) {
        if (latent == null) return
        returnedLatent = returnedLatent?.join(latent) ?: latent
    }
}

/** Converts this annotation contract to a latent function-value effect. */
internal fun EffectContract.toLatentEffect(): LatentNetworkEffect =
    LatentNetworkEffect(network = toNetworkEffect())

/**
 * Null-aware conservative join for optional latent effects.
 *
 * @return the non-null operand, their join, or `null` when both are absent.
 */
internal fun LatentNetworkEffect?.join(other: LatentNetworkEffect?): LatentNetworkEffect? = when {
    this == null -> other
    other == null -> this
    else -> join(other)
}
