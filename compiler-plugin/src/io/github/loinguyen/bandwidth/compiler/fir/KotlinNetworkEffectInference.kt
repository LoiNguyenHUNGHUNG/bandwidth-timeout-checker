package io.github.loinguyen.bandwidth.compiler.fir

import io.github.loinguyen.bandwidth.core.NetworkEffect
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol

/**
 * Couples Kotlin's resolved FIR types with inferred quantitative effects.
 *
 * Kotlin remains responsible for ordinary typing and call resolution. This
 * pass computes the effect component of `Gamma |- e : tau |> Phi`, caches it
 * by resolved FIR symbol, and emits frontend diagnostics.
 */
internal class KotlinNetworkEffectInference(
    private val session: FirSession,
    private val messages: MessageCollector,
    private val reportEffects: Boolean,
) {
    private val inferredEffects:
        MutableMap<FirFunctionSymbol<*>, KotlinFunctionEffect> = mutableMapOf()
    private val functionsBeingInferred: MutableSet<FirFunctionSymbol<*>> = mutableSetOf()
    private val analyzedFunctions: MutableSet<FirFunctionSymbol<*>> = mutableSetOf()
    private val reportedProblems: MutableSet<String> = mutableSetOf()
    private val latentValues = mutableMapOf<FirBasedSymbol<*>, LatentNetworkEffect>()
    private var diagnosticContext: CheckerContext? = null
    private var diagnosticReporter: DiagnosticReporter? = null
    private val effectVisitor = KotlinNetworkEffectVisitor(
        session = session,
        inferFunction = ::inferFunctionEffect,
        reportProblem = ::problem,
    )

    fun analyze(
        function: FirFunction,
        context: CheckerContext,
        reporter: DiagnosticReporter,
    ) {
        if (!analyzedFunctions.add(function.symbol)) return
        diagnosticContext = context
        diagnosticReporter = reporter
        try {
            val inferred: KotlinFunctionEffect = inferFunctionEffect(function)
            val inferredEffect: NetworkEffect = inferred.invocation
            if (inferredEffect.hasUnresolvedConcurrency) {
                error(
                    function.source,
                    "Cannot establish a finite network concurrency bound for " +
                        "${function.displayName()}. Use a recognized structured " +
                        "coroutine construct, or an annotated client inside " +
                        "an unknown forEach repetition.",
                )
            }
            function.effectContract(session)?.let { contract ->
                val declaredEffect: NetworkEffect = contract.toNetworkEffect()
                if (!inferredEffect.isCoveredBy(declaredEffect)) {
                    error(
                        function.source,
                        "Inferred effect ${inferredEffect.render()} is not covered by " +
                            "@BandwidthEffect(rMaxBytesPerSecond=" +
                            "${contract.rMaxBytesPerSecond}, nMax=${contract.nMax}).",
                    )
                }
            }
            function.overriddenEffectContracts(session, context).forEach { contract ->
                val declaredEffect: NetworkEffect = contract.toNetworkEffect()
                if (!inferredEffect.isCoveredBy(declaredEffect)) {
                    error(
                        function.source,
                        "Inferred override effect ${inferredEffect.render()} is not covered by " +
                            "the overridden @BandwidthEffect(rMaxBytesPerSecond=" +
                            "${contract.rMaxBytesPerSecond}, nMax=${contract.nMax}).",
                    )
                }
            }
            function.returnTypeRef.effectContract(session)?.let { contract ->
                val inferredLatent: NetworkEffect =
                    inferred.returned?.invocation ?: NetworkEffect.EMPTY
                val declaredLatent: NetworkEffect = contract.toNetworkEffect()
                if (!inferredLatent.isCoveredBy(declaredLatent)) {
                    error(
                        function.source,
                        "Inferred returned latent effect ${inferredLatent.render()} is not " +
                            "covered by @BandwidthEffect(" +
                            "rMaxBytesPerSecond=${contract.rMaxBytesPerSecond}, " +
                            "nMax=${contract.nMax}) on the return type.",
                    )
                }
            }
            if (reportEffects && inferredEffect != NetworkEffect.EMPTY &&
                !inferredEffect.hasUnresolvedConcurrency
            ) {
                messages.report(
                    CompilerMessageSeverity.INFO,
                    "Inferred bandwidth effect for ${function.displayName()}: " +
                        "${inferredEffect.render()}, " +
                        "ReqBW=${inferredEffect.requiredBandwidthBytesPerSecond()} bytes/s.",
                    CompilerMessageLocation.create(
                        context.bandwidthSourcePath(),
                    ),
                )
            }
        } finally {
            diagnosticContext = null
            diagnosticReporter = null
        }
    }

    private fun inferFunctionEffect(function: FirFunction): KotlinFunctionEffect {
        function.downloadContract(session)?.let { contract ->
            return KotlinFunctionEffect(
                invocation = NetworkEffect.download(
                    maxBytes = contract.maxBytes,
                    completeTimeoutMillis = contract.completeTimeoutMillis,
                ),
            )
        }
        inferredEffects[function.symbol]?.let { return it }
        if (!functionsBeingInferred.add(function.symbol)) {
            problem(
                key = "recursion:${function.displayName()}",
                source = function.source,
                message = "Cannot infer recursive network function ${function.displayName()}. " +
                    "Add @BandwidthEffect(rMaxBytesPerSecond, nMax) as a recursion boundary.",
            )
            return KotlinFunctionEffect()
        }

        val result: KotlinFunctionEffect =
            effectVisitor.inferFunctionBody(function, latentValues)
        functionsBeingInferred.remove(function.symbol)
        inferredEffects[function.symbol] = result
        return result
    }

    private fun problem(
        key: String,
        source: KtSourceElement?,
        message: String,
    ) {
        if (reportedProblems.add(key)) {
            error(source, message)
        }
    }

    private fun error(source: KtSourceElement?, message: String) {
        val context = diagnosticContext ?: return
        val reporter = diagnosticReporter ?: return
        reporter.reportOn(
            source,
            BandwidthDiagnostics.ERROR,
            message,
            context,
        )
    }
}

internal fun EffectContract.toNetworkEffect(): NetworkEffect =
    NetworkEffect.summary(rMaxBytesPerSecond, nMax)

internal fun FirFunction.displayName(): String =
    symbol.callableId.asSingleFqName().asString()

internal fun NetworkEffect.render(): String =
    obligations.joinToString(prefix = "{", postfix = "}") {
        "(${it.requiredRateBytesPerSecond}, ${it.concurrency})"
    }
