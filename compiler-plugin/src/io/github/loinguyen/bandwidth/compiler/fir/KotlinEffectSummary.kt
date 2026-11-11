package io.github.loinguyen.bandwidth.compiler.fir

import io.github.loinguyen.bandwidth.core.AnalysisMode
import org.jetbrains.kotlin.fir.FirAnnotationContainer
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirBackingField
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.FirReceiverParameter
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.expressions.FirCheckedSafeCallSubject
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import java.util.IdentityHashMap

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

/**
 * A Flow value exists, but its collection effect is unavailable. Keeping this
 * explicit makes unknown alternatives, assignments and returns survive joins;
 * null latent metadata remains available for ordinary non-latent expressions.
 */
internal fun untrackedFlowEffect(): LatentNetworkEffect =
    LatentNetworkEffect(
        network = Effect.Invalid("Cannot infer collection effect of an untracked Flow."),
    )

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
    private val analysisMode: AnalysisMode,
) {
    /** Values whose eager work was already recorded by the current phase walk. */
    val evaluatedExpressions = IdentityHashMap<FirElement, KotlinExpressionEffect>()

    /** A safe-call subject reuses its receiver value without evaluating it again. */
    val safeCallSubjects = IdentityHashMap<FirCheckedSafeCallSubject, LatentNetworkEffect?>()

    var returnedLatent: LatentNetworkEffect? = null
        private set

    /**
     * Associates [latent] with [symbol], joining it with any prior branch value.
     *
     * Null symbols and expressions without latent values are ignored.
     */
    fun bind(symbol: FirBasedSymbol<*>?, latent: LatentNetworkEffect?) {
        if (symbol == null || latent == null) return
        // A property symbol identifies a declaration, not one object instance.
        // Inferred constructor/initializer values must never be merged into a
        // global summary used by all instances of that declaration.
        if (symbol.flowStorageProperty()?.isContractedFlowStorage(session) == true) return
        latentValues[symbol] = latentValues[symbol]?.join(latent) ?: latent
    }

    /**
     * Resolves the latent effect of [symbol] from lexical bindings or an
     * annotation on the declaration or its return type.
     */
    fun latentOf(symbol: FirBasedSymbol<*>?): LatentNetworkEffect? {
        symbol ?: return null
        symbol.flowStorageProperty()?.let { property ->
            if (property.isContractedFlowStorage(session)) {
                return property.flowStorageEffect(session, analysisMode)
            }
        }
        // A synthetic setter value parameter does not consistently retain
        // the property's type-use annotation across compiler versions. Its
        // accepted value is nevertheless exactly the property's invariant.
        (symbol.fir as? FirValueParameter)?.let { parameter ->
            val accessor = parameter.containingDeclarationSymbol.fir as? FirPropertyAccessor
            if (accessor?.isSetter == true &&
                parameter.returnTypeRef.coneType.isBandwidthFlow(session)
            ) {
                return accessor.propertySymbol.fir.flowStorageEffect(session, analysisMode)
            }
        }
        latentValues[symbol]?.let { return it }
        val declaration = symbol.fir
        if (declaration is FirReceiverParameter) {
            val contract = declaration.effectContract(session, analysisMode)
                ?: declaration.typeRef.effectContract(session, analysisMode)
            if (contract != null && contract.variable == null) return contract.toLatentEffect()
            if (contract?.variable != null) {
                return LatentNetworkEffect(
                    network = Effect.Invalid(
                        "Cannot resolve bandwidth effect variable ${contract.variable} " +
                            "on the extension receiver.",
                    ),
                )
            }
            if (declaration.typeRef.coneType.isBandwidthLatentValue(session)) {
                return LatentNetworkEffect()
            }
        }
        val direct = (declaration as? FirAnnotationContainer)
            ?.effectContract(session, analysisMode)
            ?.takeIf { it.variable == null }
            ?.toLatentEffect()
        if (direct != null) return direct
        val typed = (declaration as? FirCallableDeclaration)
            ?.returnTypeRef
            ?.effectContract(session, analysisMode)
            ?.takeIf { it.variable == null }
            ?.toLatentEffect()
        if (typed != null) return typed
        if (
            declaration is FirValueParameter &&
            declaration.returnTypeRef.coneType.isBandwidthLatentValue(session)
        ) {
            return LatentNetworkEffect()
        }
        return null
    }

    /** Joins a latent-valued return into this function's returned effect. */
    fun recordReturn(latent: LatentNetworkEffect?) {
        if (latent == null) return
        returnedLatent = returnedLatent?.join(latent) ?: latent
    }
}

/** Resolves ordinary and backing-field access to the same storage declaration. */
internal fun FirBasedSymbol<*>.flowStorageProperty(): FirProperty? = when (val declaration = fir) {
    is FirProperty -> declaration
    is FirBackingField -> declaration.propertySymbol.fir
    else -> null
}

/**
 * A local/anonymous class member is still storage shared by its instances.
 * Kotlin 2.4 can mark such members isLocal; a dispatch receiver distinguishes
 * them from lexical local variables whose values are tracked in the context.
 */
internal fun FirProperty.isLexicalLocalVariable(): Boolean =
    symbol.isLocal && dispatchReceiverType == null

/** Nonlocal Flow storage has one checked contract shared by all object instances. */
internal fun FirProperty.isContractedFlowStorage(session: FirSession): Boolean =
    !isLexicalLocalVariable() && returnTypeRef.coneType.isBandwidthFlow(session)

/**
 * Reads the declared storage invariant. A missing annotation means empty;
 * symbolic contracts require class-level effect polymorphism, which is not
 * currently supported and must not silently become an empty contract.
 */
internal fun FirProperty.flowStorageEffect(
    session: FirSession,
    analysisMode: AnalysisMode,
): LatentNetworkEffect {
    val contract = returnTypeRef.effectContract(session, analysisMode) ?: effectContract(session, analysisMode)
    if (contract?.variable != null) {
        return LatentNetworkEffect(
            network = Effect.Invalid(
                "Flow storage contract for ${symbol.callableId} must be concrete; " +
                    "class-level bandwidth effect variables are not supported.",
            ),
        )
    }
    return contract?.toLatentEffect() ?: LatentNetworkEffect()
}

/** Validates a write or getter result against this property's storage invariant. */
internal fun FirProperty.flowStorageProblem(
    actual: LatentNetworkEffect?,
    session: FirSession,
    analysisMode: AnalysisMode,
): String? {
    val declaredEffect = flowStorageEffect(session, analysisMode).network
    declaredEffect.invalidMessage()?.let { return it }
    val declared = declaredEffect.concreteOrNull() ?: return (
        "Cannot verify Flow storage contract for ${symbol.callableId}: " +
            "the declared effect is not concrete."
        )
    val inferred = actual?.network?.concreteOrNull() ?: return (
        "Cannot verify Flow storage contract for ${symbol.callableId}: " +
            "the stored Flow effect is unknown or not concrete" +
            (actual?.network?.invalidMessage()?.let { ": $it" } ?: ".")
        )
    return if (!inferred.isCoveredBy(declared)) {
        "Inferred stored Flow effect ${inferred.render()} is not covered by " +
            "Flow storage contract ${declared.render()} for ${symbol.callableId}."
    } else {
        null
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
