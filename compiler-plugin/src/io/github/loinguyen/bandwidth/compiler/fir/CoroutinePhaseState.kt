package io.github.loinguyen.bandwidth.compiler.fir

import io.github.loinguyen.bandwidth.core.NetworkEffect

/** Accumulates sequential phases inside one structured coroutine scope. */
internal class CoroutinePhaseState<Handle : Any> {
    private var result: KotlinExpressionEffect = KotlinExpressionEffect()
    private var escaping: NetworkEffect = NetworkEffect.EMPTY
    private var lastLatent: LatentNetworkEffect? = null
    private val activeChildren = linkedMapOf<Handle, KotlinExpressionEffect>()
    private val untrackedChildren = mutableListOf<KotlinExpressionEffect>()

    fun addChild(handle: Handle?, effect: KotlinExpressionEffect) {
        escaping = escaping.parallel(effect.network.escapingOnly())
        val structuredPart = effect.copy(network = effect.network.completingOnly())
        if (handle == null) untrackedChildren += structuredPart else activeChildren[handle] = structuredPart
        lastLatent = null
    }

    fun synchronize(handle: Handle): Boolean {
        if (handle !in activeChildren) return false
        recordPhase()
        activeChildren.remove(handle)
        lastLatent = null
        return true
    }

    fun recordStatement(effect: KotlinExpressionEffect) {
        recordPhase(effect)
        lastLatent = effect.latent
    }

    fun finish(): KotlinExpressionEffect {
        recordPhase()
        return KotlinExpressionEffect(
            network = result.network.then(escaping),
            latent = lastLatent,
        )
    }

    private fun recordPhase(parent: KotlinExpressionEffect = KotlinExpressionEffect()) {
        result = result.then(activeEffect().parallel(parent))
    }

    private fun activeEffect(): KotlinExpressionEffect =
        (activeChildren.values + untrackedChildren)
            .fold(KotlinExpressionEffect()) { effect, child -> effect.parallel(child) }
}
