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
    private val applicationEntryPoints: ApplicationEntryPointEffects,
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

    /**
     * Infers and validates [function] once for the current compilation.
     *
     * The inferred eager and returned latent effects are checked against direct,
     * overridden, and return-type contracts. Optional informational reporting
     * is emitted through the compiler message collector.
     */
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
            val quantified = function.effectVariableIds(session).toSet()
            val free = buildSet {
                addAll(inferred.network.freeVariables())
                inferred.returned?.network?.freeVariables()?.let(::addAll)
            }
            val unexpected = free - quantified
            if (unexpected.isNotEmpty()) {
                error(
                    function.source,
                    "Inferred effect contains variables outside this function's " +
                        "@BandwidthVariable scope: " +
                        unexpected.joinToString { it.name },
                )
            }
            inferred.network.invalidMessage()?.let { error(function.source, it) }

            val inferredEffect = inferred.network.concreteOrNull()
            if (quantified.isEmpty() && inferredEffect == null) {
                error(
                    function.source,
                    "Inferred effect ${inferred.network.render()} is not concrete at a " +
                        "non-polymorphic function boundary.",
                )
            }
            if (inferredEffect != null) {
                function.effectContract(session)?.let { contract ->
                    val declaredEffect: NetworkEffect = contract.network
                    if (!inferredEffect.isCoveredBy(declaredEffect)) {
                        error(
                            function.source,
                            "Inferred effect ${inferredEffect.render()} is not covered by " +
                                "@BandwidthEffect contract ${declaredEffect.render()}.",
                        )
                    }
                }
                function.overriddenEffectContracts(session, context).forEach { contract ->
                    val declaredEffect: NetworkEffect = contract.network
                    if (!inferredEffect.isCoveredBy(declaredEffect)) {
                        error(
                            function.source,
                            "Inferred override effect ${inferredEffect.render()} is not " +
                                "covered by the overridden @BandwidthEffect contract " +
                                "${declaredEffect.render()}.",
                        )
                    }
                }
                function.returnTypeRef.effectContract(session)?.let { contract ->
                    val inferredLatent = inferred.returned?.network?.concreteOrNull()
                        ?: NetworkEffect.EMPTY
                    val declaredLatent: NetworkEffect = contract.network
                    if (!inferredLatent.isCoveredBy(declaredLatent)) {
                        error(
                            function.source,
                            "Inferred returned latent effect ${inferredLatent.render()} is not " +
                                "covered by @BandwidthEffect contract " +
                                "${declaredLatent.render()} on the return type.",
                        )
                    }
                }
                if (function.isEntryPoint(session)) applicationEntryPoints.record(inferredEffect)
            } else if (function.isEntryPoint(session)) {
                error(function.source, "An @EntryPoint cannot have polymorphic bandwidth effects.")
            }
            if (reportEffects && inferredEffect != null && inferredEffect != NetworkEffect.EMPTY) {
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

    /**
     * Returns a cached or newly inferred summary for [function], using primitive
     * contracts and annotated recursion boundaries when available.
     */
    private fun inferFunctionEffect(function: FirFunction): KotlinFunctionEffect {
        function.downloadContract(session)?.let { contract ->
            return KotlinFunctionEffect(
                network = NetworkEffect.download(
                    maxBytes = contract.maxBytes,
                    completeTimeoutMillis = contract.completeTimeoutMillis,
                ).asEffect(),
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

    /** Reports a keyed inference [message] at most once. */
    private fun problem(
        key: String,
        source: KtSourceElement?,
        message: String,
    ) {
        if (reportedProblems.add(key)) {
            error(source, message)
        }
    }

    /** Emits an FIR error at [source] while an analysis context is active. */
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

/** Returns this function's fully qualified callable name for diagnostics. */
internal fun FirFunction.displayName(): String =
    symbol.callableId.asSingleFqName().asString()

/** Renders rate/concurrency obligations in a compact diagnostic format. */
internal fun NetworkEffect.render(): String =
    obligations.joinToString(prefix = "{", postfix = "}") {
        "(${it.requiredRateBytesPerSecond}, ${it.concurrency})"
    }
