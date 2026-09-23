package io.github.loinguyen.bandwidth.compiler.fir

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
    val network: Effect = Effect.Empty,
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

    /** Applies [substitution] recursively to this latent value. */
    fun substitute(substitution: EffectSubstitution): LatentNetworkEffect =
        LatentNetworkEffect(
            network = network.substitute(substitution),
            returned = returned?.substitute(substitution),
        )
}

/** The effect component paired with Kotlin's already resolved type. */
internal data class KotlinExpressionEffect(
    val network: Effect = Effect.Empty,
    val latent: LatentNetworkEffect? = null,
) {
    /** Applies [substitution] to eager and latent effects. */
    fun substitute(substitution: EffectSubstitution): KotlinExpressionEffect =
        KotlinExpressionEffect(
            network = network.substitute(substitution),
            latent = latent?.substitute(substitution),
        )
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
    val network: Effect = Effect.Empty,
    val returned: LatentNetworkEffect? = null,
) {
    /** Converts this interprocedural summary to a storable latent function effect. */
    fun asLatent(): LatentNetworkEffect =
        LatentNetworkEffect(
            network = network,
            returned = returned,
        )

    /** Instantiates this function summary with [substitution]. */
    fun substitute(substitution: EffectSubstitution): KotlinFunctionEffect =
        KotlinFunctionEffect(
            network = network.substitute(substitution),
            returned = returned?.substitute(substitution),
        )
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
            ?.takeIf { it.variable == null }
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
            ?.takeIf { it.variable == null }
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
    LatentNetworkEffect(network = network.asEffect())

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
