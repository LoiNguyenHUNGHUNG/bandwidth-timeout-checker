package io.github.loinguyen.bandwidth.compiler.fir

/** Accumulates sequential phases inside one structured coroutine scope. */
internal class CoroutinePhaseState<Handle : Any> {
    private var result: KotlinExpressionEffect = KotlinExpressionEffect()
    private var escaping: Effect = Effect.Empty
    private var lastLatent: LatentNetworkEffect? = null
    private val activeChildren = linkedMapOf<Handle, KotlinExpressionEffect>()
    private val untrackedChildren = mutableListOf<KotlinExpressionEffect>()

    /**
     * Starts a structured [effect] in the current phase.
     *
     * Escaping work is accumulated separately; completing work remains active
     * until [handle] is synchronized or the scope finishes. A null handle is
     * conservatively kept active through the rest of the scope.
     */
    fun addChild(handle: Handle?, effect: KotlinExpressionEffect) {
        escaping = escaping.parallel(effect.network.escapingOnly())
        val structuredPart = effect.copy(network = effect.network.completingOnly())
        if (handle == null) untrackedChildren += structuredPart else activeChildren[handle] = structuredPart
        lastLatent = null
    }

    /**
     * Ends the active child identified by [handle] after recording its current
     * overlap window.
     *
     * @return `true` when the handle identified an active child.
     */
    fun synchronize(handle: Handle): Boolean {
        if (handle !in activeChildren) return false
        recordPhase()
        activeChildren.remove(handle)
        lastLatent = null
        return true
    }

    /** Records a parent [effect] that runs while all active children overlap it. */
    fun recordStatement(effect: KotlinExpressionEffect) {
        recordPhase(effect)
        lastLatent = effect.latent
    }

    /**
     * Closes the final phase and appends all work that may escape the structured
     * scope.
     */
    fun finish(): KotlinExpressionEffect {
        recordPhase()
        return KotlinExpressionEffect(
            network = result.network.then(escaping),
            latent = lastLatent,
        )
    }

    /** Sequentially appends one phase containing active children and [parent] work. */
    private fun recordPhase(parent: KotlinExpressionEffect = KotlinExpressionEffect()) {
        result = result.then(activeEffect().parallel(parent))
    }

    /** Returns the parallel composition of every currently active child. */
    private fun activeEffect(): KotlinExpressionEffect =
        (activeChildren.values + untrackedChildren)
            .fold(KotlinExpressionEffect()) { effect, child -> effect.parallel(child) }
}
