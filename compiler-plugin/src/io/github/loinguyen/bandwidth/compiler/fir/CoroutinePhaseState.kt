package io.github.loinguyen.bandwidth.compiler.fir

/**
 * Accumulates sequential phases inside one structured coroutine scope.
 *
 * The handle type stays generic so FIR recognition remains in the visitor.
 */
internal class CoroutinePhaseState<Handle : Any> {
    private var result: KotlinExpressionEffect = KotlinExpressionEffect()
    private var lastLatent: LatentNetworkEffect? = null
    private val activeChildren = linkedMapOf<Handle, KotlinExpressionEffect>()
    private val untrackedChildren = mutableListOf<KotlinExpressionEffect>()

    fun addChild(
        handle: Handle?,
        effect: KotlinExpressionEffect,
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
        recordPhase(effect)
        lastLatent = effect.latent
    }

    fun finish(): KotlinExpressionEffect {
        recordPhase()
        return KotlinExpressionEffect(
            immediate = result.immediate,
            latent = lastLatent,
        )
    }

    private fun recordPhase(parent: KotlinExpressionEffect = KotlinExpressionEffect()) {
        result = result.then(activeEffect().parallel(parent))
    }

    private fun activeEffect(): KotlinExpressionEffect =
        (activeChildren.values + untrackedChildren)
            .fold(KotlinExpressionEffect()) { effect, child ->
                effect.parallel(child)
            }
}
