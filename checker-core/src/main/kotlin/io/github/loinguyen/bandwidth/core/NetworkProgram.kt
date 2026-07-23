package io.github.loinguyen.bandwidth.core

/**
 * Language-independent network IR produced by a frontend.
 */
public sealed interface NetworkProgram {
    public data object Pure : NetworkProgram

    public data class Download(
        public val maxBytes: Long,
        public val completeTimeoutMillis: Long,
    ) : NetworkProgram

    public data class OpaqueCall(
        public val declaredEffect: NetworkEffect,
    ) : NetworkProgram

    public data class Sequence(
        public val steps: List<NetworkProgram>,
    ) : NetworkProgram

    public data class Choice(
        public val branches: List<NetworkProgram>,
    ) : NetworkProgram

    public data class Parallel(
        public val branches: List<NetworkProgram>,
    ) : NetworkProgram

    /**
     * A data-dependent number of launches through one scope, with at most
     * [maxConcurrentBodies] launched bodies running at once.
     */
    public data class BoundedScopeLaunch(
        public val scopeId: String,
        public val maxConcurrentBodies: Int,
        public val body: NetworkProgram,
    ) : NetworkProgram
}

/**
 * Computes the quantitative effect of the language-independent IR.
 */
public object NetworkEffectAnalyzer {
    public fun analyze(program: NetworkProgram): NetworkEffect = when (program) {
        NetworkProgram.Pure -> NetworkEffect.EMPTY
        is NetworkProgram.Download -> NetworkEffect.download(
            maxBytes = program.maxBytes,
            completeTimeoutMillis = program.completeTimeoutMillis,
        )
        is NetworkProgram.OpaqueCall -> program.declaredEffect
        is NetworkProgram.Sequence -> program.steps.fold(NetworkEffect.EMPTY) { effect, step ->
            effect.then(analyze(step))
        }
        is NetworkProgram.Choice -> program.branches.fold(NetworkEffect.EMPTY) { effect, branch ->
            effect.then(analyze(branch))
        }
        is NetworkProgram.Parallel -> program.branches.fold(NetworkEffect.EMPTY) { effect, branch ->
            effect.parallel(analyze(branch))
        }
        is NetworkProgram.BoundedScopeLaunch -> {
            require(program.scopeId.isNotBlank()) { "scopeId must not be blank" }
            require(program.maxConcurrentBodies > 0) { "bounded scope must have a positive bound" }
            analyze(program.body).boundedReplication(program.maxConcurrentBodies)
        }
    }
}
