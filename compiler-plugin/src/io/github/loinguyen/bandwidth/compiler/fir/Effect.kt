package io.github.loinguyen.bandwidth.compiler.fir

import io.github.loinguyen.bandwidth.core.DownloadLifetime
import io.github.loinguyen.bandwidth.core.NetworkEffect
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol

/** A function-scoped effect variable, identified independently of its source name. */
internal data class EffectVariableId(
    val owner: FirFunctionSymbol<*>,
    val index: Int,
    val name: String,
)

/** Explicit substitution used when a polymorphic function is instantiated. */
internal data class EffectSubstitution(
    val bindings: Map<EffectVariableId, Effect>,
) {
    companion object {
        val EMPTY = EffectSubstitution(emptyMap())
    }
}

/**
 * Unified concrete and symbolic bandwidth-effect algebra.
 *
 * Smart constructors evaluate operations whose operands are concrete and keep
 * an expression node only while at least one effect variable remains.
 */
internal sealed interface Effect {
    data object Empty : Effect

    data class Concrete(val value: NetworkEffect) : Effect

    data class Variable(val id: EffectVariableId) : Effect

    data class Sequential(val parts: List<Effect>) : Effect

    data class Parallel(val parts: List<Effect>) : Effect

    data class Choice(val parts: List<Effect>) : Effect

    data class WithSelfBound(
        val effect: Effect,
        val bound: Int,
    ) : Effect

    data class WithInvocationBound(
        val effect: Effect,
        val bound: Int,
    ) : Effect

    data class WithLifetime(
        val effect: Effect,
        val lifetime: DownloadLifetime,
    ) : Effect

    data class CompletingOnly(val effect: Effect) : Effect

    data class EscapingOnly(val effect: Effect) : Effect

    data class BoundedReplication(
        val effect: Effect,
        val copies: Int,
    ) : Effect

    data class Repetition(
        val effect: Effect,
        val missingBoundMessage: String,
    ) : Effect

    data class Invalid(val message: String) : Effect
}

/** Lifts a normalized concrete value into the unified algebra. */
internal fun NetworkEffect.asEffect(): Effect =
    if (this == NetworkEffect.EMPTY) Effect.Empty else Effect.Concrete(this)

/** Returns the concrete value when this effect is fully evaluated. */
internal fun Effect.concreteOrNull(): NetworkEffect? = when (this) {
    Effect.Empty -> NetworkEffect.EMPTY
    is Effect.Concrete -> value
    else -> null
}

/** Sequential composition with eager constant folding. */
internal fun Effect.then(other: Effect): Effect {
    if (this == Effect.Empty) return other
    if (other == Effect.Empty) return this
    val leftConcrete = concreteOrNull()
    val rightConcrete = other.concreteOrNull()
    if (leftConcrete != null && rightConcrete != null) {
        return leftConcrete.then(rightConcrete).asEffect()
    }
    val parts = buildList {
        if (this@then is Effect.Sequential) addAll(this@then.parts) else add(this@then)
        if (other is Effect.Sequential) addAll(other.parts) else add(other)
    }
    return Effect.Sequential(parts)
}

/** Parallel composition with eager constant folding. */
internal fun Effect.parallel(other: Effect): Effect {
    if (this == Effect.Empty) return other
    if (other == Effect.Empty) return this
    val leftConcrete = concreteOrNull()
    val rightConcrete = other.concreteOrNull()
    if (leftConcrete != null && rightConcrete != null) {
        return leftConcrete.parallel(rightConcrete).asEffect()
    }
    val parts = buildList {
        if (this@parallel is Effect.Parallel) addAll(this@parallel.parts) else add(this@parallel)
        if (other is Effect.Parallel) addAll(other.parts) else add(other)
    }
    return Effect.Parallel(parts)
}

/** Alternative-path join with eager constant folding. */
internal fun Effect.choice(other: Effect): Effect {
    if (this == Effect.Empty) return other
    if (other == Effect.Empty) return this
    if (this == other) return this
    val leftConcrete = concreteOrNull()
    val rightConcrete = other.concreteOrNull()
    if (leftConcrete != null && rightConcrete != null) {
        return leftConcrete.choice(rightConcrete).asEffect()
    }
    val parts = buildList {
        if (this@choice is Effect.Choice) addAll(this@choice.parts) else add(this@choice)
        if (other is Effect.Choice) addAll(other.parts) else add(other)
    }.distinct()
    return Effect.Choice(parts)
}

internal fun Effect.withSelfBound(bound: Int): Effect =
    concreteOrNull()?.withSelfBound(bound)?.asEffect()
        ?: Effect.WithSelfBound(this, bound)

internal fun Effect.withConcurrentInvocationBound(bound: Int): Effect =
    concreteOrNull()?.withConcurrentInvocationBound(bound)?.asEffect()
        ?: Effect.WithInvocationBound(this, bound)

internal fun Effect.withLifetime(lifetime: DownloadLifetime): Effect =
    concreteOrNull()?.withLifetime(lifetime)?.asEffect()
        ?: Effect.WithLifetime(this, lifetime)

internal fun Effect.completingOnly(): Effect =
    concreteOrNull()?.completingOnly()?.asEffect()
        ?: Effect.CompletingOnly(this)

internal fun Effect.escapingOnly(): Effect =
    concreteOrNull()?.escapingOnly()?.asEffect()
        ?: Effect.EscapingOnly(this)

internal fun Effect.boundedReplication(copies: Int): Effect =
    concreteOrNull()?.boundedReplication(copies)?.asEffect()
        ?: Effect.BoundedReplication(this, copies)

/** Unknown repetition, deferred until symbolic callback effects are substituted. */
internal fun Effect.repeat(missingBoundMessage: String): Effect {
    val concrete = concreteOrNull()
    return when {
        concrete == null -> Effect.Repetition(this, missingBoundMessage)
        concrete.canRepeat -> concrete.repeat().asEffect()
        else -> Effect.Invalid(missingBoundMessage)
    }
}

/** Capture-avoiding substitution followed by partial evaluation. */
internal fun Effect.substitute(substitution: EffectSubstitution): Effect = when (this) {
    Effect.Empty, is Effect.Concrete, is Effect.Invalid -> this
    is Effect.Variable -> substitution.bindings[id] ?: this
    is Effect.Sequential -> parts.map { it.substitute(substitution) }
        .fold(Effect.Empty as Effect) { result, part -> result.then(part) }
    is Effect.Parallel -> parts.map { it.substitute(substitution) }
        .fold(Effect.Empty as Effect) { result, part -> result.parallel(part) }
    is Effect.Choice -> parts.map { it.substitute(substitution) }
        .fold(Effect.Empty as Effect) { result, part -> result.choice(part) }
    is Effect.WithSelfBound -> effect.substitute(substitution).withSelfBound(bound)
    is Effect.WithInvocationBound ->
        effect.substitute(substitution).withConcurrentInvocationBound(bound)
    is Effect.WithLifetime -> effect.substitute(substitution).withLifetime(lifetime)
    is Effect.CompletingOnly -> effect.substitute(substitution).completingOnly()
    is Effect.EscapingOnly -> effect.substitute(substitution).escapingOnly()
    is Effect.BoundedReplication -> effect.substitute(substitution).boundedReplication(copies)
    is Effect.Repetition -> effect.substitute(substitution).repeat(missingBoundMessage)
}

/** Free variables remaining after the current instantiation. */
internal fun Effect.freeVariables(): Set<EffectVariableId> = when (this) {
    Effect.Empty, is Effect.Concrete, is Effect.Invalid -> emptySet()
    is Effect.Variable -> setOf(id)
    is Effect.Sequential -> parts.flatMapTo(linkedSetOf()) { it.freeVariables() }
    is Effect.Parallel -> parts.flatMapTo(linkedSetOf()) { it.freeVariables() }
    is Effect.Choice -> parts.flatMapTo(linkedSetOf()) { it.freeVariables() }
    is Effect.WithSelfBound -> effect.freeVariables()
    is Effect.WithInvocationBound -> effect.freeVariables()
    is Effect.WithLifetime -> effect.freeVariables()
    is Effect.CompletingOnly -> effect.freeVariables()
    is Effect.EscapingOnly -> effect.freeVariables()
    is Effect.BoundedReplication -> effect.freeVariables()
    is Effect.Repetition -> effect.freeVariables()
}

/** First deferred semantic error in this expression, if one exists. */
internal fun Effect.invalidMessage(): String? = when (this) {
    is Effect.Invalid -> message
    is Effect.Sequential -> parts.firstNotNullOfOrNull { it.invalidMessage() }
    is Effect.Parallel -> parts.firstNotNullOfOrNull { it.invalidMessage() }
    is Effect.Choice -> parts.firstNotNullOfOrNull { it.invalidMessage() }
    is Effect.WithSelfBound -> effect.invalidMessage()
    is Effect.WithInvocationBound -> effect.invalidMessage()
    is Effect.WithLifetime -> effect.invalidMessage()
    is Effect.CompletingOnly -> effect.invalidMessage()
    is Effect.EscapingOnly -> effect.invalidMessage()
    is Effect.BoundedReplication -> effect.invalidMessage()
    is Effect.Repetition -> effect.invalidMessage()
    Effect.Empty, is Effect.Concrete, is Effect.Variable -> null
}

/** Compact rendering for diagnostics and tests involving symbolic summaries. */
internal fun Effect.render(): String = when (this) {
    Effect.Empty -> "{}"
    is Effect.Concrete -> value.render()
    is Effect.Variable -> id.name
    is Effect.Sequential -> parts.joinToString(" ; ", "seq(", ")") { it.render() }
    is Effect.Parallel -> parts.joinToString(" || ", "par(", ")") { it.render() }
    is Effect.Choice -> parts.joinToString(" | ", "choice(", ")") { it.render() }
    is Effect.WithSelfBound -> "selfBound[$bound](${effect.render()})"
    is Effect.WithInvocationBound -> "invocationBound[$bound](${effect.render()})"
    is Effect.WithLifetime -> "lifetime[$lifetime](${effect.render()})"
    is Effect.CompletingOnly -> "completing(${effect.render()})"
    is Effect.EscapingOnly -> "escaping(${effect.render()})"
    is Effect.BoundedReplication -> "replicate[$copies](${effect.render()})"
    is Effect.Repetition -> "repeat(*)(${effect.render()})"
    is Effect.Invalid -> "invalid($message)"
}
