package io.github.loinguyen.bandwidth.compiler.ir

import io.github.loinguyen.bandwidth.core.NetworkEffect
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration

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
 * Result of visiting one Kotlin expression or statement.
 */
internal data class KotlinExpressionEffect(
    val immediate: NetworkEffect = NetworkEffect.EMPTY,
    val latent: LatentNetworkEffect? = null,
)

/**
 * Interprocedural summary of calling a Kotlin function.
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
 * Lexical environment for latent values and returned callbacks.
 *
 * Bindings are joined rather than overwritten, conservatively merging values
 * assigned along different control-flow paths. The binding map is shared with
 * nested-function contexts so captured local callbacks retain their effects.
 */
internal class KotlinEffectContext(
    val function: IrFunction,
    private val latentValues: MutableMap<IrValueDeclaration, LatentNetworkEffect>,
) {
    var returnedLatent: LatentNetworkEffect? = null
        private set

    fun bind(
        declaration: IrValueDeclaration,
        latent: LatentNetworkEffect?,
    ) {
        if (latent == null) return
        latentValues[declaration] = latentValues[declaration]?.join(latent) ?: latent
    }

    fun latentOf(declaration: IrValueDeclaration): LatentNetworkEffect? =
        latentValues[declaration]
            ?: declaration.effectContract()?.toLatentEffect()
            ?: declaration.type.effectContract()?.toLatentEffect()

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
