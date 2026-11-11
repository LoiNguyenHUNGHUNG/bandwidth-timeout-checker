package io.github.loinguyen.bandwidth.compiler.fir

import io.github.loinguyen.bandwidth.core.DownloadLifetime
import io.github.loinguyen.bandwidth.core.NetworkEffect
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.processOverriddenPropertiesWithActionSafe
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirAnonymousFunction
import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.resolve.providers.symbolProvider
import org.jetbrains.kotlin.fir.resolve.toClassSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.scopes.ProcessorAction
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.coneTypeOrNull

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
    private val analyzedProperties: MutableSet<FirBasedSymbol<*>> = mutableSetOf()
    private val analyzedInitializers: MutableSet<FirBasedSymbol<*>> = mutableSetOf()
    private val reportedProblems: MutableSet<String> = mutableSetOf()
    private val latentValues = mutableMapOf<FirBasedSymbol<*>, LatentNetworkEffect>()
    private var diagnosticContext: CheckerContext? = null
    private var diagnosticReporter: DiagnosticReporter? = null
    private val effectVisitor = KotlinNetworkEffectVisitor(
        session = session,
        inferFunction = ::inferFunctionEffect,
        reportProblem = ::problem,
        reportInfo = { message ->
            if (reportEffects) messages.report(CompilerMessageSeverity.INFO, message)
        },
    )

    /**
     * Checks the invariant of nonlocal Flow storage independently of reads.
     * Each instance's initializer and custom getter must satisfy the declared
     * contract; their inferred effects never become the property's read type.
     */
    fun analyzeProperty(
        property: FirProperty,
        context: CheckerContext,
        reporter: DiagnosticReporter,
    ) {
        if (property.isLexicalLocalVariable() || !analyzedProperties.add(property.symbol)) return
        val flowStorage = property.isContractedFlowStorage(session)
        // Local/anonymous classes carry fixed FIR symbols in their receiver type.
        // A local ClassId is not a valid key for the global symbol provider.
        val enclosingClass = property.dispatchReceiverType?.toClassSymbol(session)?.fir
            ?: context.containingDeclarations.filterIsInstance<FirClass>().lastOrNull()
            ?: property.symbol.callableId?.classId?.takeUnless { it.isLocal }?.let { classId ->
                session.symbolProvider.getClassLikeSymbolByClassId(classId)?.fir as? FirClass
            }
        if (!flowStorage && enclosingClass?.containsFlowStorage() != true) return
        diagnosticContext = context
        diagnosticReporter = reporter
        try {
            if (!flowStorage) {
                property.initializer?.let { initializer ->
                    val owner = property.getter ?: context.containingDeclarations
                        .filterIsInstance<FirFunction>().lastOrNull()
                    if (owner == null) {
                        error(
                            initializer.source ?: property.source,
                            "Flow wrapper initializer cannot be checked without an initializer context.",
                        )
                    } else {
                        val result = effectVisitor.infer(
                            initializer,
                            KotlinEffectContext(owner, session, latentValues),
                        )
                        if (result.network.concreteOrNull() != NetworkEffect.EMPTY) {
                            error(
                                initializer.source ?: property.source,
                                "Flow wrapper initializer performs eager network work that is " +
                                    "not modeled at constructor boundaries. Use an explicit " +
                                    "initialization function.",
                            )
                        }
                    }
                }
                return
            }
            property.flowStorageEffect(session).network.invalidMessage()?.let {
                error(property.source, it)
            }
            val declared = property.flowStorageEffect(session).network.concreteOrNull()
            with(context) {
                property.symbol.processOverriddenPropertiesWithActionSafe { overridden ->
                    val base = overridden.fir
                    if (!base.returnTypeRef.coneType.isBandwidthFlow(session)) {
                        return@processOverriddenPropertiesWithActionSafe ProcessorAction.NEXT
                    }
                    val inherited = base.flowStorageEffect(session).network.concreteOrNull()
                    if (declared != null && inherited != null) {
                        if (!declared.isCoveredBy(inherited)) {
                            error(
                                property.source,
                                "Flow storage contract ${declared.render()} is not covered by " +
                                    "the overridden property contract ${inherited.render()}.",
                            )
                        } else if (!base.isVal && !inherited.isCoveredBy(declared)) {
                            error(
                                property.source,
                                "Mutable Flow storage must preserve the overridden property " +
                                    "contract ${inherited.render()} for assignments through " +
                                    "the base type.",
                            )
                        }
                    } else if (inherited == null) {
                        error(
                            property.source,
                            "Cannot verify an overridden Flow storage contract that is not concrete.",
                        )
                    }
                    ProcessorAction.NEXT
                }
            }
            val virtualAccessor = property.status.isOverride ||
                property.status.modality != Modality.FINAL
            val initializer = property.initializer
            if (initializer != null) {
                val owner = property.getter ?: context.containingDeclarations
                    .filterIsInstance<FirFunction>().lastOrNull()
                if (owner == null) {
                    error(
                        property.source,
                        "Cannot verify Flow storage contract for ${property.symbol.callableId}: " +
                            "no initializer analysis context is available.",
                    )
                } else {
                    val inferred = effectVisitor.infer(
                        initializer,
                        KotlinEffectContext(owner, session, latentValues),
                    )
                    if (inferred.network.concreteOrNull() != NetworkEffect.EMPTY) {
                        error(
                            initializer.source ?: property.source,
                            "Eager network work during Flow property initialization is not " +
                                "modeled at constructor or application-startup boundaries. " +
                                "Use a lazy Flow producer or an explicit initialization function.",
                        )
                    }
                    val actual = inferred.latent ?: if (
                        initializer is FirLiteralExpression && initializer.value == null
                    ) LatentNetworkEffect() else null
                    property.flowStorageProblem(actual, session)?.let {
                        error(initializer.source ?: property.source, it)
                    }
                }
            }
            property.getter?.takeIf { it.body != null }?.let { getter ->
                val result = effectVisitor.inferFunctionBody(getter, latentValues)
                if (virtualAccessor && result.network.concreteOrNull() != NetworkEffect.EMPTY) {
                    error(
                        getter.source ?: property.source,
                        "Eager network work in a virtual Flow accessor (overridable or overriding getter) is " +
                            "not supported: base-typed property access cannot verify the " +
                            "dispatched accessor's eager effect. Use a final property or an " +
                            "explicit function with a checked effect contract.",
                    )
                }
                property.flowStorageProblem(result.returned, session)?.let {
                    error(getter.source ?: property.source, it)
                }
            }
            property.setter?.takeIf { it.body != null }?.let { setter ->
                // KotlinEffectContext reads a setter's value parameter through
                // the property invariant even before this declaration is checked.
                val result = effectVisitor.inferFunctionBody(setter, latentValues)
                if (virtualAccessor && result.network.concreteOrNull() != NetworkEffect.EMPTY) {
                    error(
                        setter.source ?: property.source,
                        "Eager network work in a virtual Flow accessor (overridable or overriding setter) is " +
                            "not supported: base-typed assignment cannot verify the " +
                            "dispatched accessor's eager effect. Use a final property or an " +
                            "explicit function with a checked effect contract.",
                    )
                }
            }
            if (property.delegate != null &&
                (property.bandwidthPureLazyFlowInitializer(session) == null || property.getter == null)
            ) {
                error(
                    property.source,
                    "Cannot verify Flow storage contract for ${property.symbol.callableId}: " +
                        "the delegated getter's Flow effect is unknown. Only a directly visible " +
                        "network-free standard lazy initializer is supported.",
                )
            }
        } finally {
            diagnosticContext = null
            diagnosticReporter = null
        }
    }

    /** Includes inherited Flow storage without imposing the guard on unrelated classes. */
    @OptIn(DirectDeclarationsAccess::class)
    private fun FirClass.containsFlowStorage(
        visited: MutableSet<FirBasedSymbol<*>> = mutableSetOf(),
    ): Boolean {
        if (!visited.add(symbol)) return false
        if (declarations.any { declaration ->
                when (declaration) {
                    is FirProperty -> !declaration.isLexicalLocalVariable() &&
                        declaration.returnTypeRef.coneTypeOrNull?.isBandwidthFlow(session) == true
                    is FirConstructor -> declaration.valueParameters.any {
                        it.returnTypeRef.coneTypeOrNull?.isBandwidthFlow(session) == true
                    }
                    else -> false
                }
            }
        ) return true
        return superTypeRefs.any { supertype ->
            supertype.coneTypeOrNull != null &&
                supertype.coneType.toClassSymbol(session)?.fir?.containsFlowStorage(visited) == true
        }
    }

    /** Checks member assignments inside an `init` block using its constructor scope. */
    @OptIn(DirectDeclarationsAccess::class)
    fun analyzeInitializer(
        initializer: FirAnonymousInitializer,
        context: CheckerContext,
        reporter: DiagnosticReporter,
    ) {
        if (!analyzedInitializers.add(initializer.symbol)) return
        val owner = (initializer.containingDeclarationSymbol.fir as? FirClass)
            ?.declarations?.filterIsInstance<FirConstructor>()?.firstOrNull()
            ?: context.containingDeclarations.filterIsInstance<FirConstructor>().lastOrNull()
            ?: return
        diagnosticContext = context
        diagnosticReporter = reporter
        try {
            val result = effectVisitor.infer(
                initializer.body,
                KotlinEffectContext(owner, session, latentValues),
            )
            if (result.network.concreteOrNull() != NetworkEffect.EMPTY) {
                error(
                    initializer.source,
                    "Eager network work in an init block is not modeled at constructor " +
                        "boundaries. Use an explicit initialization function.",
                )
            }
        } finally {
            diagnosticContext = null
            diagnosticReporter = null
        }
    }

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
        if (function.concurrentMapContract(session) != null) {
            // Explicit trusted library boundary: its dependent concurrency is
            // instantiated at calls, rather than inferred from this implementation.
            if (reportEffects) {
                messages.report(
                    CompilerMessageSeverity.INFO,
                    "Audited @BandwidthConcurrentMap contract for " + function.displayName(),
                    CompilerMessageLocation.create(context.bandwidthSourcePath()),
                )
            }
            return
        }
        diagnosticContext = context
        diagnosticReporter = reporter
        try {
            validateFlowParameterDefaults(function)
            val inferred: KotlinFunctionEffect = inferFunctionEffect(function)
            val returnsFlow = function.returnTypeRef.coneType.isBandwidthFlow(session)
            val overriddenReturnedContracts = function.overriddenReturnedEffectContracts(session, context)
            if (function.body != null && (returnsFlow || overriddenReturnedContracts.isNotEmpty())) {
                // Primitive and symbolic eager contracts bypass body inference. They do
                // not certify the effect suspended inside a returned Flow.
                val returned = if (
                    function.downloadContract(session) != null ||
                    function.symbolicInvocationEffect(session) != null
                ) {
                    effectVisitor.inferFunctionBody(function, latentValues).returned
                } else {
                    inferred.returned
                }
                function.returnTypeRef.effectContract(session)?.let { contract ->
                    validateReturnedFlowContract(function, returned, contract, overridden = false)
                }
                overriddenReturnedContracts.forEach { contract ->
                    validateReturnedFlowContract(function, returned, contract, overridden = true)
                }
            }
            val isDeclarationHandler =
                function !is FirAnonymousFunction && function.isHandler(session)
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
                if (!returnsFlow && overriddenReturnedContracts.isEmpty()) {
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
                }
                when {
                    isDeclarationHandler -> {
                        val handlerBody = function.effectContract(session)
                            ?.takeIf { it.variable == null }
                            ?.network
                            ?: inferredEffect
                        val repeatedBody = handlerBody.withLifetime(
                            DownloadLifetime.MAY_OUTLIVE_CALL,
                        )
                        if (repeatedBody.canRepeat) {
                            val repeated = repeatedBody.repeat()
                            applicationEntryPoints.record(repeated)
                            if (reportEffects) {
                                messages.report(
                                    CompilerMessageSeverity.INFO,
                                    "NAMED_HANDLER_EFFECT function=${function.displayName()} " +
                                        "body=${handlerBody.render()} repeated=${repeated.render()}",
                                    CompilerMessageLocation.create(context.bandwidthSourcePath()),
                                )
                            }
                        } else {
                            error(
                                function.source,
                                "Network work in concurrent repeated @Handler function " +
                                    "${function.displayName()} may overlap across an unknown " +
                                    "number of invocations. Use a self bound for every download.",
                            )
                        }
                    }
                    function.isEntryPoint(session) -> applicationEntryPoints.record(inferredEffect)
                }
            } else if (function.isEntryPoint(session) || isDeclarationHandler) {
                val annotationName = if (isDeclarationHandler) "@Handler" else "@EntryPoint"
                error(
                    function.source,
                    "An $annotationName function cannot have polymorphic bandwidth effects.",
                )
            }
            if (reportEffects) {
                messages.report(
                    CompilerMessageSeverity.INFO,
                    "RAW_FUNCTION_EFFECT function=${function.displayName()} " +
                        "eager=${inferred.network.render()} " +
                        "returned=${inferred.returned?.network?.render() ?: "none"}",
                    CompilerMessageLocation.create(context.bandwidthSourcePath()),
                )
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

    /** Default arguments obey the same Flow contract as explicitly passed values. */
    private fun validateFlowParameterDefaults(function: FirFunction) {
        val defaults = function.valueParameters.filter {
            it.defaultValue != null && it.returnTypeRef.coneType.isBandwidthFlow(session)
        }
        if (defaults.isEmpty()) return
        val variables = function.effectVariableIds(session).associateBy(EffectVariableId::name)
        val context = KotlinEffectContext(function, session, latentValues)
        function.valueParameters.forEach { parameter ->
            val name = parameter.effectContract(session)?.variable
                ?: parameter.returnTypeRef.effectContract(session)?.variable
            variables[name]?.let { variable ->
                context.bind(parameter.symbol, LatentNetworkEffect(network = Effect.Variable(variable)))
            }
        }
        function.receiverParameter?.let { receiver ->
            val name = receiver.effectContract(session)?.variable
                ?: receiver.typeRef.effectContract(session)?.variable
            variables[name]?.let { variable ->
                context.bind(receiver.symbol, LatentNetworkEffect(network = Effect.Variable(variable)))
            }
        }
        defaults.forEach { parameter ->
            val expression = parameter.defaultValue ?: return@forEach
            val inferred = effectVisitor.infer(expression, context)
            if (inferred.network.concreteOrNull() != NetworkEffect.EMPTY) {
                error(
                    expression.source ?: parameter.source,
                    "Eager network work in a Flow default argument is not modeled at call " +
                        "boundaries. Use an explicit initialization argument.",
                )
            }
            val contract = parameter.effectContract(session)
                ?: parameter.returnTypeRef.effectContract(session)
            val expected = contract?.variable?.let { name ->
                variables[name]?.let { Effect.Variable(it) }
            }
                ?: contract?.takeIf { it.variable == null }?.network?.asEffect()
                ?: Effect.Empty
            val actual = inferred.latent?.network ?: if (
                expression is FirLiteralExpression && expression.value == null
            ) Effect.Empty else null
            val concreteActual = actual?.concreteOrNull()
            val concreteExpected = expected.concreteOrNull()
            val covered = actual != null && (
                actual == expected || concreteActual == NetworkEffect.EMPTY ||
                    (concreteActual != null && concreteExpected != null &&
                        concreteActual.isCoveredBy(concreteExpected))
                )
            if (!covered) {
                error(
                    expression.source ?: parameter.source,
                    "Flow default argument effect ${actual?.render() ?: "unknown"} is not " +
                        "covered by parameter contract ${expected.render()}.",
                )
            }
        }
    }

    /**
     * Checks the effect of collecting a Flow produced by a visible body.
     * Missing or non-concrete latent information cannot establish an empty
     * contract; opaque declarations are trusted before reaching this check.
     */
    private fun validateReturnedFlowContract(
        function: FirFunction,
        returned: LatentNetworkEffect?,
        contract: EffectContract,
        overridden: Boolean,
    ) {
        val contractDescription = if (overridden) {
            "the overridden @BandwidthEffect contract ${contract.network.render()}"
        } else {
            "@BandwidthEffect contract ${contract.network.render()}"
        }
        val inferredLatent = returned?.network?.concreteOrNull()
        if (inferredLatent == null) {
            val reason = if (returned == null) "unknown" else "not concrete"
            error(
                function.source,
                "Cannot verify returned Flow effect of ${function.displayName()} against " +
                    "$contractDescription: the returned Flow effect is $reason.",
            )
        } else if (!inferredLatent.isCoveredBy(contract.network)) {
            error(
                function.source,
                "Inferred returned latent effect ${inferredLatent.render()} is not " +
                    "covered by $contractDescription on the return type.",
            )
        }
    }

    /**
     * Returns a cached or newly inferred summary for [function], using primitive
     * contracts and annotated recursion boundaries when available.
     */
    private fun inferFunctionEffect(function: FirFunction): KotlinFunctionEffect {
        function.downloadContract(session)?.let { contract ->
            return KotlinFunctionEffect(
                network = contract.network.asEffect(),
            )
        }
        function.symbolicInvocationEffect(session)?.let { contract ->
            return KotlinFunctionEffect(network = contract)
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
