package io.github.loinguyen.bandwidth.compiler.fir

import io.github.loinguyen.bandwidth.core.NetworkEffect

/**
 * Accumulates sequential phases inside one structured coroutine scope.
 *
 * The handle type stays generic so FIR recognition remains in the visitor.
 */
internal class CoroutinePhaseState<Handle : Any> {
    private var result: NetworkEffect = NetworkEffect.EMPTY
    private var lastLatent: LatentNetworkEffect? = null
    private val activeChildren = linkedMapOf<Handle, NetworkEffect>()
    private val untrackedChildren = mutableListOf<NetworkEffect>()

    fun addChild(
        handle: Handle?,
        effect: NetworkEffect,
    ) {
        if (handle == null) {
            untrackedChildren += effect
        } else {
            activeChildren[handle] = effect
        }
        lastLatent = null
    }

    fun synchronize(handle: Handle): Boolean {
        if (handle !in activeChildren) {
            return false
        }
        recordPhase()
        activeChildren.remove(handle)
        lastLatent = null
        return true
    }

    fun recordStatement(effect: KotlinExpressionEffect) {
        recordPhase(effect.immediate)
        lastLatent = effect.latent
    }

    fun finish(): KotlinExpressionEffect {
        recordPhase()
        return KotlinExpressionEffect(
            immediate = result,
            latent = lastLatent,
        )
    }

    private fun recordPhase(parent: NetworkEffect = NetworkEffect.EMPTY) {
        result = result.then(activeEffect().parallel(parent))
    }

    private fun activeEffect(): NetworkEffect =
        (activeChildren.values + untrackedChildren)
            .fold(NetworkEffect.EMPTY) { effect, child ->
                effect.parallel(child)
            }
}
