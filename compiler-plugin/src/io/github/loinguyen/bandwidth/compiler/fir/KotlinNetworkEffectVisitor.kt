package io.github.loinguyen.bandwidth.compiler.fir

import io.github.loinguyen.bandwidth.core.NetworkEffect
import io.github.loinguyen.bandwidth.core.DownloadLifetime
import java.util.IdentityHashMap
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.originalForSubstitutionOverride
import org.jetbrains.kotlin.fir.unwrapFakeOverrides
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirAnonymousObject
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirCallableDeclaration
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirPropertyAccessor
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.evaluateAs
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirCallableReferenceAccess
import org.jetbrains.kotlin.fir.expressions.FirCheckedSafeCallSubject
import org.jetbrains.kotlin.fir.expressions.FirDoWhileLoop
import org.jetbrains.kotlin.fir.expressions.FirElvisExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirCall
import org.jetbrains.kotlin.fir.expressions.FirDelegatedConstructorCall
import org.jetbrains.kotlin.fir.expressions.FirImplicitInvokeCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.expressions.FirLoop
import org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSafeCallExpression
import org.jetbrains.kotlin.fir.expressions.FirSpreadArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirTypeOperatorCall
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.expressions.FirVarargArgumentsExpression
import org.jetbrains.kotlin.fir.expressions.FirWhenExpression
import org.jetbrains.kotlin.fir.expressions.FirWhileLoop
import org.jetbrains.kotlin.fir.expressions.FirWrappedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirWrappedExpression
import org.jetbrains.kotlin.fir.expressions.impl.FirResolvedArgumentList
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.ConeKotlinTypeProjection
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.visitors.FirVisitor
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.descriptors.Modality

/**
 * Infers the effect component of Kotlin's resolved FIR type-and-effect
 * judgment. Kotlin syntax and concurrency rules are centralized here.
 */
internal class KotlinNetworkEffectVisitor(
    private val session: FirSession,
    private val inferFunction: (FirFunction) -> KotlinFunctionEffect,
    private val reportInfo: (String) -> Unit,
    private val reportProblem: (
        key: String,
        source: KtSourceElement?,
        message: String,
    ) -> Unit,
) : FirVisitor<KotlinExpressionEffect, KotlinEffectContext>() {
    /** Scope ownership may justify a guarded wait without ending its overlap phase. */
    private val structuredPhases =
        IdentityHashMap<KotlinEffectContext, CoroutinePhaseState<FirBasedSymbol<*>>>()

    /**
     * Infers the eager effect and callback-valued return of [function].
     *
     * @param latentValues shared bindings for function and Flow values resolved
     * across interprocedural analysis.
     */
    fun inferFunctionBody(
        function: FirFunction,
        latentValues: MutableMap<FirBasedSymbol<*>, LatentNetworkEffect>,
    ): KotlinFunctionEffect {
        if (function is FirPropertyAccessor && function.isGetter) {
            val property = function.propertySymbol.fir
            property.bandwidthPureLazyFlowInitializer(session)?.let { initializer ->
                val summary = inferFunction(initializer.anonymousFunction)
                val invalid = when {
                    summary.network.concreteOrNull() != NetworkEffect.EMPTY ->
                        "Eager network work in a delegated lazy Flow initializer is unsupported. " +
                            "Use an explicit initialization function instead."
                    summary.returned?.network?.concreteOrNull() == null ->
                        "Cannot infer the stored Flow effect of a delegated lazy Flow initializer."
                    else -> null
                }
                if (invalid != null) {
                    problem("lazy-flow-getter:" + property.symbol.callableId,
                        initializer.source ?: property.source, invalid)
                    return KotlinFunctionEffect(
                        network = Effect.Invalid(invalid),
                        returned = LatentNetworkEffect(network = Effect.Invalid(invalid)),
                    )
                }
                // A pure, visible initializer may run on the first read, but
                // it adds no eager network work. Its known Flow effect still
                // has to satisfy the ordinary property's checked invariant.
                return KotlinFunctionEffect(returned = summary.returned)
            }
        }
        val context = KotlinEffectContext(function, session, latentValues)
        val variables = function.effectVariableIds(session).associateBy(EffectVariableId::name)
        function.receiverParameter?.let { receiver ->
            val contract = receiver.effectContract(session) ?: receiver.typeRef.effectContract(session)
            contract?.variable?.let { name ->
                variables[name]?.let { variable ->
                    context.bind(receiver.symbol, LatentNetworkEffect(network = Effect.Variable(variable)))
                }
            }
        }
        function.valueParameters.forEach { parameter ->
            val variableName = parameter.effectContract(session)?.variable
                ?: parameter.returnTypeRef.effectContract(session)?.variable
                ?: return@forEach
            variables[variableName]?.let { variable ->
                context.bind(
                    parameter.symbol,
                    LatentNetworkEffect(network = Effect.Variable(variable)),
                )
            }
        }
        val delegation = if (function is FirConstructor) infer(function.delegatedConstructor, context)
            else KotlinExpressionEffect()
        val initialization = if (function is FirConstructor && function.delegatedConstructor?.isThis != true) {
            inferConstructorInitialization(function, context)
        } else KotlinExpressionEffect()
        val bodyEffect: KotlinExpressionEffect = delegation.then(initialization).then(infer(function.body, context))
        val expressionResult: LatentNetworkEffect? =
            if (function.returnTypeRef.coneType.isBandwidthLatentValue(session)) {
                bodyEffect.latent
            } else {
                null
            }
        return KotlinFunctionEffect(
            network = bodyEffect.network,
            returned = context.returnedLatent.join(expressionResult),
        )
    }

    /** Super construction precedes lexical field/init work; this-delegation owns it already. */
    @OptIn(DirectDeclarationsAccess::class)
    private fun inferConstructorInitialization(
        constructor: FirConstructor,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val owner = constructor.initializationOwner(session)
            ?.takeIf { it.hasModeledConstructorInitialization() }
            ?: return KotlinExpressionEffect()
        return owner.declarations.fold(KotlinExpressionEffect()) { accumulated, declaration ->
            val effect = when (declaration) {
                is FirProperty -> infer(declaration, context)
                is FirAnonymousInitializer -> infer(declaration.body, context)
                else -> KotlinExpressionEffect()
            }
            accumulated.then(effect)
        }
    }

    /**
     * Infers [element], treating a missing FIR node as an effect-free expression.
     */
    fun infer(
        element: FirElement?,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val effect = context.evaluatedExpressions[element]
            ?: element?.accept(this, context)
            ?: KotlinExpressionEffect()
        // Blocks and returns are control-flow containers: their missing value
        // metadata must not overwrite values already recorded by recordReturn.
        if (
            effect.latent == null && element is FirExpression &&
            element !is FirBlock && element !is FirReturnExpression &&
            element.resolvedType.isBandwidthFlow(session)
        ) {
            return effect.withExpectedFlowValue(element)
        }
        return effect
    }

    /** Preserves a known null value while making an unknown Flow explicit. */
    private fun KotlinExpressionEffect.withExpectedFlowValue(
        expression: FirElement?,
    ): KotlinExpressionEffect =
        if (latent != null) this else copy(
            latent = if (expression.isDefinitelyNullValue()) {
                LatentNetworkEffect()
            } else {
                untrackedFlowEffect()
            },
        )

    /** Null alternatives contain no producer to collect; other unknowns do. */
    private fun FirElement?.isDefinitelyNullValue(): Boolean = when (this) {
        is FirLiteralExpression -> value == null
        is FirWrappedArgumentExpression -> expression.isDefinitelyNullValue()
        is FirWrappedExpression -> expression.isDefinitelyNullValue()
        is FirTypeOperatorCall -> argumentList.arguments.singleOrNull().isDefinitelyNullValue()
        is FirBlock -> statements.lastOrNull().isDefinitelyNullValue()
        is FirWhenExpression -> branches.isNotEmpty() &&
            branches.all { it.result.isDefinitelyNullValue() }
        else -> false
    }

    /**
     * Infers an otherwise unsupported element through its conversion operand or
     * non-function children.
     */
    override fun visitElement(
        element: FirElement,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        // FIR versions disagree on whether named declarations dispatch through
        // visitFunction or directly here. Use the common base type so both
        // routes keep declarations latent; declaration checkers still validate
        // every body and contract independently.
        if (element is FirFunction) return KotlinExpressionEffect()
        // A named local class declaration does not construct an instance.
        // Its fields/init blocks are charged through constructor calls only.
        if (element is FirClass && element !is FirAnonymousObject) return KotlinExpressionEffect()
        return element.functionTypeConversionOperand()?.let { infer(it, data) }
            ?: inferChildren(element, data)
    }

    /**
     * Leaves a nested function declaration latent; its body is charged only on
     * invocation.
     */
    override fun visitFunction(
        function: FirFunction,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = KotlinExpressionEffect()

    /**
     * Wraps an ordinary anonymous function as a latent value. A `@Handler`
     * expression instead materializes a retained, concurrently repeated
     * callback at the registration site.
     */
    override fun visitAnonymousFunctionExpression(
        anonymousFunctionExpression: FirAnonymousFunctionExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val function = anonymousFunctionExpression.anonymousFunction
        val summary = inferFunction(function)
        if (!function.isHandler(session)) {
            return KotlinExpressionEffect(latent = summary.asLatent())
        }
        val handlerEffect = repeatedCallbackNetwork(
            bodyNetwork = summary.network,
            callbackName = "@Handler callback",
            key = "annotated-handler:${data.function.displayName()}:" +
                anonymousFunctionExpression.source?.startOffset,
            source = anonymousFunctionExpression.source ?: data.function.source,
            policy = RepeatedCallbackPolicy.RETAINED_CONCURRENT,
        )
        return KotlinExpressionEffect(network = handlerEffect)
    }

    /**
     * Evaluates a callable reference's receivers and returns the target summary
     * as a latent value.
     */
    override fun visitCallableReferenceAccess(
        callableReferenceAccess: FirCallableReferenceAccess,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val receivers = inferReceivers(callableReferenceAccess, data)
        val target = callableReferenceAccess.resolvedSymbol()?.fir as? FirFunction
        if (target?.concurrentMapContract(session) != null) {
            val message = "@BandwidthConcurrentMap operators must be called directly " +
                "with a statically known concurrency; callable references are unsupported."
            problem(
                key = "concurrent-map-reference:" + callableReferenceAccess.source?.startOffset,
                source = callableReferenceAccess.source ?: data.function.source,
                message = message,
            )
            return KotlinExpressionEffect(network = Effect.Invalid(message))
        }
        if (target?.receiverParameter?.typeRef?.coneType?.isBandwidthFlow(session) == true) {
            val message = "Flow extension callable references require receiver-effect " +
                "instantiation; use a lambda with a direct annotated call instead."
            problem("flow-extension-reference:" + callableReferenceAccess.source?.startOffset,
                callableReferenceAccess.source ?: data.function.source, message)
            return KotlinExpressionEffect(network = receivers.network.then(Effect.Invalid(message)))
        }
        return KotlinExpressionEffect(
            network = receivers.network,
            latent = target?.let(::functionSummary)?.asLatent(),
        )
    }

    /**
     * Infers a property initializer and binds any resulting latent value to the
     * property symbol.
     */
    override fun visitProperty(
        property: FirProperty,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val initializationExpression = property.delegate ?: property.initializer
        val initializer = infer(initializationExpression, data).let { effect ->
            if (property.delegate == null && property.initializer != null &&
                property.returnTypeRef.coneType.isBandwidthFlow(session)
            ) {
                effect.withExpectedFlowValue(property.initializer)
            } else {
                effect
            }
        }
        if (property.isContractedFlowStorage(session) && property.initializer != null) {
            property.flowStorageProblem(initializer.latent, session)?.let { message ->
                problem("flow-storage-init:" + property.symbol.callableId,
                    property.initializer?.source ?: property.source, message)
            }
        }
        data.bind(property.symbol, initializer.latent)
        return KotlinExpressionEffect(
            network = initializer.network,
        )
    }

    /** Infers receiver work and restores the latent value bound to a property. */
    override fun visitPropertyAccessExpression(
        propertyAccessExpression: FirPropertyAccessExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val receivers = inferReceivers(propertyAccessExpression, data)
        val symbol = propertyAccessExpression.resolvedSymbol()
        val property = symbol?.flowStorageProperty()
        if (property != null) validateFlowPropertyReceiver(property, propertyAccessExpression, data)
        if (property?.isContractedFlowStorage(session) == true) {
            // Getter evaluation can perform eager work; its returned Flow is
            // described by the stable storage invariant, not any one instance.
            val getterWork = if (symbol?.fir is FirProperty) {
                property.getter?.let { functionSummary(it).network } ?: Effect.Empty
            } else Effect.Empty
            return KotlinExpressionEffect(
                network = receivers.network.then(getterWork),
                latent = property.flowStorageEffect(session),
            )
        }
        // Function summaries are cached independently of mutable storage. A
        // captured var can change after its getter/helper has been summarized.
        // Reject these sources until the cache tracks such dependencies.
        if (property != null && !property.isVal &&
            property.returnTypeRef.coneType.isBandwidthFlow(session)
        ) {
            return KotlinExpressionEffect(
                network = receivers.network,
                latent = untrackedFlowEffect(),
            )
        }
        // Unlike Flow storage, ordinary channel storage has no checked
        // assignment invariant. A mutable channel must not retain the source
        // metadata of an earlier local allocation after being reassigned.
        if (property != null && !property.isVal &&
            property.returnTypeRef.coneType.isBandwidthReceiveChannel(session)
        ) return KotlinExpressionEffect(network = receivers.network)
        val localChannel = if (data.latentOf(symbol) == null && property?.isVal == true &&
            property.status.modality == Modality.FINAL && !property.status.isOverride &&
            property.getter?.body == null
        ) {
            // A nonlocal field's initializer is not necessarily visited by a
            // function walk. Recover only a visible, effect-free allocation;
            // never evaluate constructor/startup network work at a read site.
            val factory = property.initializer as? FirFunctionCall
            factory?.takeIf {
                it.resolvedFunction()?.symbol?.callableId?.asSingleFqName()?.asString() ==
                    "kotlinx.coroutines.channels.Channel"
            }?.let { infer(it, data) }?.takeIf {
                it.network.concreteOrNull() == NetworkEffect.EMPTY
            }?.latent
        } else null
        return KotlinExpressionEffect(
            network = receivers.network.then(
                if (symbol?.fir is FirProperty &&
                    property?.receiverParameter?.typeRef?.coneType?.isBandwidthLatentValue(session) == true
                ) property.getter?.let { functionSummary(it).network } ?: Effect.Empty
                else Effect.Empty,
            ),
            latent = data.latentOf(symbol) ?: localChannel,
        )
    }

    /**
     * Infers an assigned value and conservatively joins its latent effect into
     * the target symbol.
     */
    override fun visitVariableAssignment(
        variableAssignment: FirVariableAssignment,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val target = (variableAssignment.lValue as? FirPropertyAccessExpression)
            ?.resolvedSymbol()
        val targetType = (target?.fir as? FirCallableDeclaration)?.returnTypeRef?.coneType
        val value = infer(variableAssignment.rValue, data).let { effect ->
            if (targetType?.isBandwidthFlow(session) == true) {
                effect.withExpectedFlowValue(variableAssignment.rValue)
            } else {
                effect
            }
        }
        val storage = target?.flowStorageProperty()?.takeIf { it.isContractedFlowStorage(session) }
        storage?.flowStorageProblem(value.latent, session)?.let { message ->
            problem("flow-storage-write:" + data.function.displayName() + ":" +
                variableAssignment.source?.startOffset, variableAssignment.source, message)
        }
        val property = target?.flowStorageProperty()
        if (property != null) {
            (variableAssignment.lValue as? FirPropertyAccessExpression)?.let {
                validateFlowPropertyReceiver(property, it, data)
            }
        }
        data.bind(target, value.latent)
        val setterWork = if (target?.fir is FirProperty && (storage != null ||
            property?.receiverParameter?.typeRef?.coneType?.isBandwidthLatentValue(session) == true)
        ) {
            property?.setter?.let { functionSummary(it).network } ?: Effect.Empty
        } else Effect.Empty
        val receivers = (variableAssignment.lValue as? FirPropertyAccessExpression)
            ?.let { inferReceivers(it, data).network } ?: Effect.Empty
        return KotlinExpressionEffect(
            network = receivers.then(value.network).then(setterWork),
        )
    }

    /** Both reading and writing an extension property must satisfy its receiver contract. */
    private fun validateFlowPropertyReceiver(
        property: FirProperty,
        access: FirPropertyAccessExpression,
        context: KotlinEffectContext,
    ) {
        val receiver = property.receiverParameter ?: return
        if (!receiver.typeRef.coneType.isBandwidthLatentValue(session)) return
        val actual = (access.extensionReceiver ?: access.explicitReceiver)
            ?.let { infer(it, context).latent?.network }
        val contract = receiver.effectContract(session) ?: receiver.typeRef.effectContract(session)
        val declared = contract?.network ?: NetworkEffect.EMPTY
        if (contract?.variable != null || actual?.concreteOrNull()?.isCoveredBy(declared) != true) {
            problem("flow-property-receiver:" + property.symbol.callableId + ":" + access.source?.startOffset,
                access.source,
                "Flow receiver effect is not covered by the extension property's concrete contract " + declared.render() + ".")
        }
    }

    /** Preserve a nullable latent value while evaluating the receiver exactly once. */
    override fun visitSafeCallExpression(
        safeCallExpression: FirSafeCallExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val receiver = infer(safeCallExpression.receiver, data)
        val subject = safeCallExpression.checkedSubjectRef.value
        val hadPrevious = data.safeCallSubjects.containsKey(subject)
        val previous = data.safeCallSubjects.put(subject, receiver.latent)
        return try {
            receiver.then(infer(safeCallExpression.selector, data))
        } finally {
            if (hadPrevious) data.safeCallSubjects[subject] = previous
            else data.safeCallSubjects.remove(subject)
        }
    }

    /** The safe-call subject is a value, not a second evaluation of its producer. */
    override fun visitCheckedSafeCallSubject(
        checkedSafeCallSubject: FirCheckedSafeCallSubject,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect =
        KotlinExpressionEffect(latent = data.safeCallSubjects[checkedSafeCallSubject])

    /** Resolves an extension receiver as a latent value, just like a parameter. */
    override fun visitThisReceiverExpression(
        thisReceiverExpression: FirThisReceiverExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val symbol = thisReceiverExpression.calleeReference.boundSymbol as? FirBasedSymbol<*>
        val receiver = (symbol?.fir as? FirFunction)?.receiverParameter?.symbol ?: symbol
        return KotlinExpressionEffect(latent = data.latentOf(receiver))
    }

    /** Forwards a type operator to its single operand. */
    override fun visitTypeOperatorCall(
        typeOperatorCall: FirTypeOperatorCall,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect =
        infer(typeOperatorCall.argumentList.arguments.singleOrNull(), data)

    /** Forwards a wrapped argument to its underlying expression. */
    override fun visitWrappedArgumentExpression(
        wrappedArgumentExpression: FirWrappedArgumentExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = infer(wrappedArgumentExpression.expression, data)

    /** Forwards a named argument to its underlying expression. */
    override fun visitNamedArgumentExpression(
        namedArgumentExpression: FirNamedArgumentExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = infer(namedArgumentExpression.expression, data)

    /** Forwards a spread argument to its underlying expression. */
    override fun visitSpreadArgumentExpression(
        spreadArgumentExpression: FirSpreadArgumentExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = infer(spreadArgumentExpression.expression, data)

    /** Forwards a generic FIR wrapper to its underlying expression. */
    override fun visitWrappedExpression(
        wrappedExpression: FirWrappedExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = infer(wrappedExpression.expression, data)

    /** Infers an ordinary resolved function call. */
    override fun visitFunctionCall(
        functionCall: FirFunctionCall,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = inferCall(functionCall, data)

    /** Constructor delegation obeys the same parameter contracts as ordinary calls. */
    override fun visitDelegatedConstructorCall(
        delegatedConstructorCall: FirDelegatedConstructorCall,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val call = delegatedConstructorCall
        val cached = IdentityHashMap<FirExpression, KotlinExpressionEffect>()
        fun effectOf(expression: FirExpression): KotlinExpressionEffect =
            cached.getOrPut(expression) { infer(expression, data) }
        val inputs = sequence(call.argumentList.arguments.map(::effectOf))
        val target = (call.calleeReference as? FirResolvedNamedReference)?.resolvedSymbol?.fir as? FirFunction
            ?: return inputs
        val substitution = validateHigherOrderArguments(
            call, target, call.argumentEffectsByParameter(::effectOf), null, data,
        )
        return inputs.then(KotlinExpressionEffect(
            network = functionSummary(target, substitution).network,
        ))
    }

    /** Infers invocation of a Kotlin function value. */
    override fun visitImplicitInvokeCall(
        implicitInvokeCall: FirImplicitInvokeCall,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = inferCall(implicitInvokeCall, data)

    /**
     * Sequentially evaluates each branch condition with its result, then joins
     * the branches as alternatives.
     */
    override fun visitWhenExpression(
        whenExpression: FirWhenExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = choice(
        whenExpression.branches.map { branch ->
            thenValue(
                infer(branch.condition, data),
                infer(branch.result, data),
            )
        },
    )

    /** Preserve both possible Elvis values; the left operand is evaluated once. */
    override fun visitElvisExpression(
        elvisExpression: FirElvisExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val left = infer(elvisExpression.lhs, data)
        val right = infer(elvisExpression.rhs, data)
        // The right operand is conditional. Counting its eager work after the
        // left is conservative, while the returned producer is an alternative.
        return left.then(right).copy(latent = left.latent.join(right.latent))
    }

    /**
     * Joins the try and catch paths as alternatives and appends the finally
     * effect to every path.
     */
    override fun visitTryExpression(
        tryExpression: FirTryExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val alternatives = choice(
            listOf(infer(tryExpression.tryBlock, data)) +
                tryExpression.catches.map { infer(it.block, data) },
        )
        val finallyEffect = infer(tryExpression.finallyBlock, data)
        return alternatives.then(finallyEffect).copy(latent = alternatives.latent)
    }

    /** Applies the general-loop inference rule to [loop]. */
    override fun visitLoop(
        loop: FirLoop,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = inferLoop(loop, data)

    /** Applies the general-loop inference rule to [whileLoop]. */
    override fun visitWhileLoop(
        whileLoop: FirWhileLoop,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = inferLoop(whileLoop, data)

    /** Applies the general-loop inference rule to [doWhileLoop]. */
    override fun visitDoWhileLoop(
        doWhileLoop: FirDoWhileLoop,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = inferLoop(doWhileLoop, data)

    /**
     * Infers the returned expression and records callback-valued returns in the
     * enclosing function context.
     */
    override fun visitReturnExpression(
        returnExpression: FirReturnExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val value = infer(returnExpression.result, data).let { effect ->
            if (data.function.returnTypeRef.coneType.isBandwidthFlow(session)) {
                effect.withExpectedFlowValue(returnExpression.result)
            } else {
                effect
            }
        }
        data.recordReturn(value.latent)
        return KotlinExpressionEffect(
            network = value.network,
        )
    }

    /** Sequentially composes every statement in [block]. */
    override fun visitBlock(
        block: FirBlock,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = sequence(
        block.statements.map { infer(it, data) },
    )

    /**
     * Dispatches a resolved call to specialized coroutine or callback rules,
     * falling back to ordinary argument evaluation and function summarization.
     */
    private fun inferCall(
        call: FirFunctionCall,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val target: FirFunction =
            (call.resolvedSymbol() as? FirFunctionSymbol<*>)?.fir
                ?: return inferChildren(call, context)
        target.concurrentMapContract(session)?.let { contract ->
            return inferConcurrentMap(call, contract, context)
        }
        if (target.isStructuredCoroutineScope()) {
            return inferStructuredCoroutineScope(call, context)
        }
        if (target.isCoroutineBuilder()) {
            return inferUnstructuredCoroutineBuilder(call, context)
        }
        if (target.isAwaitAll()) {
            return inferAwaitAll(call, context)
        }
        if (target.isFlowBuilder()) {
            return inferFlowBuilder(call, context)
        }
        if (target.isFlowSourceBuilder()) {
            return inferFlowSourceBuilder(call, context)
        }
        when (target.symbol.callableId.asSingleFqName().asString()) {
            "kotlinx.coroutines.flow.MutableStateFlow",
            "kotlinx.coroutines.flow.MutableSharedFlow" ->
                return inferLocalMutableFlowSource(call, target, context)
            "kotlinx.coroutines.flow.asStateFlow",
            "kotlinx.coroutines.flow.asSharedFlow" ->
                return inferTrackedFlowWrapper(call, target, context)
            "kotlinx.coroutines.channels.Channel" ->
                return inferLocalChannelSource(call, target, context)
            "kotlinx.coroutines.flow.receiveAsFlow" ->
                return inferTrackedFlowWrapper(call, target, context)
        }
        if (target.symbol.callableId.asSingleFqName().asString() == "kotlinx.coroutines.flow.emptyFlow" &&
            target.receiverParameter == null && target.valueParameters.isEmpty() &&
            target.returnTypeRef.coneType.isBandwidthFlow(session)
        ) {
            // This exact zero-argument cold source emits no values and starts
            // no producer work. Preserve any eagerly evaluated inputs.
            return sequence(call.receiverExpressions().map { infer(it, context) } +
                call.argumentList.arguments.map { infer(it, context) })
                .copy(latent = LatentNetworkEffect())
        }
        if (target.symbol.callableId.asSingleFqName().asString() == "androidx.room.coroutines.createFlow") {
            return inferRoomQueryFlow(call, target, context)
        }
        if (target.symbol.callableId.asSingleFqName().asString() in setOf(
                "kotlinx.coroutines.flow.cancellable",
                "kotlinx.coroutines.flow.filterNotNull",
                "kotlinx.coroutines.flow.buffer",
                "kotlinx.coroutines.flow.flowOn",
            )
        ) {
            // These operators preserve producer work and add no network callbacks.
            val receivers = call.receiverExpressions().map { infer(it, context) }
            return sequence(receivers + call.argumentList.arguments.map { infer(it, context) })
                .copy(latent = receivers.mapNotNull { it.latent }.singleOrNull())
        }
        if (target.symbol.callableId.asSingleFqName().asString() ==
                "kotlinx.coroutines.flow.distinctUntilChanged" &&
            target.valueParameters.isEmpty()
        ) {
            // The no-comparator overload only compares ordinary values; it
            // does not invoke a user-supplied network callback. Overloads with
            // a comparator continue through ordinary callback validation.
            return inferTrackedFlowWrapper(call, target, context)
        }
        if (target.symbol.callableId.asSingleFqName().asString() in setOf(
                "kotlinx.coroutines.flow.catch", "kotlinx.coroutines.flow.onCompletion",
                "kotlinx.coroutines.flow.onStart",
            )
        ) {
            return inferFlowCompletionOperator(call, target, context)
        }
        if (target.symbol.callableId.asSingleFqName().asString() in setOf(
                "kotlinx.coroutines.flow.filter",
                "kotlinx.coroutines.flow.map",
                "kotlinx.coroutines.flow.transform",
                "kotlinx.coroutines.flow.onEach",
            )
        ) {
            return inferFlowElementOperator(call, target, context)
        }
        if (target.symbol.callableId.asSingleFqName().asString() == "kotlinx.coroutines.flow.emitAll") {
            return inferFlowEmitAll(call, target, context)
        }
        if (target.isFlatMapMerge()) {
            return inferFlatMapMerge(call, context)
        }
        if (target.symbol.callableId.asSingleFqName().asString() == "kotlinx.coroutines.flow.flatMapConcat") {
            return inferFlatMapConcat(call, target, context)
        }
        if (target.symbol.callableId.asSingleFqName().asString() == "kotlinx.coroutines.flow.toList") {
            return inferFlowToList(call, context)
        }
        if (target.isSemaphoreWithPermit()) {
            return inferSemaphoreWithPermit(call, context)
        }
        if (target.isFlowCollect()) {
            return inferFlowCollect(call, context)
        }
        if (target.symbol.callableId.asSingleFqName().asString() ==
            "kotlinx.coroutines.flow.launchIn"
        ) {
            return inferFlowLaunchIn(call, context)
        }
        val repeatedCallbackPolicy = target.repeatedCallbackPolicy()
        if (repeatedCallbackPolicy != null) {
            return inferRepeatedCallbackCall(
                call = call,
                target = target,
                context = context,
                policy = repeatedCallbackPolicy,
            )
        }
        if (target.isSingleCallbackFunction()) {
            return inferSingleCallbackCall(call, context)
        }
        val cachedEffects = IdentityHashMap<FirExpression, KotlinExpressionEffect>()
        /** Infers [expression] at most once for this call site. */
        fun effectOf(expression: FirExpression): KotlinExpressionEffect =
            cachedEffects.getOrPut(expression) { infer(expression, context) }

        val receivers = call.receiverExpressions()
        val receiverEffects = sequence(receivers.map(::effectOf))
        val arguments = call.argumentList.arguments
        val evaluatedArguments = sequence(arguments.map(::effectOf))
        val argumentEffects = call.argumentEffectsByParameter(::effectOf)
        val effectBindings = validateHigherOrderArguments(
            call = call,
            target = target,
            argumentEffects = argumentEffects,
            receiverEffect = target.receiverParameter?.let {
                (call.extensionReceiver ?: call.explicitReceiver)?.let(::effectOf)
            },
            context = context,
        )

        val evaluatedInputs = receiverEffects.then(evaluatedArguments)
        val invokedReceiver = call.explicitReceiver ?: call.dispatchReceiver
        val isExplicitFunctionInvoke = target.symbol.callableId.callableName.asString() == "invoke" &&
            invokedReceiver?.resolvedType?.isSomeFunctionType(session) == true
        if (call is FirImplicitInvokeCall || isExplicitFunctionInvoke) {
            val invokedValue = invokedReceiver
            return inferFunctionInvocation(
                invokedValueEffect = invokedValue?.let(::effectOf),
                evaluatedInputs = evaluatedInputs,
            )
        }

        val summary = functionSummary(
            target = target,
            effectBindings = effectBindings,
            call = call,
            context = context,
        )
        val invocation = call.clientSelfBound(target)?.let { bound ->
            summary.copy(network = summary.network.withSelfBound(bound))
        } ?: summary
        return evaluatedInputs.then(
            KotlinExpressionEffect(
                network = invocation.network,
                latent = invocation.returned,
            ),
        ).copy(
            latent = invocation.returned,
        )
    }

    /**
     * A coroutine scope is split into sequential phases. launch/async children
     * overlap each phase until a direct join/await on their local handle.
     */
    private fun inferStructuredCoroutineScope(
        call: FirFunctionCall,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val lambda = call.visibleLambdaArgument()
        val body = lambda?.anonymousFunction?.body
        if (body == null) {
            problem(
                key = "coroutine-scope:${context.function.displayName()}:" +
                    "${call.source?.startOffset}",
                source = call.source ?: context.function.source,
                message = "Cannot infer structured coroutine scope with a non-visible block. " +
                    "Keep the structured coroutine lambda visible.",
            )
            return KotlinExpressionEffect()
        }
        val evaluatedInputs = sequence(
            call.receiverExpressions().map { infer(it, context) } +
                call.argumentList.arguments
                    .filter { it.visibleLambda() !== lambda }
                    .map { infer(it, context) },
        )
        return thenValue(evaluatedInputs, inferCoroutineStatements(body, context))
    }

    /**
     * Infers `awaitAll` for inline async arguments or a directly mapped async
     * collection, composing all child starts before the common synchronization.
     * Stored mapped collections are synchronized by the enclosing phase tracker.
     */
    private fun inferAwaitAll(
        call: FirFunctionCall,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val arguments = call.argumentList.arguments.flatMap { it.flattenVarargArguments() }
        if (arguments.isEmpty()) {
            val repeatedReceiver = call.receiverExpressions()
                .mapNotNull { it.repeatedStructuredChildren() }
                .singleOrNull()
            if (repeatedReceiver != null) {
                val repeated = inferRepeatedCoroutineChildren(repeatedReceiver, context)
                val phases = CoroutinePhaseState<Unit>()
                phases.recordStatement(repeated.inputs)
                repeated.body?.let { phases.addChild(handle = null, effect = it) }
                if (repeated.preparation.network != Effect.Empty) {
                    phases.recordStatement(repeated.preparation)
                }
                return phases.finish()
            }
            problem(
                key = "await-all-collection:${context.function.displayName()}:" +
                    "${call.source?.startOffset}",
                source = call.source ?: context.function.source,
                message = "Cannot infer awaitAll for an untracked Deferred collection. " +
                    "Create the collection with a visible map { async { ... } } expression " +
                    "inside the same structured coroutine scope.",
            )
            return inferReceivers(call, context)
        }
        val children = arguments.mapNotNull { it.structuredChild() }
        if (children.size != arguments.size) {
            val phases = structuredPhases[context]
            val ownsEveryHandle = phases != null && arguments.all { argument ->
                val handle = argument.directCoroutineHandle()
                val property = handle?.fir as? FirProperty
                handle != null && property != null && property.isLexicalLocalVariable() && property.isVal &&
                    phases.hasActiveChild(handle)
            }
            if (ownsEveryHandle) {
                // This wait may be guarded by try/catch or another control-flow
                // node. Ownership establishes that its children are already
                // accounted for, but does not prove synchronization on every
                // continuation. Keep them active until a proven wait/scope end.
                return sequence(call.receiverExpressions().map { infer(it, context) } +
                    call.argumentList.arguments.map { infer(it, context) })
            }
            problem(
                key = "await-all:${context.function.displayName()}:" +
                    "${call.source?.startOffset}",
                source = call.source ?: context.function.source,
                message = "Cannot infer awaitAll unless every argument is a visible inline " +
                    "async child. Store-and-await handles require separate synchronization.",
            )
            return KotlinExpressionEffect()
        }
        val resolvedChildren = children.map { inferCoroutineBuilder(it.call, context) }
        val phases = CoroutinePhaseState<Unit>()
        phases.recordStatement(inferReceivers(call, context))
        resolvedChildren.forEach { child ->
            phases.recordStatement(child.inputs)
            child.body?.let { phases.addChild(handle = null, effect = it) }
        }
        return phases.finish()
    }

    /**
     * Infers a collection callback ending in `async` as an unknown number of
     * concurrently active structured children, with serial preparation.
     *
     * The collection source and non-callback arguments are evaluated once.
     * Each mapped async body may overlap every other body. A visible local
     * semaphore guarding the entire child bounds this batch only; otherwise
     * downloads need trusted shared self bounds before replication is finite.
     */
    private fun inferRepeatedCoroutineChildren(
        children: RepeatedStructuredChildren,
        context: KotlinEffectContext,
    ): RepeatedCoroutineChildrenEffect {
        val mapCall = children.mapCall
        val callback = mapCall.visibleLambdaArgument()
        val inputs = sequence(
            mapCall.receiverExpressions().map { infer(it, context) } +
                mapCall.argumentList.arguments
                    .filter { it.visibleLambda() !== callback }
                    .map { infer(it, context) },
        )
        // Bind preparation locals before inferring the returned child's body.
        // These statements run once per element, and may overlap children from
        // earlier elements. Their unrelated escaping work is not joined by
        // awaiting the returned collection.
        val prefix = sequence(children.preparation.map { infer(it, context) })
        val child = inferCoroutineBuilder(children.builderCall, context)
        val preparation = KotlinExpressionEffect(
            network = repeatNetworkEffect(
                effect = prefix.then(child.inputs).network,
                key = "mapped-async-preparation:${context.function.displayName()}:" +
                    "${mapCall.source?.startOffset}",
                source = mapCall.source ?: context.function.source,
                missingBoundMessage = "Escaping network work before a mapped async child may " +
                    "overlap across an unknown number of elements. Use a self bound for every " +
                    "escaping download.",
            ),
        )
        val network = child.body?.network ?: Effect.Empty
        if (network == Effect.Empty) {
            return RepeatedCoroutineChildrenEffect(inputs, preparation, child.body)
        }
        if (network.escapingOnly() != Effect.Empty) {
            val escapingMessage = "Cannot infer map { async { ... } } when an async body starts " +
                "network work that may outlive that child without a self bound. " +
                "Bound retained work independently of the child permit."
            val key = "mapped-async-escaping:${context.function.displayName()}:" +
                mapCall.source?.startOffset
            val source = mapCall.source ?: context.function.source
            val localCapacity = children.localSemaphoreBatchCapacity(context)
            val repeated = if (localCapacity != null) {
                boundedRepeatedNetworkEffect(
                    effect = network,
                    maxConcurrentBodies = localCapacity,
                    key = key,
                    source = source,
                    missingBoundMessage = escapingMessage,
                )
            } else {
                val completing = repeatNetworkEffect(
                    effect = network.completingOnly().withLifetime(DownloadLifetime.MAY_OUTLIVE_CALL),
                    key = "$key:completing",
                    source = source,
                    missingBoundMessage = "Mapped async children need shared self bounds " +
                        "or a visible local Semaphore for completing downloads.",
                ).withLifetime(DownloadLifetime.COMPLETES_WITH_CALL)
                val escaping = repeatNetworkEffect(
                    effect = network.escapingOnly(),
                    key = key,
                    source = source,
                    missingBoundMessage = escapingMessage,
                )
                completing.parallel(escaping)
            }
            return RepeatedCoroutineChildrenEffect(
                inputs = inputs,
                preparation = preparation,
                // Await joins the child, not jobs that the child has retained.
                // Preserve the latter's lifetime across the awaitAll boundary.
                body = KotlinExpressionEffect(network = repeated),
            )
        }
        val localCapacity = children.localSemaphoreBatchCapacity(context)
        val repeated = if (localCapacity != null) {
            // Consume the lexical gate here. Exporting it as a self bound would
            // incorrectly merge different allocations in concurrent calls.
            network.boundedReplication(localCapacity)
        } else repeatNetworkEffect(
            effect = network.withLifetime(DownloadLifetime.MAY_OUTLIVE_CALL),
            key = "mapped-async-self-bound:${context.function.displayName()}:" +
                "${mapCall.source?.startOffset}",
            source = mapCall.source ?: context.function.source,
            missingBoundMessage = "Mapped async children may overlap across an unknown " +
                "number of collection elements. Use a self bound for every download, or " +
                "guard each complete async body with one constant local Semaphore " +
                "allocated before map.",
        )
        return RepeatedCoroutineChildrenEffect(
            inputs = inputs,
            preparation = preparation,
            body = KotlinExpressionEffect(
                network = repeated.withLifetime(DownloadLifetime.COMPLETES_WITH_CALL),
            ),
        )
    }

    /**
     * Recognizes a batch-local resource without allowing it to escape as a
     * process-wide download bound. The gate must be allocated outside this map
     * in the analyzed function, and every access must be a direct withPermit
     * receiver or an availablePermits observation. Aliases, manual release, and
     * passing the gate elsewhere fail this deliberately narrow rule.
     *
     * Only an empty-effect prefix may precede the terminal permit block, so
     * unprotected work cannot inherit its bound. The ordinary effect inference
     * separately requires independent self bounds for escaping work and
     * preserves structured fanout within one permit.
     */
    private fun RepeatedStructuredChildren.localSemaphoreBatchCapacity(
        context: KotlinEffectContext,
    ): Int? {
        val childBody = builderCall.visibleLambdaArgument()?.anonymousFunction?.body
            ?: return null
        val (permit, prefix) = childBody.semaphoreGuardAndPrefix() ?: return null
        if (prefix.any { infer(it, context).network != Effect.Empty }) return null
        val property = permit.receiverExpressions()
            .mapNotNull { it.resolvedSymbol()?.fir as? FirProperty }
            .distinct()
            .singleOrNull()
            ?: return null
        if (!property.isVal || !property.isLexicalLocalVariable()) return null
        val capacity = property.initializer?.semaphoreInitializerCapacity() ?: return null
        val enclosingBody = context.function.body ?: return null
        if (!enclosingBody.containsDeclaration(property) || mapCall.containsDeclaration(property)) {
            return null
        }
        var onlyPermitReceivers = true
        enclosingBody.accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (onlyPermitReceivers) element.acceptChildren(this)
            }

            override fun visitFunctionCall(functionCall: FirFunctionCall) {
                val receivers = functionCall.receiverExpressions()
                if (functionCall.resolvedFunction()?.isSemaphoreWithPermit() == true &&
                    receivers.any { it.resolvedSymbol() == property.symbol }
                ) {
                    // Do not visit the approved receiver, but check arguments
                    // and any other receiver for hidden aliases or releases.
                    receivers.filter { it.resolvedSymbol() != property.symbol }
                        .forEach { it.accept(this) }
                    functionCall.argumentList.arguments.forEach { it.accept(this) }
                } else {
                    visitElement(functionCall)
                }
            }

            override fun visitPropertyAccessExpression(
                propertyAccessExpression: FirPropertyAccessExpression,
            ) {
                val symbol = propertyAccessExpression.resolvedSymbol()
                if (symbol == property.symbol) {
                    onlyPermitReceivers = false
                } else if ((symbol as? FirCallableSymbol<*>)?.callableId
                        ?.asSingleFqName()?.asString() ==
                    "kotlinx.coroutines.sync.Semaphore.availablePermits"
                ) {
                    propertyAccessExpression.receiverExpressions()
                        .filter { it.resolvedSymbol() != property.symbol }
                        .forEach { it.accept(this) }
                } else {
                    visitElement(propertyAccessExpression)
                }
            }
        })
        return capacity.takeIf { onlyPermitReceivers }
    }

    /** Retains prefix work for an explicit emptiness check, never dropping it. */
    private fun FirElement.semaphoreGuardAndPrefix(): Pair<FirFunctionCall, List<FirElement>>? = when (this) {
        is FirFunctionCall -> takeIf { resolvedFunction()?.isSemaphoreWithPermit() == true }
            ?.let { it to emptyList() }
        is FirBlock -> statements.lastOrNull()?.semaphoreGuardAndPrefix()?.let { (call, prefix) ->
            call to (statements.dropLast(1) + prefix)
        }
        is FirReturnExpression -> result.semaphoreGuardAndPrefix()
        is FirWrappedArgumentExpression -> expression.semaphoreGuardAndPrefix()
        is FirWrappedExpression -> expression.semaphoreGuardAndPrefix()
        else -> null
    }

    /** Checks allocation containment by declaration identity, including lambdas. */
    private fun FirElement.containsDeclaration(property: FirProperty): Boolean {
        var found = false
        accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (element === property) found = true
                else if (!found) element.acceptChildren(this)
            }
        })
        return found
    }

    /**
     * Defers a visible `flow { ... }` body until a terminal Flow operator
     * materializes the returned value.
     */
    private fun inferFlowBuilder(
        call: FirFunctionCall,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val callback = call.visibleLambdaArgument()
        val body = callback?.anonymousFunction?.body
        val evaluatedInputs = sequence(
            call.receiverExpressions().map { infer(it, context) } +
                call.argumentList.arguments
                    .filter { it.visibleLambda() !== callback }
                    .map { infer(it, context) },
        )
        if (body == null) {
            problem(
                key = "flow-builder:${context.function.displayName()}:" +
                    call.source?.startOffset,
                source = call.source ?: context.function.source,
                message = "Cannot infer flow { ... } with a non-visible block. " +
                    "Keep the Flow builder lambda visible.",
            )
            return evaluatedInputs
        }
        val bodyEffect = inferFunction(callback.anonymousFunction)
        return evaluatedInputs.copy(
            latent = LatentNetworkEffect(network = bodyEffect.network),
        )
    }

    /**
     * Treats `asFlow()` as a lazy source. A function-valued source is invoked
     * repeatedly; collection and array sources have no intrinsic network work.
     */
    private fun inferFlowSourceBuilder(
        call: FirFunctionCall,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val receivers = call.receiverExpressions().map { infer(it, context) }
        val evaluatedInputs = sequence(
            receivers + call.argumentList.arguments.map { infer(it, context) },
        )
        val producer = receivers.mapNotNull { it.latent }.singleOrNull()
        val sourceNetwork = producer?.network?.let { network ->
            repeatNetworkEffect(
                effect = network,
                key = "flow-source-repeat:${context.function.displayName()}:" +
                    call.source?.startOffset,
                source = call.source ?: context.function.source,
                missingBoundMessage = "Network work escaping a function-valued asFlow source " +
                    "may overlap across an unknown number of values. Use a self bound for " +
                    "every download.",
            )
        } ?: Effect.Empty
        return evaluatedInputs.copy(
            latent = LatentNetworkEffect(network = sourceNetwork),
        )
    }

    /**
     * MutableStateFlow and MutableSharedFlow allocate local signaling storage;
     * observing either starts no producer. Initial/configuration expressions
     * still run now, and independently launched hot producers are charged at
     * their own start sites, never at collection.
     */
    private fun inferLocalMutableFlowSource(
        call: FirFunctionCall,
        target: FirFunction,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val receivers = call.receiverExpressions().map { infer(it, context) }
        val arguments = call.argumentList.arguments.map { infer(it, context) }
        val name = target.symbol.callableId.callableName.asString()
        val expectedParameters = when (name) {
            "MutableStateFlow" -> listOf("value")
            "MutableSharedFlow" -> listOf("replay", "extraBufferCapacity", "onBufferOverflow")
            else -> emptyList()
        }
        val signatureMatches = target.receiverParameter == null &&
            target.valueParameters.map { it.name.asString() } == expectedParameters &&
            target.returnTypeRef.coneType.isBandwidthFlow(session)
        val latentPayload = arguments.any { it.latent != null } ||
            call.hasDirectLatentPayload()
        val invalid = when {
            !signatureMatches -> "Only the audited $name source has a receiving-effect contract."
            latentPayload -> "$name cannot hide a Flow or function value in its emitted payload."
            else -> null
        }
        if (invalid != null) problem(
            "local-hot-flow-source:" + context.function.displayName() + ":" + call.source?.startOffset,
            call.source ?: context.function.source,
            invalid,
        )
        return sequence(receivers + arguments).copy(
            latent = LatentNetworkEffect(network = invalid?.let(Effect::Invalid) ?: Effect.Empty),
        )
    }

    /**
     * The ordinary Channel factory allocates local signaling storage and
     * launches no producer. Track only that receiving effect; this is not a
     * contract for arbitrary ReceiveChannel implementations or producer(...).
     * An undelivered-element callback may run later during cancellation, so
     * only a known empty callback is admitted by this narrow source rule.
     */
    private fun inferLocalChannelSource(
        call: FirFunctionCall,
        target: FirFunction,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val cache = IdentityHashMap<FirExpression, KotlinExpressionEffect>()
        fun effectOf(expression: FirExpression): KotlinExpressionEffect =
            cache.getOrPut(expression) { infer(expression, context) }
        val receivers = call.receiverExpressions().map(::effectOf)
        val arguments = call.argumentList.arguments.map(::effectOf)
        val byParameter = call.argumentEffectsByParameter(::effectOf)
        val signatureMatches = target.receiverParameter == null &&
            target.valueParameters.map { it.name.asString() } ==
                listOf("capacity", "onBufferOverflow", "onUndeliveredElement")
        val cleanupParameter = target.valueParameters.singleOrNull {
            it.name.asString() == "onUndeliveredElement"
        }
        val cleanupArgument = call.argumentForParameter("onUndeliveredElement")
        val cleanup = cleanupParameter?.let { byParameter[it]?.latent }
        val cleanupIsEmpty = cleanupArgument == null || cleanupArgument.isDefinitelyNullValue() ||
            cleanup?.let { it.network == Effect.Empty && it.returned == null } == true
        val hiddenInput = byParameter.any { (parameter, effect) ->
            parameter !== cleanupParameter && effect.latent != null
        }
        val invalid = when {
            !signatureMatches -> "Only the audited local Channel factory has a receiving-effect contract."
            !cleanupIsEmpty -> "The local Channel source requires an empty onUndeliveredElement callback effect."
            hiddenInput || call.hasDirectLatentPayload() ->
                "A local Channel cannot hide a Flow or function value in its emitted payload."
            else -> null
        }
        if (invalid != null) problem(
            "channel-source:" + context.function.displayName() + ":" + call.source?.startOffset,
            call.source ?: context.function.source,
            invalid,
        )
        return sequence(receivers + arguments).copy(
            latent = LatentNetworkEffect(network = invalid?.let(Effect::Invalid) ?: Effect.Empty),
        )
    }

    /** Reject latent element values until emitted-payload effects are modeled. */
    private fun FirFunctionCall.hasDirectLatentPayload(): Boolean =
        resolvedType.typeArguments.any { projection ->
            (projection as? ConeKotlinTypeProjection)?.type?.isBandwidthLatentValue(session) == true
        }

    /**
     * Read-only state wrappers and receiveAsFlow preserve known receiving
     * effects. Unknown channels are not silently declared network-free: their
     * transport/producer ownership needs a visible local source or contract.
     */
    private fun inferTrackedFlowWrapper(
        call: FirFunctionCall,
        target: FirFunction,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val receivers = call.receiverExpressions().map { infer(it, context) }
        val arguments = call.argumentList.arguments.map { infer(it, context) }
        val source = receivers.mapNotNull { it.latent }.singleOrNull()
        val name = target.symbol.callableId.callableName.asString()
        val mutableChannelReceiver = name == "receiveAsFlow" &&
            call.receiverExpressions().any { receiver ->
                (receiver.resolvedSymbol()?.flowStorageProperty())?.isVal == false
            }
        val invalid = when {
            target.receiverParameter == null || target.valueParameters.isNotEmpty() ||
                !target.returnTypeRef.coneType.isBandwidthFlow(session) ->
                "Only the audited $name receiver wrapper has a receiving-effect contract."
            mutableChannelReceiver || source == null || source.network.invalidMessage() != null ->
                if (name == "receiveAsFlow")
                    "Cannot infer receiveAsFlow on an untracked channel. Use a visible local Channel source or an explicit receiving-effect contract; track its producer separately."
                else "Cannot infer $name on an untracked Flow. Declare or infer the receiver collection effect."
            call.hasDirectLatentPayload() ->
                "$name cannot hide a Flow or function value in its emitted payload."
            else -> null
        }
        if (invalid != null) problem(
            "tracked-flow-wrapper:" + context.function.displayName() + ":" + call.source?.startOffset,
            call.source ?: context.function.source,
            invalid,
        )
        return sequence(receivers + arguments).copy(
            latent = invalid?.let { LatentNetworkEffect(network = Effect.Invalid(it)) } ?: source,
        )
    }

    /**
     * Builds the lazy effect of `flatMapMerge(concurrency = k)`. Transform
     * invocations repeat serially, while at most `k` returned inner flows are
     * collected concurrently. Escaping work still crosses the core repetition
     * boundary and therefore needs a self bound.
     */
    private fun inferFlatMapMerge(
        call: FirFunctionCall,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val callbackArgument = call.argumentForParameter("transform")
        val callback = callbackArgument?.visibleLambda()
        val receiverEffects = call.receiverExpressions().map { infer(it, context) }
        val nonCallbackArguments = call.argumentList.arguments
            .filter { it !== callbackArgument }
            .map { infer(it, context) }
        val evaluatedInputs = sequence(receiverEffects + nonCallbackArguments)
        val sourceFlow = receiverEffects.mapNotNull { it.latent }.singleOrNull()
        if (sourceFlow == null) {
            problem(
                key = "flat-map-merge-source:${context.function.displayName()}:" +
                    call.source?.startOffset,
                source = call.source ?: context.function.source,
                message = "Cannot infer the source Flow for flatMapMerge. Build it with a " +
                    "visible flow { ... }, asFlow(), or flatMapMerge expression in the same " +
                    "function.",
            )
        }

        val concurrency = call.argumentForParameter("concurrency")?.positiveConstantInt()
        if (concurrency == null) {
            problem(
                key = "flat-map-merge-concurrency:${context.function.displayName()}:" +
                    call.source?.startOffset,
                source = call.source ?: context.function.source,
                message = "flatMapMerge concurrency must be an explicit positive constant.",
            )
        }

        val transform = callback?.anonymousFunction?.let { function ->
            val summary = inferFunction(function)
            KotlinExpressionEffect(
                network = summary.network,
                latent = summary.returned,
            )
        }
        val innerFlow = transform?.latent
        if (innerFlow == null) {
            problem(
                key = "flat-map-merge-transform:${context.function.displayName()}:" +
                    call.source?.startOffset,
                source = call.source ?: context.function.source,
                message = "Cannot infer the Flow returned by flatMapMerge's transform. " +
                    "Return a Flow built with a visible flow { ... } expression.",
            )
        }

        if (sourceFlow == null || concurrency == null || transform == null || innerFlow == null) {
            return evaluatedInputs
        }
        val repeatedTransform = repeatNetworkEffect(
            effect = transform.network,
            key = "flat-map-merge-transform-repeat:${context.function.displayName()}:" +
                call.source?.startOffset,
            source = call.source ?: context.function.source,
            missingBoundMessage = "Network work escaping a flatMapMerge transform may " +
                "overlap across an unknown number of values. Use a self bound for every " +
                "download.",
        )
        val repeatedInnerFlow = boundedRepeatedNetworkEffect(
            effect = innerFlow.network,
            maxConcurrentBodies = concurrency,
            key = "flat-map-merge-inner-repeat:${context.function.displayName()}:" +
                call.source?.startOffset,
            source = call.source ?: context.function.source,
            missingBoundMessage = "Network work escaping a flatMapMerge inner Flow may " +
                "overlap across an unknown number of values. Use a self bound for every " +
                "download.",
        )
        return evaluatedInputs.copy(
            latent = LatentNetworkEffect(
                network = sourceFlow.network
                    .parallel(repeatedTransform)
                    .parallel(repeatedInnerFlow),
            ),
        )
    }

    /**
     * A flatMapConcat callback runs and its returned Flow is collected before
     * the next callback begins. Only escaped work accumulates across elements.
     */
    private fun inferFlatMapConcat(
        call: FirFunctionCall,
        target: FirFunction,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val cache = IdentityHashMap<FirExpression, KotlinExpressionEffect>()
        fun effectOf(expression: FirExpression): KotlinExpressionEffect =
            cache.getOrPut(expression) { infer(expression, context) }
        val receivers = call.receiverExpressions().map(::effectOf)
        val inputs = sequence(receivers + call.argumentList.arguments.map(::effectOf))
        val source = receivers.mapNotNull { it.latent }.singleOrNull()
        val parameter = target.valueParameters.singleOrNull {
            it.returnTypeRef.coneType.isSomeFunctionType(session)
        }
        val transform = parameter?.let { call.argumentEffectsByParameter(::effectOf)[it]?.latent }
        val inner = transform?.returned
        val invalid = when {
            source == null || source.network.invalidMessage() != null ->
                "Cannot infer flatMapConcat on an untracked source Flow. " +
                    "Declare or infer its collection effect."
            transform == null || transform.network.invalidMessage() != null ||
                inner == null || inner.network.invalidMessage() != null ->
                "Cannot infer the Flow returned by flatMapConcat's transform. " +
                    "Use a visible transform returning a tracked Flow."
            else -> null
        }
        if (invalid != null) {
            problem("flat-map-concat:" + context.function.displayName() + ":" + call.source?.startOffset,
                call.source ?: context.function.source, invalid)
        }
        val repeated = if (invalid == null) repeatNetworkEffect(
            effect = transform!!.network.then(inner!!.network),
            key = "flat-map-concat-repeat:" + context.function.displayName() + ":" + call.source?.startOffset,
            source = call.source ?: context.function.source,
            missingBoundMessage = "Escaping network work in flatMapConcat may overlap " +
                "across an unknown number of elements. Use a self bound for every download.",
        ) else Effect.Invalid(invalid)
        return inputs.copy(latent = LatentNetworkEffect(
            network = invalid?.let(Effect::Invalid) ?: source!!.network.parallel(repeated),
        ))
    }

    /** toList is a terminal collector with no callback or latent producer result. */
    private fun inferFlowToList(
        call: FirFunctionCall,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val receivers = call.receiverExpressions().map { infer(it, context) }
        val inputs = sequence(receivers + call.argumentList.arguments.map { infer(it, context) })
        val source = receivers.mapNotNull { it.latent }.singleOrNull()
        val invalid = if (source == null || source.network.invalidMessage() != null)
            "Cannot infer toList on an untracked Flow. Declare or infer its collection effect."
            else null
        if (invalid != null) {
            problem("flow-to-list:" + context.function.displayName() + ":" + call.source?.startOffset,
                call.source ?: context.function.source, invalid)
        }
        return inputs.then(KotlinExpressionEffect(
            network = invalid?.let(Effect::Invalid) ?: source!!.network,
        ))
    }

    /**
     * Instantiates an audited cold Flow operator contract. Construction only
     * evaluates the inputs; collection overlaps the upstream with at most k
     * completing callbacks. Escaped callback work has unknown repetition.
     */
    private fun inferConcurrentMap(
        call: FirFunctionCall,
        contract: ConcurrentMapContract,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val cached = IdentityHashMap<FirExpression, KotlinExpressionEffect>()
        fun effectOf(expression: FirExpression): KotlinExpressionEffect =
            cached.getOrPut(expression) { infer(expression, context) }
        val receiverEffects = call.receiverExpressions().map(::effectOf)
        val evaluatedInputs = sequence(
            receiverEffects + call.argumentList.arguments.map(::effectOf),
        )
        val sourceFlow = receiverEffects.mapNotNull { it.latent }.singleOrNull()
        val callbackArgument = call.argumentForParameter(contract.callbackParameter)
        val callback = callbackArgument?.let(::effectOf)?.latent
        val concurrency = call.argumentForParameter(contract.concurrencyParameter)
            ?.positiveConstantInt()
        fun reject(suffix: String, message: String) {
            problem(
                key = "concurrent-map-" + suffix + ":" + context.function.displayName() +
                    ":" + call.source?.startOffset,
                source = call.source ?: context.function.source,
                message = message,
            )
        }
        if (sourceFlow == null || sourceFlow.network.invalidMessage() != null) {
            reject("source", "Cannot infer the source Flow for @BandwidthConcurrentMap; " +
                "declare or infer its collection effect.")
        }
        if (concurrency == null) {
            reject("concurrency", "@BandwidthConcurrentMap concurrency must be an explicit " +
                "positive constant.")
        }
        if (callback == null || callback.network.invalidMessage() != null) {
            reject("callback", "Cannot infer the @BandwidthConcurrentMap callback effect; " +
                "supply a tracked callback explicitly.")
        }
        val hiddenLatentArgument = call.argumentEffectsByParameter(::effectOf).any { (parameter, effect) ->
            parameter.name.asString() != contract.callbackParameter && effect.latent != null
        }
        if (hiddenLatentArgument) {
            reject("extra-input", "@BandwidthConcurrentMap does not support additional latent " +
                "Flow or function arguments, including values hidden behind generic types.")
        }
        if (callback?.returned != null) {
            reject("returned-value", "@BandwidthConcurrentMap callbacks returning latent " +
                "function or Flow values are unsupported; return an ordinary value.")
        }
        if (sourceFlow == null || sourceFlow.network.invalidMessage() != null ||
            concurrency == null || callback == null ||
            callback.network.invalidMessage() != null || callback.returned != null ||
            hiddenLatentArgument
        ) {
            return evaluatedInputs.copy(latent = untrackedFlowEffect())
        }
        val repeatedCallback = boundedRepeatedNetworkEffect(
            effect = callback.network,
            maxConcurrentBodies = concurrency,
            key = "concurrent-map-callback-repeat:" + context.function.displayName() +
                ":" + call.source?.startOffset,
            source = call.source ?: context.function.source,
            missingBoundMessage = "Network work escaping a @BandwidthConcurrentMap callback " +
                "may overlap across an unknown number of elements. Use a self bound " +
                "for every download.",
        )
        return evaluatedInputs.copy(
            latent = LatentNetworkEffect(
                network = sourceFlow.network.parallel(repeatedCallback),
            ),
        )
    }

    /**
     * Preserves the effect of one `Semaphore.withPermit { ... }` invocation and
     * records the shared capacity for a surrounding repetition boundary.
     *
     * Only an immutable top-level semaphore with a compiler-known positive
     * capacity contributes a bound. Other semaphore forms remain transparent.
     * Work that escapes the block is not protected after the permit is released,
     * so it receives no bound from this construct. A surrounding repeated spawn
     * therefore sees `k` for protected work and an unknown bound for escaped work.
     */
    private fun inferSemaphoreWithPermit(
        call: FirFunctionCall,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val callback = call.visibleLambdaArgument()
        val evaluatedInputs = sequence(
            call.receiverExpressions().map { infer(it, context) } +
                call.argumentList.arguments
                    .filter { it.visibleLambda() !== callback }
                    .map { infer(it, context) },
        )
        val body = callback?.anonymousFunction?.let { inferFunction(it) }
        if (body == null) {
            problem(
                key = "semaphore-block:${context.function.displayName()}:" +
                    call.source?.startOffset,
                source = call.source ?: context.function.source,
                message = "Cannot infer Semaphore.withPermit with a non-visible block. " +
                    "Keep the withPermit lambda visible.",
            )
            return evaluatedInputs
        }

        val bodyNetwork = body.network
        if (bodyNetwork == Effect.Empty) {
            return evaluatedInputs.copy(latent = body.returned)
        }

        val capacity = call.sharedTopLevelSemaphoreCapacity()
        if (capacity == null) {
            return evaluatedInputs.then(
                KotlinExpressionEffect(
                    network = bodyNetwork,
                    latent = body.returned,
                ),
            )
        }

        val bounded = bodyNetwork.completingOnly()
            .withConcurrentInvocationBound(capacity)
            .then(bodyNetwork.escapingOnly())
        return evaluatedInputs.then(
            KotlinExpressionEffect(
                network = bounded,
                latent = body.returned,
            ),
        )
    }

    /**
     * Room 2.8.4 createFlow maps local invalidation signals to one completed
     * query callback at a time. The callback, including any escaping work, is
     * repeated lazily per collection; it is never silently assumed pure.
     */
    private fun inferRoomQueryFlow(
        call: FirFunctionCall,
        target: FirFunction,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val cache = IdentityHashMap<FirExpression, KotlinExpressionEffect>()
        fun effectOf(expression: FirExpression): KotlinExpressionEffect =
            cache.getOrPut(expression) { infer(expression, context) }
        val receivers = call.receiverExpressions().map(::effectOf)
        val inputs = sequence(receivers + call.argumentList.arguments.map(::effectOf))
        val arguments = call.argumentEffectsByParameter(::effectOf)
        val callback = arguments.entries.singleOrNull { it.key.name.asString() == "block" }?.value?.latent
        val signatureMatches = target.receiverParameter == null &&
            target.valueParameters.map { it.name.asString() } == listOf("db", "inTransaction", "tableNames", "block") &&
            target.returnTypeRef.coneType.isBandwidthFlow(session) &&
            target.valueParameters.last().returnTypeRef.coneType.isSomeFunctionType(session)
        val hiddenLatent = receivers.any { it.latent != null } || arguments.any { (parameter, effect) ->
            parameter.name.asString() != "block" && effect.latent != null
        }
        val invalid = when {
            !signatureMatches -> "Only the audited Room createFlow query overload has an effect contract."
            callback == null || callback.network.invalidMessage() != null ->
                "Cannot infer the Room createFlow query callback effect; supply a tracked callback."
            callback.returned != null ->
                "Room createFlow cannot hide a latent Flow or function in its emitted query result."
            hiddenLatent -> "Room createFlow does not support additional latent Flow or function inputs."
            else -> null
        }
        if (invalid != null) {
            problem("room-query-flow:" + context.function.displayName() + ":" + call.source?.startOffset,
                call.source ?: context.function.source, invalid)
        }
        val query = if (invalid == null) repeatNetworkEffect(
            effect = callback!!.network,
            key = "room-query-repeat:" + context.function.displayName() + ":" + call.source?.startOffset,
            source = call.source ?: context.function.source,
            missingBoundMessage = "Escaping network work in a Room query callback may overlap " +
                "across invalidations. Use a self bound for every download.",
        ) else Effect.Invalid(invalid)
        return inputs.copy(latent = LatentNetworkEffect(network = query))
    }

    /**
     * These actions run lazily at most once per collection. onStart precedes
     * upstream collection; recovery/completion follows it. Sequential
     * composition retains work escaping either action or the producer.
     */
    private fun inferFlowCompletionOperator(
        call: FirFunctionCall,
        target: FirFunction,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val receivers = call.receiverExpressions().map { infer(it, context) }
        val arguments = call.argumentList.arguments.map { infer(it, context) }
        val producer = receivers.mapNotNull { it.latent }.singleOrNull()
        val callback = arguments.singleOrNull()?.latent
        val invalid = when {
            producer == null || producer.network.invalidMessage() != null ->
                "Cannot infer a recovery/completion operator on an untracked Flow. " +
                    "Declare or infer the source collection effect."
            callback == null || callback.network.invalidMessage() != null ->
                "Cannot infer the Flow recovery/completion callback effect. " +
                    "Use a visible callback or an explicit effect contract."
            else -> null
        }
        if (invalid != null) {
            problem("flow-completion:" + context.function.displayName() + ":" + call.source?.startOffset,
                call.source ?: context.function.source, invalid)
        }
        return sequence(receivers + arguments).copy(
            latent = LatentNetworkEffect(network = invalid?.let(Effect::Invalid)
                ?: if (target.symbol.callableId.asSingleFqName().asString() == "kotlinx.coroutines.flow.onStart")
                    callback!!.network.then(producer!!.network)
                else producer!!.network.then(callback!!.network)),
        )
    }

    /**
     * These cold operators invoke one callback at a time for upstream elements.
     * Callback work starts only when the returned Flow is collected. An upstream
     * buffer or dispatcher may keep its producer active during a callback, so
     * producer and repeated consumer effects are conservatively parallel.
     */
    private fun inferFlowElementOperator(
        call: FirFunctionCall,
        target: FirFunction,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val cache = IdentityHashMap<FirExpression, KotlinExpressionEffect>()
        fun effectOf(expression: FirExpression): KotlinExpressionEffect =
            cache.getOrPut(expression) { infer(expression, context) }
        val receivers = call.receiverExpressions().map(::effectOf)
        val arguments = call.argumentList.arguments.map(::effectOf)
        val producer = receivers.mapNotNull { it.latent }.singleOrNull()
        val parameter = target.valueParameters.singleOrNull {
            it.returnTypeRef.coneType.isSomeFunctionType(session)
        }
        val callback = parameter?.let { call.argumentEffectsByParameter(::effectOf)[it]?.latent }
        val invalid = when {
            producer == null || producer.network.invalidMessage() != null ->
                "Cannot infer a per-element operator on an untracked Flow. " +
                    "Declare or infer the source collection effect."
            callback == null || callback.network.invalidMessage() != null ->
                "Cannot infer the Flow per-element callback effect. " +
                    "Use a visible callback or an explicit effect contract."
            target.symbol.callableId.asSingleFqName().asString() == "kotlinx.coroutines.flow.map" &&
                callback.returned != null ->
                "Flow map cannot hide a Flow or function value in its emitted payload. " +
                    "Collect the returned Flow with a modeled flattening operator."
            else -> null
        }
        if (invalid != null) {
            problem("flow-element:" + context.function.displayName() + ":" + call.source?.startOffset,
                call.source ?: context.function.source, invalid)
        }
        val consumer = if (invalid == null) repeatNetworkEffect(
            effect = callback!!.network,
            key = "flow-element-repeat:" + context.function.displayName() + ":" + call.source?.startOffset,
            source = call.source ?: context.function.source,
            missingBoundMessage = "Escaping network work in a Flow per-element callback " +
                "may overlap across an unknown number of elements. Use a self bound " +
                "for every download.",
        ) else Effect.Invalid(invalid)
        return sequence(receivers + arguments).copy(
            latent = LatentNetworkEffect(network = invalid?.let(Effect::Invalid)
                ?: producer!!.network.parallel(consumer)),
        )
    }

    /** `emitAll(Flow)` collects that producer; channel ownership needs another contract. */
    private fun inferFlowEmitAll(
        call: FirFunctionCall,
        target: FirFunction,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val cache = IdentityHashMap<FirExpression, KotlinExpressionEffect>()
        fun effectOf(expression: FirExpression): KotlinExpressionEffect =
            cache.getOrPut(expression) { infer(expression, context) }
        val inputs = sequence(call.receiverExpressions().map(::effectOf) +
            call.argumentList.arguments.map(::effectOf))
        val parameter = target.valueParameters.singleOrNull {
            it.returnTypeRef.coneType.isBandwidthFlow(session)
        }
        val producer = parameter?.let { call.argumentEffectsByParameter(::effectOf)[it]?.latent }
        val invalid = when {
            parameter == null -> "Only the Flow overload of emitAll has a collection-effect contract; " +
                "channel producers need an explicit lifetime contract."
            producer == null || producer.network.invalidMessage() != null ->
                "Cannot infer emitAll on an untracked Flow. Declare or infer its collection effect."
            else -> null
        }
        if (invalid != null) {
            problem("flow-emit-all:" + context.function.displayName() + ":" + call.source?.startOffset,
                call.source ?: context.function.source, invalid)
        }
        return inputs.then(KotlinExpressionEffect(network = invalid?.let(Effect::Invalid) ?: producer!!.network))
    }

    /**
     * Materializes the source Flow and its sequentially invoked collector.
     * Upstream producers can run concurrently (for example, behind a buffer),
     * so their effect is conservatively parallel with the consumer effect.
     * Completing consumer work does not accumulate across elements; escaping
     * work crosses the ordinary repetition boundary and needs a self bound.
     * collectLatest has the same conservative bound: its audited transform
     * implementation cancels AND joins the prior action before starting the
     * next, so completing actions do not overlap each other. Independent work
     * escaping an action still crosses the usual repetition/self-bound check.
     */
    private fun inferFlowCollect(
        call: FirFunctionCall,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val receiverEffects = call.receiverExpressions().map { infer(it, context) }
        val argumentEffects = call.argumentList.arguments.map { infer(it, context) }
        val evaluatedInputs = sequence(receiverEffects + argumentEffects)
        val flow = receiverEffects.mapNotNull { it.latent }.singleOrNull()
        if (flow == null || flow.network.invalidMessage() != null) {
            problem(
                key = "flow-collect-source:${context.function.displayName()}:" +
                    call.source?.startOffset,
                source = call.source ?: context.function.source,
                message = "Cannot infer collect on an untracked Flow. Build the Flow with a " +
                    "visible flow { ... }, asFlow(), or flatMapMerge expression in the same " +
                    "function.",
            )
        }
        val consumer = argumentEffects.mapIndexed { index, argument ->
            val collector = argument.latent
            if (collector == null || collector.network.invalidMessage() != null) {
                val message = "Cannot infer the Flow collector callback effect. " +
                    "Use a visible callback or a value with an explicit effect contract."
                problem(
                    key = "flow-collector-callback:${context.function.displayName()}:" +
                        "${call.source?.startOffset}:$index",
                    source = call.source ?: context.function.source,
                    message = message,
                )
                Effect.Invalid(message)
            } else {
                repeatNetworkEffect(
                    effect = collector.network,
                    key = "flow-collector-repeat:${context.function.displayName()}:" +
                        "${call.source?.startOffset}:$index",
                    source = call.source ?: context.function.source,
                    missingBoundMessage = "Escaping network work in a Flow collector callback " +
                        "may overlap across an unknown number of elements. Use a self bound " +
                        "for every download.",
                )
            }
        }.fold(Effect.Empty as Effect) { result, effect -> result.parallel(effect) }
        val producer = flow?.network ?: Effect.Invalid(
            "Cannot infer collection effect of an untracked Flow.",
        )
        return evaluatedInputs.then(
            KotlinExpressionEffect(network = producer.parallel(consumer)),
        )
    }

    /**
     * `launchIn(scope)` starts one collection and returns its Job immediately.
     * Per-element work from operators such as onEach is already part of the
     * source's latent effect; materialize that whole effect as escaping work.
     *
     * Receiver construction and scope evaluation remain eager, so neither is
     * reclassified as child work. The core sequential rule then overlaps this
     * child with the continuation, preserving its internal sequential phases.
     * An enclosing loop/handler applies repetition and requires shared bounds.
     * We do not infer child ownership from a passed scope value: in particular,
     * a launchIn on an external scope must survive an enclosing coroutineScope.
     */
    private fun inferFlowLaunchIn(
        call: FirFunctionCall,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val receiverEffects = call.receiverExpressions().map { infer(it, context) }
        val evaluatedInputs = sequence(
            receiverEffects + call.argumentList.arguments.map { infer(it, context) },
        )
        val producer = receiverEffects.mapNotNull { it.latent }.singleOrNull()
        val network = if (producer == null || producer.network.invalidMessage() != null) {
            val message = "Cannot infer launchIn on an untracked Flow. " +
                "Declare or infer the source collection effect before starting it."
            problem(
                key = "flow-launch-in-source:${context.function.displayName()}:" +
                    call.source?.startOffset,
                source = call.source ?: context.function.source,
                message = message,
            )
            Effect.Invalid(message)
        } else {
            producer.network.withLifetime(DownloadLifetime.MAY_OUTLIVE_CALL)
        }
        return evaluatedInputs.then(KotlinExpressionEffect(network = network))
            .copy(latent = null)
    }

    /**
     * A launch/async on an unproven receiver is one spawned child that may
     * outlive this expression. Enclosing loops and callbacks, rather than the
     * builder itself, are responsible for applying repetition.
     */
    private fun inferUnstructuredCoroutineBuilder(
        call: FirFunctionCall,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val resolved = inferCoroutineBuilder(call, context)
        val child = resolved.body ?: return resolved.inputs
        return resolved.inputs.then(
            KotlinExpressionEffect(
                network = child.network.withLifetime(DownloadLifetime.MAY_OUTLIVE_CALL),
            ),
        )
    }

    /** Resolves block evaluation separately from the callback work it denotes. */
    private fun inferCoroutineBuilder(
        call: FirFunctionCall,
        context: KotlinEffectContext,
    ): CoroutineBuilderEffect {
        val blockArgument = call.argumentForParameter("block")
        if (blockArgument == null) {
            problem(
                key = "coroutine-block:${context.function.displayName()}:${call.source?.startOffset}",
                source = call.source ?: context.function.source,
                message = "Cannot infer the network effect of this coroutine block. " +
                    "Provide a visible lambda or an effect-annotated callback.",
            )
            return CoroutineBuilderEffect(
                inputs = sequence(call.receiverExpressions().map { infer(it, context) }),
                body = null,
            )
        }

        val visibleLambda = blockArgument.visibleLambda()
        val blockValue = if (visibleLambda == null) {
            infer(blockArgument, context)
        } else {
            KotlinExpressionEffect()
        }
        val inputs = sequence(
            call.receiverExpressions().map { infer(it, context) } +
                call.argumentList.arguments
                    .filter { it !== blockArgument }
                    .map { infer(it, context) } +
                KotlinExpressionEffect(
                    network = blockValue.network,
                ),
        )
        val body = visibleLambda?.let { inferCoroutineChild(it, context) }
            ?: blockValue.latent?.let { latent ->
                KotlinExpressionEffect(
                    network = latent.network,
                    latent = latent.returned,
                )
            }
        if (body == null) {
            problem(
                key = "coroutine-block:${context.function.displayName()}:${call.source?.startOffset}",
                source = call.source ?: context.function.source,
                message = "Cannot infer the network effect of this coroutine block. " +
                    "Provide a visible lambda or an effect-annotated callback.",
            )
        }
        return CoroutineBuilderEffect(inputs = inputs, body = body)
    }

    /**
     * Evaluates receivers and arguments, then invokes every latent callback once
     * in argument order.
     */
    private fun inferSingleCallbackCall(
        call: FirFunctionCall,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val cachedEffects = IdentityHashMap<FirExpression, KotlinExpressionEffect>()
        /** Infers [expression] at most once for this call site. */
        fun effectOf(expression: FirExpression): KotlinExpressionEffect =
            cachedEffects.getOrPut(expression) { infer(expression, context) }

        val receiverEffects = sequence(call.receiverExpressions().map(::effectOf))
        val argumentEffects = call.argumentList.arguments.map(::effectOf)
        val evaluatedArguments = sequence(argumentEffects)
        val invokedCallbacks = argumentEffects.fold(KotlinExpressionEffect()) { effect, argument ->
            effect.then(
                KotlinExpressionEffect(
                    network = argument.latent?.network ?: Effect.Empty,
                ),
            )
        }
        return receiverEffects.then(evaluatedArguments).then(invokedCallbacks)
    }

    /** Infers a callback body once, then applies the core repetition rule. */
    private fun inferRepeatedCallbackCall(
        call: FirFunctionCall,
        target: FirFunction,
        context: KotlinEffectContext,
        policy: RepeatedCallbackPolicy,
    ): KotlinExpressionEffect {
        val cachedEffects = IdentityHashMap<FirExpression, KotlinExpressionEffect>()
        /** Infers [expression] at most once for this call site. */
        fun effectOf(expression: FirExpression): KotlinExpressionEffect =
            cachedEffects.getOrPut(expression) { infer(expression, context) }

        val receiverEffects = sequence(call.receiverExpressions().map(::effectOf))
        val argumentEffects = call.argumentEffectsByParameter(::effectOf)
        val evaluatedArguments = sequence(call.argumentList.arguments.map(::effectOf))
        val evaluatedInputs = receiverEffects.then(evaluatedArguments)

        val callbackBody = argumentEffects
            .filter { (parameter, argument) -> parameter.isRepeatedCallback(argument) }
            .entries
            .fold(KotlinExpressionEffect()) { effect, (parameter, argument) ->
                val latent = argument.latent
                if (latent == null) {
                    problem(
                        key = "repeated-callback:${context.function.displayName()}:" +
                            "${call.source?.startOffset}:${parameter.name}",
                        source = call.source ?: context.function.source,
                        message = "Cannot infer the repeated callback effect for " +
                            "${target.displayName()}. Keep the callback visible or annotate " +
                            "its effect.",
                    )
                    effect
                } else {
                    effect.then(
                        KotlinExpressionEffect(
                            network = latent.network,
                            latent = latent.returned,
                        ),
                    )
                }
            }
        val bodyNetwork = callbackBody.network
        // These APIs return an ordinary result (a collection or Unit), not
        // the callback value used to construct it. Do not leak input metadata.
        if (bodyNetwork == Effect.Empty) return evaluatedInputs.copy(latent = null)
        val invocationEffect = repeatedCallbackNetwork(
            bodyNetwork = bodyNetwork,
            callbackName = target.displayName(),
            key = "repeated-network:${context.function.displayName()}:" +
                call.source?.startOffset,
            source = call.source ?: context.function.source,
            policy = policy,
        )
        return evaluatedInputs.then(
            KotlinExpressionEffect(network = invocationEffect),
        )
    }

    /** Applies one recognized callback invocation policy to its body effect. */
    private fun repeatedCallbackNetwork(
        bodyNetwork: Effect,
        callbackName: String,
        key: String,
        source: KtSourceElement?,
        policy: RepeatedCallbackPolicy,
    ): Effect {
        if (bodyNetwork == Effect.Empty) return Effect.Empty
        val repeatedBody = if (policy.invocationsMayOverlap) {
            bodyNetwork.withLifetime(DownloadLifetime.MAY_OUTLIVE_CALL)
        } else {
            bodyNetwork
        }
        val repeated = repeatNetworkEffect(
            effect = repeatedBody,
            key = key,
            source = source,
            missingBoundMessage = if (policy.invocationsMayOverlap) {
                "Network work in concurrent repeated callback $callbackName may overlap " +
                    "across an unknown number of invocations. Use a self bound for every " +
                    "download."
            } else {
                "Escaping network work in repeated callback $callbackName may overlap " +
                    "across an unknown number of invocations. Use a self bound for every " +
                    "download."
            },
        )
        if (policy == RepeatedCallbackPolicy.RETAINED_CONCURRENT) {
            reportInfo("RAW_CALLBACK_EFFECT callback=$callbackName key=$key body=${bodyNetwork.render()} repeated=${repeated.render()}")
        }
        return if (policy.mayOutliveCall) {
            repeated.withLifetime(DownloadLifetime.MAY_OUTLIVE_CALL)
        } else {
            repeated
        }
    }

    /**
     * Divides a structured coroutine [block] into overlap phases, recognizing
     * direct child starts and handle-specific waits.
     */
    private fun inferCoroutineStatements(
        block: FirBlock,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val phases = CoroutinePhaseState<FirBasedSymbol<*>>()
        val previousPhases = structuredPhases.put(context, phases)
        try {
            block.statements.forEach { statement ->
                val repeatedChildren = statement.repeatedStructuredChildren()
                if (repeatedChildren != null) {
                    val resolved = inferRepeatedCoroutineChildren(repeatedChildren, context)
                    phases.recordStatement(resolved.inputs)
                    resolved.body?.let {
                        phases.addChild(handle = repeatedChildren.handle, effect = it)
                    }
                    // The callback returns immediately after starting its child.
                    // All mapped children may therefore overlap preparation in
                    // later iterations; only the child group remains tracked.
                    if (resolved.preparation.network != Effect.Empty) {
                        phases.recordStatement(resolved.preparation)
                    }
                    return@forEach
                }

                val child = statement.structuredChild()
                if (child != null) {
                    val resolved = inferCoroutineBuilder(child.call, context)
                    phases.recordStatement(resolved.inputs)
                    resolved.body?.let { phases.addChild(handle = child.handle, effect = it) }
                    return@forEach
                }

                val waitedHandle = statement.coroutineWaitedHandle()
                val waitedProperty = waitedHandle?.fir as? FirProperty
                if (waitedHandle != null && waitedProperty?.isVal == true &&
                    waitedProperty.isLexicalLocalVariable() && phases.synchronize(waitedHandle)
                ) {
                    return@forEach
                }

                inferCoroutineStatement(statement, context, phases)
            }

            return phases.finish()
        } finally {
            if (previousPhases == null) structuredPhases.remove(context)
            else structuredPhases[context] = previousPhases
        }
    }

    /**
     * Splits unconditional eager expression evaluation at a tracked awaitAll.
     * Inputs are recorded before the parent invocation, whose normal visitor
     * reuses their values with only their already-recorded eager effects cleared.
     * This preserves callback effects and evaluation order without exposing the
     * mutable phase state to inference of branches or deferred function bodies.
     */
    private fun inferCoroutineStatement(
        statement: FirElement,
        context: KotlinEffectContext,
        phases: CoroutinePhaseState<FirBasedSymbol<*>>,
    ) {
        fun trackedAwait(element: FirElement): List<FirBasedSymbol<*>>? {
            val call = element as? FirFunctionCall ?: return null
            if (call.resolvedFunction()?.isAwaitAll() != true) return null
            val arguments = call.argumentList.arguments.flatMap { it.flattenVarargArguments() }
            val handles = if (arguments.isEmpty()) {
                listOfNotNull(call.coroutineWaitedHandle())
            } else {
                arguments.map { it.directCoroutineHandle() ?: return null }
            }
            if (handles.isEmpty()) return null
            // A mutable collection may have been replaced since its children
            // started. Only an immutable local binding establishes this link.
            if (handles.any { handle ->
                    val property = handle.fir as? FirProperty
                    property == null || !property.isLexicalLocalVariable() || !property.isVal ||
                        !phases.hasActiveChild(handle)
                }
            ) return null
            return handles.distinct()
        }

        fun containsTrackedAwait(element: FirElement): Boolean =
            trackedAwait(element) != null ||
                element.eagerCoroutineInputs().any(::containsTrackedAwait)

        if (!containsTrackedAwait(statement)) {
            phases.recordStatement(infer(statement, context))
            return
        }

        val previous = IdentityHashMap<FirElement, KotlinExpressionEffect?>()
        fun evaluate(element: FirElement) {
            if (previous.containsKey(element)) return
            if (containsTrackedAwait(element)) {
                element.eagerCoroutineInputs().forEach(::evaluate)
            }
            val handles = trackedAwait(element)
            val value = if (handles != null) {
                handles.forEach { check(phases.synchronize(it)) }
                KotlinExpressionEffect()
            } else {
                infer(element, context).also(phases::recordStatement)
            }
            previous[element] = context.evaluatedExpressions.put(
                element,
                value.copy(network = Effect.Empty),
            )
        }

        try {
            evaluate(statement)
        } finally {
            previous.forEach { (element, value) ->
                if (value == null) context.evaluatedExpressions.remove(element)
                else context.evaluatedExpressions[element] = value
            }
        }
    }

    /**
     * Only unconditional eager inputs, in source evaluation order. Control-flow
     * nodes, safe calls, and function/coroutine bodies stay atomic: a wait in one
     * of those bodies cannot synchronize this statement's surrounding scope.
     */
    private fun FirElement.eagerCoroutineInputs(): List<FirElement> = when (this) {
        is FirProperty -> listOfNotNull(initializer)
        is FirReturnExpression -> listOf(result)
        is FirVariableAssignment -> {
            val access = lValue as? FirPropertyAccessExpression
            val target = access?.resolvedSymbol()?.fir as? FirProperty
            if (target?.isLexicalLocalVariable() == true && target.delegate == null &&
                access.receiverExpressions().isEmpty()
            ) {
                listOf(rValue)
            } else {
                emptyList()
            }
        }
        is FirFunctionCall -> receiverExpressions() + argumentList.arguments
        is FirPropertyAccessExpression -> receiverExpressions()
        is FirCallableReferenceAccess -> receiverExpressions()
        is FirTypeOperatorCall -> argumentList.arguments
        is FirVarargArgumentsExpression -> arguments
        is FirWrappedArgumentExpression -> listOf(expression)
        is FirNamedArgumentExpression -> listOf(expression)
        is FirSpreadArgumentExpression -> listOf(expression)
        is FirWrappedExpression -> listOf(expression)
        else -> emptyList()
    }

    /**
     * Unwraps an eager collection `map` whose callback runs ordinary statements
     * and returns one visible structured child. mapNotNull may skip an element
     * via an early null return; alternate returned jobs remain excluded.
     */
    private fun FirElement.repeatedStructuredChildren(): RepeatedStructuredChildren? {
        functionTypeConversionOperand()?.let { return it.repeatedStructuredChildren() }
        return when (this) {
            is FirFunctionCall -> {
                if (resolvedFunction()?.isEagerCollectionMap() != true) return null
                val callback = visibleLambdaArgument()?.anonymousFunction ?: return null
                val body = callback.body ?: return null
                val allowNullReturns = resolvedFunction()?.symbol?.callableId
                    ?.asSingleFqName()?.asString() == "kotlin.collections.mapNotNull"
                val returned = body.returnedMappedChild(callback, allowNullReturns) ?: return null
                RepeatedStructuredChildren(
                    handle = null,
                    mapCall = this,
                    preparation = returned.preparation,
                    builderCall = returned.call,
                )
            }
            is FirProperty -> initializer
                ?.repeatedStructuredChildren()
                ?.copy(handle = symbol)
            is FirWrappedArgumentExpression -> expression.repeatedStructuredChildren()
            is FirNamedArgumentExpression -> expression.repeatedStructuredChildren()
            is FirSpreadArgumentExpression -> expression.repeatedStructuredChildren()
            is FirWrappedExpression -> expression.repeatedStructuredChildren()
            is FirReturnExpression -> result.repeatedStructuredChildren()
            else -> null
        }
    }

    /**
     * Follows the value produced by a callback block, retaining preceding work.
     * FIR can wrap a destructured lambda's original body in a returned block;
     * these ordinary blocks introduce no coroutine synchronization boundary.
     */
    private fun FirElement.returnedMappedChild(
        callback: FirFunction,
        allowNullReturns: Boolean = false,
    ): ReturnedMappedChild? {
        functionTypeConversionOperand()?.let { return it.returnedMappedChild(callback, allowNullReturns) }
        return when (this) {
            is FirBlock -> {
                val result = statements.lastOrNull()?.returnedMappedChild(callback, allowNullReturns)
                    ?: return null
                val preparation = statements.dropLast(1)
                if (preparation.any {
                        if (allowNullReturns) it.hasUnsafeNullableMapPreparation(callback)
                        else it.hasReturnTo(callback)
                    }
                ) return null
                result.copy(preparation = preparation + result.preparation)
            }
            is FirReturnExpression -> if (target.labeledElement === callback) {
                result.returnedMappedChild(callback, allowNullReturns)
            } else {
                null
            }
            is FirFunctionCall -> structuredChild()
                ?.takeUnless { hasReturnTo(callback) }
                ?.let { ReturnedMappedChild(emptyList(), it.call) }
            is FirWrappedArgumentExpression -> expression.returnedMappedChild(callback, allowNullReturns)
            is FirNamedArgumentExpression -> expression.returnedMappedChild(callback, allowNullReturns)
            is FirSpreadArgumentExpression -> expression.returnedMappedChild(callback, allowNullReturns)
            is FirWrappedExpression -> expression.returnedMappedChild(callback, allowNullReturns)
            else -> null
        }
    }

    /**
     * mapNotNull may skip an element before its final child starts. Other child
     * returns and branch starts would require a separate returned-job proof.
     */
    private fun FirElement.hasUnsafeNullableMapPreparation(callback: FirFunction): Boolean {
        var unsafe = false
        accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (!unsafe) element.acceptChildren(this)
            }

            override fun visitReturnExpression(returnExpression: FirReturnExpression) {
                if (returnExpression.target.labeledElement === callback &&
                    !returnExpression.result.isDefinitelyNullValue()
                ) unsafe = true
                else visitElement(returnExpression)
            }

            override fun visitFunctionCall(functionCall: FirFunctionCall) {
                if (functionCall.resolvedFunction()?.isCoroutineBuilder() == true) unsafe = true
                else visitElement(functionCall)
            }
        })
        return unsafe
    }

    /** Finds an alternate return of this callback, including inside inline calls. */
    private fun FirElement.hasReturnTo(function: FirFunction): Boolean {
        var found = false
        accept(object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (!found) element.acceptChildren(this)
            }

            override fun visitReturnExpression(returnExpression: FirReturnExpression) {
                if (returnExpression.target.labeledElement === function) found = true
                else visitElement(returnExpression)
            }
        })
        return found
    }

    /**
     * Infers a visible coroutine [lambda] as structured statements, with a
     * latent-summary fallback when no block is available.
     */
    private fun inferCoroutineChild(
        lambda: FirAnonymousFunctionExpression,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val body = lambda.anonymousFunction.body
        return body?.let { inferCoroutineStatements(it, context) }
            ?: KotlinExpressionEffect(
                network = infer(lambda, context).latent?.network ?: Effect.Empty,
            )
    }

    /**
     * Unwraps this statement as a proven structured coroutine child, retaining
     * a local property symbol as its synchronization handle when present.
     */
    private fun FirElement.structuredChild(): StructuredChild? {
        functionTypeConversionOperand()?.let { return it.structuredChild() }
        return when (this) {
            is FirFunctionCall -> {
                if (isProvenStructuredChild()) {
                    StructuredChild(handle = null, call = this)
                } else {
                    null
                }
            }
            is FirProperty -> initializer?.structuredChild()?.copy(handle = symbol)
            is FirWrappedArgumentExpression -> expression.structuredChild()
            is FirNamedArgumentExpression -> expression.structuredChild()
            is FirSpreadArgumentExpression -> expression.structuredChild()
            is FirWrappedExpression -> expression.structuredChild()
            is FirReturnExpression -> result.structuredChild()
            else -> null
        }
    }

    /**
     * Returns the local child handle directly awaited or joined by this element.
     */
    private fun FirElement.coroutineWaitedHandle(): FirBasedSymbol<*>? {
        functionTypeConversionOperand()?.let { return it.coroutineWaitedHandle() }
        val call =
            when (this) {
                is FirFunctionCall -> this
                is FirWrappedArgumentExpression -> return expression.coroutineWaitedHandle()
                is FirNamedArgumentExpression -> return expression.coroutineWaitedHandle()
                is FirSpreadArgumentExpression -> return expression.coroutineWaitedHandle()
                is FirWrappedExpression -> return expression.coroutineWaitedHandle()
                else -> return null
            }
        val target = call.resolvedFunction()
        val isSingleWait = target?.isCoroutineWait() == true
        val isCollectionWait =
            target?.isAwaitAll() == true && call.argumentList.arguments.isEmpty()
        if (!isSingleWait && !isCollectionWait) {
            return null
        }
        return call.receiverExpressions()
            .asSequence()
            .mapNotNull { it.resolvedSymbol() }
            .firstOrNull()
    }

    /** Returns the call's single visible lambda argument, if unambiguous. */
    private fun FirFunctionCall.visibleLambdaArgument(): FirAnonymousFunctionExpression? =
        argumentList.arguments
            .asSequence()
            .mapNotNull { it.visibleLambda() }
            .singleOrNull()

    /** Unwraps this expression to a directly visible anonymous function. */
    private fun FirExpression.visibleLambda(): FirAnonymousFunctionExpression? {
        functionTypeConversionOperand()?.let { return it.visibleLambda() }
        return when (this) {
            is FirAnonymousFunctionExpression -> this
            is FirWrappedArgumentExpression -> expression.visibleLambda()
            is FirNamedArgumentExpression -> expression.visibleLambda()
            is FirSpreadArgumentExpression -> expression.visibleLambda()
            is FirWrappedExpression -> expression.visibleLambda()
            else -> null
        }
    }

    /** Recursively flattens FIR vararg containers into source argument expressions. */
    private fun FirExpression.flattenVarargArguments(): List<FirExpression> =
        if (this is FirVarargArgumentsExpression) {
            arguments.flatMap { it.flattenVarargArguments() }
        } else {
            listOf(this)
        }

    /** Only a direct local handle is a proof of the identity being synchronized. */
    private fun FirExpression.directCoroutineHandle(): FirBasedSymbol<*>? = when (this) {
        is FirWrappedArgumentExpression -> expression.directCoroutineHandle()
        is FirNamedArgumentExpression -> expression.directCoroutineHandle()
        is FirWrappedExpression -> expression.directCoroutineHandle()
        is FirPropertyAccessExpression -> resolvedSymbol()
        else -> null
    }

    /** Returns this call's resolved FIR function, if resolution succeeded. */
    private fun FirFunctionCall.resolvedFunction(): FirFunction? =
        (resolvedSymbol() as? FirFunctionSymbol<*>)?.fir

    /** Returns whether this function is a modeled structured coroutine scope. */
    private fun FirFunction.isStructuredCoroutineScope(): Boolean =
        symbol.callableId.asSingleFqName().asString() in STRUCTURED_COROUTINE_SCOPE_FQ_NAMES

    /** Returns whether this function is a modeled coroutine child builder. */
    private fun FirFunction.isCoroutineBuilder(): Boolean =
        symbol.callableId.asSingleFqName().asString() in COROUTINE_BUILDER_FQ_NAMES

    /** Returns whether this function directly synchronizes one coroutine child. */
    private fun FirFunction.isCoroutineWait(): Boolean =
        symbol.callableId.asSingleFqName().asString() in COROUTINE_WAIT_FQ_NAMES

    /** Returns whether this function is the modeled `awaitAll` overload family. */
    private fun FirFunction.isAwaitAll(): Boolean =
        symbol.callableId.asSingleFqName().asString() == AWAIT_ALL_FQ_NAME

    /** Returns whether this function is the lazy `flow { ... }` builder. */
    private fun FirFunction.isFlowBuilder(): Boolean =
        symbol.callableId.asSingleFqName().asString() == FLOW_BUILDER_FQ_NAME

    /** Returns whether this function converts a source value into a lazy Flow. */
    private fun FirFunction.isFlowSourceBuilder(): Boolean =
        symbol.callableId.asSingleFqName().asString() in FLOW_SOURCE_BUILDER_FQ_NAMES

    /** Returns whether this function merges inner flows with a fixed bound. */
    private fun FirFunction.isFlatMapMerge(): Boolean =
        symbol.callableId.asSingleFqName().asString() == FLAT_MAP_MERGE_FQ_NAME

    /** Returns whether this is the structured kotlinx.coroutines semaphore helper. */
    private fun FirFunction.isSemaphoreWithPermit(): Boolean =
        symbol.callableId.asSingleFqName().asString() == SEMAPHORE_WITH_PERMIT_FQ_NAME

    /** Returns whether this function terminally collects a Flow. */
    private fun FirFunction.isFlowCollect(): Boolean =
        unwrapFakeOverrides().symbol.callableId.asSingleFqName().asString() in FLOW_COLLECT_FQ_NAMES

    /** Returns whether this function eagerly maps every collection element once. */
    private fun FirFunction.isEagerCollectionMap(): Boolean =
        symbol.callableId.asSingleFqName().asString() in EAGER_COLLECTION_MAP_FQ_NAMES

    /** Returns whether this function invokes its callback exactly once. */
    private fun FirFunction.isSingleCallbackFunction(): Boolean =
        symbol.callableId.asSingleFqName().asString() in SINGLE_CALLBACK_FQ_NAMES

    /**
     * Returns whether [argument] passed to this parameter is a callback that a
     * recognized repetition may invoke many times.
     *
     * A Kotlin function type is a callback by declaration. Java listener
     * interfaces are not: `View.OnClickListener`,
     * `SwipeRefreshLayout.OnRefreshListener` and every other Android SAM
     * boundary declare an interface parameter, so [isSomeFunctionType] is false
     * for them and their bodies were previously dropped without a diagnostic.
     * They are recognized here through the latent effect that SAM conversion of
     * a visible lambda already produces, because
     * [functionTypeConversionOperand] unwraps the conversion node.
     *
     * Accepting any latent-valued argument can only over-approximate: a passed
     * function value that the API never invokes is charged as if it were
     * invoked, which raises the inferred peak demand rather than lowering it.
     */
    private fun FirValueParameter.isRepeatedCallback(
        argument: KotlinExpressionEffect,
    ): Boolean =
        returnTypeRef.coneType.isSomeFunctionType(session) || argument.latent != null

    /** Returns the invocation policy of a recognized repeated callback API. */
    private fun FirFunction.repeatedCallbackPolicy(): RepeatedCallbackPolicy? {
        val fqName = symbol.callableId.asSingleFqName().asString()
        return when (fqName) {
            in IMMEDIATE_REPEATED_CALLBACK_FQ_NAMES ->
                RepeatedCallbackPolicy.IMMEDIATE_SERIAL
            in RETAINED_REPEATED_CALLBACK_FQ_NAMES ->
                RepeatedCallbackPolicy.RETAINED_SERIAL
            in RETAINED_CONCURRENT_CALLBACK_FQ_NAMES ->
                RepeatedCallbackPolicy.RETAINED_CONCURRENT
            else -> null
        }
    }

    /**
     * Materializes the latent effect of an implicitly invoked function value
     * after its receiver and argument inputs have been evaluated.
     */
    private fun inferFunctionInvocation(
        invokedValueEffect: KotlinExpressionEffect?,
        evaluatedInputs: KotlinExpressionEffect,
    ): KotlinExpressionEffect {
        val latent = invokedValueEffect?.latent ?: LatentNetworkEffect()
        return evaluatedInputs.then(
            KotlinExpressionEffect(
                network = latent.network,
                latent = latent.returned,
            ),
        )
    }

    /**
     * Resolves [target] through primitive, declared, cached, or inferred eager
     * and returned-latent effects.
     */
    private fun functionSummary(
        target: FirFunction,
        effectBindings: EffectSubstitution = EffectSubstitution.EMPTY,
        call: FirFunctionCall? = null,
        context: KotlinEffectContext? = null,
    ): KotlinFunctionEffect {
        if (target.concurrentMapContract(session) != null) {
            return KotlinFunctionEffect(network = Effect.Invalid(
                "@BandwidthConcurrentMap requires direct call-site instantiation.",
            ))
        }
        // Class type substitution can create a callable with no visible body
        // (or with a body referring to its original parameter symbols). Infer
        // the definition, then translate the call's effect-variable bindings.
        // Real source overrides are retained: only substitution wrappers peel.
        val definition = generateSequence(target) { it.originalForSubstitutionOverride }.last()
        definition.downloadContract(session)?.let { contract ->
            return KotlinFunctionEffect(
                network = contract.network.asEffect(),
            )
        }

        val invocationContract = definition.effectContract(session)
        val symbolicInvocation = definition.symbolicInvocationEffect(session)
        val returnedContract = target.returnTypeRef.effectContract(session)
            ?: definition.returnTypeRef.effectContract(session)
        val variables = definition.effectVariableIds(session)
        val returnsLatentValue =
            target.returnTypeRef.coneType.isBandwidthLatentValue(session)
        val needsVisibleBody =
            invocationContract == null ||
                (returnsLatentValue && returnedContract == null)
        val shouldInfer = definition.hasInferableNetworkBody(session) && needsVisibleBody
        val inferred = if (shouldInfer) inferFunction(definition) else null
        val symbolic = KotlinFunctionEffect(
            network = symbolicInvocation
                ?: invocationContract?.network?.asEffect()
                ?: inferred?.network
                ?: Effect.Empty,
            returned = returnedContract?.let {
                LatentNetworkEffect(network = it.network.asEffect())
            } ?: inferred?.returned,
        )
        val definitionBindings = if (definition.symbol == target.symbol) effectBindings else EffectSubstitution(
            effectBindings.bindings.mapKeys { (id, _) ->
                if (id.owner == target.symbol) {
                    variables.firstOrNull { it.index == id.index && it.name == id.name } ?: id
                } else id
            },
        )
        val instantiated = symbolic.substitute(definitionBindings)
        if (call != null && context != null) {
            val unbound = buildSet {
                addAll(instantiated.network.freeVariables())
                instantiated.returned?.network?.freeVariables()?.let(::addAll)
            }.filter { it.owner == definition.symbol || it.owner == target.symbol }
            if (unbound.isNotEmpty()) {
                problem(
                    key = "unbound-effect-variable:${context.function.displayName()}:" +
                        "${call.source?.startOffset}:${unbound.joinToString { it.name }}",
                    source = call.source ?: context.function.source,
                    message = "Cannot instantiate polymorphic effect of " +
                        "${target.displayName()}; no callback argument binds " +
                        unbound.joinToString(prefix = "'", postfix = "'", separator = "', '") {
                            it.name
                        },
                )
            }
            instantiated.network.invalidMessage()?.let { message ->
                problem(
                    key = "invalid-instantiated-effect:${context.function.displayName()}:" +
                        call.source?.startOffset,
                    source = call.source ?: context.function.source,
                    message = message,
                )
            }
        }
        return instantiated
    }

    /**
     * Checks supplied function-valued arguments against parameter contracts.
     * An unannotated source parameter has the trusted empty-effect contract.
     */
    private fun validateHigherOrderArguments(
        call: FirCall,
        target: FirFunction,
        argumentEffects: Map<FirValueParameter, KotlinExpressionEffect>,
        receiverEffect: KotlinExpressionEffect?,
        context: KotlinEffectContext,
    ): EffectSubstitution {
        // launch/async are handled by structured coroutine rules, including
        // their visible lambda bodies. They are not ordinary library callback
        // contracts.
        if (target.isCoroutineBuilder()) return EffectSubstitution.EMPTY
        val variables = target.effectVariableIds(session).associateBy(EffectVariableId::name)
        val bindings = mutableMapOf<EffectVariableId, Effect>()
        target.receiverParameter?.let { receiver ->
            if (receiver.typeRef.coneType.isBandwidthLatentValue(session)) {
                val contract = receiver.effectContract(session) ?: receiver.typeRef.effectContract(session)
                val actual = receiverEffect?.latent
                val label = if (receiver.typeRef.coneType.isBandwidthFlow(session)) "Flow receiver" else "function receiver"
                if (actual == null || actual.network.invalidMessage() != null) {
                    problem("latent-receiver-unknown:" + context.function.displayName() + ":" + call.source?.startOffset,
                        call.source ?: context.function.source,
                        "Cannot infer the " + label + " effect of " + target.displayName() + ". Declare or infer its latent effect.")
                } else {
                    val variable = contract?.variable?.let(variables::get)
                    if (variable != null) bindings[variable] = actual.network
                    if (variable == null || contract?.network != NetworkEffect.EMPTY) {
                        val declared = contract?.network ?: NetworkEffect.EMPTY
                        val concrete = actual.network.concreteOrNull()
                        if (concrete == null || !concrete.isCoveredBy(declared)) {
                            problem("latent-receiver-contract:" + context.function.displayName() + ":" + call.source?.startOffset,
                                call.source ?: context.function.source,
                                label + " effect " + actual.network.render() + " is not covered by its contract " +
                                    declared.render() + " on " + target.displayName() + ".")
                        }
                    }
                }
            }
        }
        target.valueParameters.forEach { parameter ->
            if (!parameter.returnTypeRef.coneType.isBandwidthLatentValue(session)) {
                return@forEach
            }
            val contract =
                parameter.effectContract(session)
                    ?: parameter.returnTypeRef.effectContract(session)
            val isFlowParameter = parameter.returnTypeRef.coneType.isBandwidthFlow(session)
            val suppliedArgument = argumentEffects[parameter]?.let { effect ->
                if (isFlowParameter) {
                    effect.withExpectedFlowValue(call.argumentForParameter(parameter.name.asString()))
                } else {
                    effect
                }
            }
            if (suppliedArgument == null) {
                if (isFlowParameter) {
                    // Default expressions may perform eager work or construct an
                    // opaque producer. Do not silently certify an omitted value.
                    problem(
                        key = "untracked-flow-default:${context.function.displayName()}:" +
                            "${call.source?.startOffset}:${parameter.name}",
                        source = call.source ?: context.function.source,
                        message = "Cannot infer an omitted default for Flow parameter " +
                            "'${parameter.name}' of ${target.displayName()}: " +
                            "its untracked Flow default effect is unsupported. Pass the Flow explicitly.",
                    )
                    return@forEach
                }
                contract?.variable?.let { variableName ->
                    variables[variableName]?.let { variable ->
                        bindings[variable] = Effect.Empty
                    }
                }
                return@forEach
            }
            if (isFlowParameter &&
                (suppliedArgument.latent == null ||
                    suppliedArgument.latent.network.invalidMessage() != null)
            ) {
                problem(
                    key = "untracked-flow-argument:${context.function.displayName()}:" +
                        "${call.source?.startOffset}:${parameter.name}",
                    source = call.source ?: context.function.source,
                    message = "Cannot pass an untracked Flow to parameter '${parameter.name}' " +
                        "of ${target.displayName()}. Declare or infer its collection effect.",
                )
                return@forEach
            }
            val actual = suppliedArgument.latent ?: LatentNetworkEffect()
            if (contract == null) {
                if (actual.network != Effect.Empty) {
                    problem(
                        key = "higher-order-argument:${context.function.displayName()}:" +
                            "${call.source?.startOffset}:${parameter.name}",
                        source = call.source ?: context.function.source,
                        message = "Effectful higher-order argument for parameter " +
                            "'${parameter.name}' of ${target.displayName()} requires " +
                            "@BandwidthEffect(rMaxBytesPerSecond, nMax) on that parameter.",
                    )
                }
                return@forEach
            }

            val actualEffect = actual.network
            contract.variable?.let { variableName ->
                val variable = variables[variableName] ?: return@let
                val existing = bindings[variable]
                if (existing != null && existing != actualEffect) {
                    problem(
                        key = "effect-variable-conflict:${context.function.displayName()}:" +
                            "${call.source?.startOffset}:${variable.name}",
                        source = call.source ?: context.function.source,
                        message = "Effect variable '${variable.name}' is bound to different " +
                            "callback effects at the same call site.",
                    )
                    bindings[variable] = existing.choice(actualEffect)
                } else {
                    bindings[variable] = actualEffect
                }
            }

            val declaredEffect = contract.network
            if (
                (contract.variable == null || declaredEffect != NetworkEffect.EMPTY) &&
                (actualEffect.concreteOrNull()?.isCoveredBy(declaredEffect) != true)
            ) {
                problem(
                    key = "higher-order-contract:${context.function.displayName()}:" +
                        "${call.source?.startOffset}:${parameter.name}",
                    source = call.source ?: context.function.source,
                    message = "Higher-order argument effect ${actualEffect.render()} is " +
                        "not covered by @BandwidthEffect contract " +
                        "${declaredEffect.render()} on parameter '${parameter.name}'.",
                )
            }
        }
        return EffectSubstitution(bindings)
    }

    /**
     * Infers the peak effect of a general loop with an unknown iteration count.
     *
     * Work that completes within an iteration remains sequential across
     * iterations. Escaping work may overlap with every later iteration, so it
     * crosses an unknown-repetition boundary and requires a self bound.
     */
    private fun inferLoop(
        loop: FirLoop,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val iterationExpressions = if (loop is FirDoWhileLoop) {
            listOf(loop.block, loop.condition)
        } else {
            listOf(loop.condition, loop.block)
        }
        val oneIteration = sequence(iterationExpressions.map { infer(it, context) })
        val iterationNetwork = oneIteration.network
        if (iterationNetwork == Effect.Empty) return KotlinExpressionEffect()

        val repeated = repeatNetworkEffect(
            effect = iterationNetwork,
            key = "unbounded-loop:${context.function.displayName()}:" +
                loop.source?.startOffset,
            source = loop.source ?: context.function.source,
            missingBoundMessage = "Escaping network work in a general loop may overlap across " +
                "an unknown number of iterations. Use a self bound for every download.",
        )
        return KotlinExpressionEffect(network = repeated)
    }

    /** Applies the core repetition rule and reports missing escaping bounds. */
    private fun repeatNetworkEffect(
        effect: Effect,
        key: String,
        source: KtSourceElement?,
        missingBoundMessage: String,
    ): Effect {
        val concrete = effect.concreteOrNull()
        if (concrete != null && !concrete.canRepeat) {
            problem(
                key = key,
                source = source,
                message = missingBoundMessage,
            )
            return effect
        }
        return effect.repeat(missingBoundMessage)
    }

    /**
     * Repeats [effect] an unknown number of times while bounding concurrently
     * active completing bodies and preserving the unknown-repetition treatment
     * of work that escapes a body.
     */
    private fun boundedRepeatedNetworkEffect(
        effect: Effect,
        maxConcurrentBodies: Int,
        key: String,
        source: KtSourceElement?,
        missingBoundMessage: String,
    ): Effect {
        val repeated = repeatNetworkEffect(
            effect = effect,
            key = key,
            source = source,
            missingBoundMessage = missingBoundMessage,
        )
        return repeated.completingOnly()
            .boundedReplication(maxConcurrentBodies)
            .parallel(repeated.escapingOnly())
    }

    /**
     * Sequentially infers direct non-function children of an otherwise
     * unhandled FIR element.
     */
    private fun inferChildren(
        element: FirElement,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val children = mutableListOf<KotlinExpressionEffect>()
        element.acceptChildren(
            object : FirVisitorVoid() {
                /** Adds the inferred effect of [element] to the child sequence. */
                override fun visitElement(element: FirElement) {
                    children += infer(element, context)
                }

                /** Skips nested function bodies because they remain latent. */
                override fun visitFunction(function: FirFunction) = Unit
            },
        )
        return sequence(children)
    }

    /** Sequentially infers the distinct receivers of [expression]. */
    private fun inferReceivers(
        expression: FirQualifiedAccessExpression,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect = sequence(
        expression.receiverExpressions().map { infer(it, context) },
    )

    /** Returns explicit, dispatch, and extension receivers without identity duplicates. */
    private fun FirQualifiedAccessExpression.receiverExpressions(): List<FirExpression> {
        val result = mutableListOf<FirExpression>()
        listOf(explicitReceiver, dispatchReceiver, extensionReceiver).forEach { receiver ->
            if (receiver != null && result.none { it === receiver }) {
                result += receiver
            }
        }
        return result
    }

    /**
     * Maps resolved value parameters to their evaluated argument effects,
     * sequencing multiple expressions assigned to the same vararg parameter.
     */
    private fun FirCall.argumentEffectsByParameter(
        effectOf: (FirExpression) -> KotlinExpressionEffect,
    ): Map<FirValueParameter, KotlinExpressionEffect> {
        val mapping = (argumentList as? FirResolvedArgumentList)?.mapping ?: return emptyMap()
        return buildMap {
            mapping.forEach { (argument, parameter) ->
                // Route every argument through the ordinary visitor so
                // expression-level policies such as @Handler are preserved.
                val effect = effectOf(argument)
                put(parameter, get(parameter)?.let { thenValue(it, effect) } ?: effect)
            }
        }
    }

    /** Returns the resolved argument mapped to the parameter named [name]. */
    private fun FirCall.argumentForParameter(name: String): FirExpression? {
        val mapping = (argumentList as? FirResolvedArgumentList)?.mapping ?: return null
        return mapping.entries.firstOrNull { (_, parameter) ->
            parameter.name.asString() == name
        }?.key
    }

    /** Evaluates this expression as a positive compiler-known `Int`. */
    private fun FirExpression.positiveConstantInt(): Int? {
        functionTypeConversionOperand()?.let { return it.positiveConstantInt() }
        val unwrapped = when (this) {
            is FirNamedArgumentExpression -> expression
            is FirWrappedArgumentExpression -> expression
            is FirWrappedExpression -> expression
            else -> this
        }
        if (unwrapped !== this) return unwrapped.positiveConstantInt()
        val value = (unwrapped as? FirLiteralExpression)?.value
            ?: unwrapped.evaluateAs<FirLiteralExpression>(session)?.value
        val longValue = when (value) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> return null
        }
        return longValue
            .takeIf { it in 1..Int.MAX_VALUE.toLong() }
            ?.toInt()
    }

    /**
     * Resolves the capacity of the unique semaphore receiver when it is a
     * process-shared immutable top-level property.
     */
    private fun FirFunctionCall.sharedTopLevelSemaphoreCapacity(): Int? =
        receiverExpressions()
            .mapNotNull { it.sharedTopLevelSemaphoreCapacity() }
            .distinct()
            .singleOrNull()

    /** Resolves this expression to a supported shared semaphore property. */
    private fun FirExpression.sharedTopLevelSemaphoreCapacity(): Int? {
        functionTypeConversionOperand()?.let {
            return it.sharedTopLevelSemaphoreCapacity()
        }
        val expression = when (this) {
            is FirNamedArgumentExpression -> expression
            is FirWrappedArgumentExpression -> expression
            is FirSpreadArgumentExpression -> expression
            is FirWrappedExpression -> expression
            else -> this
        }
        if (expression !== this) return expression.sharedTopLevelSemaphoreCapacity()

        val property = expression.resolvedSymbol()?.fir as? FirProperty ?: return null
        val callableId = property.symbol.callableId ?: return null
        if (!property.isVal || property.symbol.isLocal || callableId.classId != null) return null
        return property.initializer?.semaphoreInitializerCapacity()
    }

    /** Extracts the constant `permits` argument from `Semaphore(permits, ...)`. */
    private fun FirExpression.semaphoreInitializerCapacity(): Int? {
        functionTypeConversionOperand()?.let { return it.semaphoreInitializerCapacity() }
        val expression = when (this) {
            is FirNamedArgumentExpression -> expression
            is FirWrappedArgumentExpression -> expression
            is FirSpreadArgumentExpression -> expression
            is FirWrappedExpression -> expression
            else -> this
        }
        if (expression !== this) return expression.semaphoreInitializerCapacity()

        val initializer = expression as? FirFunctionCall ?: return null
        val target = initializer.resolvedFunction() ?: return null
        if (target.symbol.callableId.asSingleFqName().asString() != SEMAPHORE_FACTORY_FQ_NAME) {
            return null
        }
        return initializer.argumentForParameter("permits")?.positiveConstantInt()
    }

    /**
     * Returns whether this builder is an unqualified direct child whose context
     * cannot replace the structured parent job.
     */
    private fun FirFunctionCall.isProvenStructuredChild(): Boolean {
        if (resolvedFunction()?.isCoroutineBuilder() != true || explicitReceiver?.source != null) {
            return false
        }
        val coroutineContext = argumentForParameter("context") ?: return true
        return coroutineContext.isKnownDispatcherOnly()
    }

    /** Returns whether this expression resolves to a recognized dispatcher value. */
    private fun FirExpression.isKnownDispatcherOnly(): Boolean {
        functionTypeConversionOperand()?.let { return it.isKnownDispatcherOnly() }
        val unwrapped = when (this) {
            is FirNamedArgumentExpression -> expression
            is FirWrappedArgumentExpression -> expression
            is FirWrappedExpression -> expression
            else -> this
        }
        if (unwrapped !== this) return unwrapped.isKnownDispatcherOnly()
        val symbol = (unwrapped as? FirQualifiedAccessExpression)?.resolvedSymbol()
            as? FirCallableSymbol<*>
        val callableId = symbol?.callableId?.asSingleFqName()?.asString()
        return callableId in DISPATCHER_CONTEXT_FQ_NAMES
    }

    /** Returns the symbol referenced by a resolved qualified access. */
    private fun FirElement.resolvedSymbol(): FirBasedSymbol<*>? =
        when (this) {
            is FirQualifiedAccessExpression ->
                (calleeReference as? FirResolvedNamedReference)?.resolvedSymbol
            else -> null
        }

    /** Forwards a source-located, deduplication-keyed inference problem. */
    private fun problem(
        key: String,
        source: KtSourceElement?,
        message: String,
    ) {
        reportProblem(key, source, message)
    }

    /** Sequentially folds [effects] from an empty expression effect. */
    private fun sequence(effects: List<KotlinExpressionEffect>): KotlinExpressionEffect =
        effects.fold(KotlinExpressionEffect()) { effect, next -> effect.then(next) }

    /** Conservatively joins [effects] as mutually exclusive branches. */
    private fun choice(effects: List<KotlinExpressionEffect>): KotlinExpressionEffect =
        effects.fold(KotlinExpressionEffect()) { effect, branch ->
            KotlinExpressionEffect(
                network = effect.network.choice(branch.network),
                latent = effect.latent.join(branch.latent),
            )
        }

    /** Sequences [first] before [second] and returns the latter's latent value. */
    private fun thenValue(
        first: KotlinExpressionEffect,
        second: KotlinExpressionEffect,
    ): KotlinExpressionEffect = first.then(second)

    /**
     * Returns the unique bounded-client capacity attached to a primitive
     * download call's receivers or arguments.
     */
    private fun FirFunctionCall.clientSelfBound(target: FirFunction): Int? {
        if (target.downloadContract(session) == null) return null
        val bounds = (receiverExpressions() + argumentList.arguments)
            .mapNotNull { it.boundedClientCapacity() }
            .distinct()
        return bounds.singleOrNull()
    }

    /** Resolves a `@BoundedClient` capacity from this expression. */
    private fun FirExpression.boundedClientCapacity(): Int? {
        functionTypeConversionOperand()?.let { return it.boundedClientCapacity() }
        val expression = when (this) {
            is FirNamedArgumentExpression -> expression
            is FirWrappedArgumentExpression -> expression
            is FirSpreadArgumentExpression -> expression
            is FirWrappedExpression -> expression
            else -> this
        }
        if (expression !== this) return expression.boundedClientCapacity()
        val declaration = expression.resolvedSymbol()?.fir
            as? org.jetbrains.kotlin.fir.FirAnnotationContainer
        return declaration?.boundedClientContract(session)?.k
    }

    private companion object {
        data class StructuredChild(
            val handle: FirBasedSymbol<*>?,
            val call: FirFunctionCall,
        )

        data class RepeatedStructuredChildren(
            val handle: FirBasedSymbol<*>?,
            val mapCall: FirFunctionCall,
            val preparation: List<FirElement>,
            val builderCall: FirFunctionCall,
        )

        data class ReturnedMappedChild(
            val preparation: List<FirElement>,
            val call: FirFunctionCall,
        )

        data class RepeatedCoroutineChildrenEffect(
            val inputs: KotlinExpressionEffect,
            val preparation: KotlinExpressionEffect,
            val body: KotlinExpressionEffect?,
        )

        data class CoroutineBuilderEffect(
            val inputs: KotlinExpressionEffect,
            val body: KotlinExpressionEffect?,
        )

        val STRUCTURED_COROUTINE_SCOPE_FQ_NAMES: Set<String> = setOf(
            "kotlinx.coroutines.coroutineScope",
            "kotlinx.coroutines.supervisorScope",
            "kotlinx.coroutines.withContext",
        )
        val COROUTINE_BUILDER_FQ_NAMES: Set<String> = setOf(
            "kotlinx.coroutines.launch",
            "kotlinx.coroutines.async",
        )
        val COROUTINE_WAIT_FQ_NAMES: Set<String> = setOf(
            "kotlinx.coroutines.Job.join",
            "kotlinx.coroutines.Deferred.await",
        )
        val DISPATCHER_CONTEXT_FQ_NAMES: Set<String> = setOf(
            "kotlinx.coroutines.Dispatchers.Default",
            "kotlinx.coroutines.Dispatchers.IO",
            "kotlinx.coroutines.Dispatchers.Main",
            "kotlinx.coroutines.Dispatchers.Unconfined",
        )
        const val AWAIT_ALL_FQ_NAME: String = "kotlinx.coroutines.awaitAll"
        const val FLOW_BUILDER_FQ_NAME: String = "kotlinx.coroutines.flow.flow"
        const val FLAT_MAP_MERGE_FQ_NAME: String = "kotlinx.coroutines.flow.flatMapMerge"
        const val SEMAPHORE_FACTORY_FQ_NAME: String = "kotlinx.coroutines.sync.Semaphore"
        const val SEMAPHORE_WITH_PERMIT_FQ_NAME: String = "kotlinx.coroutines.sync.withPermit"
        val FLOW_SOURCE_BUILDER_FQ_NAMES: Set<String> = setOf(
            "kotlinx.coroutines.flow.asFlow",
        )
        val FLOW_COLLECT_FQ_NAMES: Set<String> = setOf(
            "kotlinx.coroutines.flow.collect",
            "kotlinx.coroutines.flow.collectLatest",
            "kotlinx.coroutines.flow.Flow.collect",
            "kotlinx.coroutines.flow.SharedFlow.collect",
            "kotlinx.coroutines.flow.StateFlow.collect",
            "kotlinx.coroutines.flow.MutableSharedFlow.collect",
            "kotlinx.coroutines.flow.MutableStateFlow.collect",
        )
        val EAGER_COLLECTION_MAP_FQ_NAMES: Set<String> = setOf(
            "kotlin.collections.map",
            "kotlin.collections.mapIndexed",
            "kotlin.collections.zip",
            "kotlin.collections.mapNotNull",
        )
        val SINGLE_CALLBACK_FQ_NAMES: Set<String> = setOf(
            "androidx.tracing.traceAsync",
            "io.beatmaps.common.amqp.rabbitOptional",
            "io.beatmaps.util.captchaProvider",
            "io.beatmaps.util.optionalAuthorization",
            "io.beatmaps.util.requireAuthorization",
            "io.beatmaps.api.scores.ssTry",
            "io.ktor.server.auth.authenticate",
            "io.ktor.server.application.serverConfig",
            "io.ktor.server.application.ServerConfigBuilder.module",
            "io.ktor.server.engine.embeddedServer",
            "io.ktor.server.routing.route",
            "io.ktor.server.routing.routing",
            // These contracts cover callback timing only. Their own HTTP/
            // WebSocket transport must have a separate download/lifetime
            // contract at the audited application boundary.
            "io.ktor.client.statement.HttpStatement.execute",
            "io.ktor.client.plugins.websocket.webSocket",
            "io.ktor.client.plugins.websocket.ws",
            "io.ktor.client.plugins.websocket.wss",
            "kotlinx.coroutines.runBlocking",
            "kotlinx.coroutines.sync.withLock",
            "kotlin.also",
            "kotlin.apply",
            "kotlin.let",
            "kotlin.run",
            "kotlin.with",
            "kotlin.runCatching",
            "kotlin.use",
            "kotlin.io.use",
            "kotlin.collections.getOrPut",
            "org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction",
            "org.jetbrains.exposed.sql.transactions.transaction",
        )
        val IMMEDIATE_REPEATED_CALLBACK_FQ_NAMES: Set<String> = setOf(
            "kotlin.repeat",
            "kotlin.collections.map",
            "kotlin.collections.mapIndexed",
            "kotlin.collections.zip",
            "kotlin.collections.mapNotNull",
            "kotlin.collections.forEach",
            "kotlin.collections.forEachIndexed",
            "kotlin.sequences.forEach",
        )
        val RETAINED_REPEATED_CALLBACK_FQ_NAMES: Set<String> = setOf(
            // Android View listeners. These are Java SAM boundaries, reached
            // through KotlinExpressionEffect.latent rather than a Kotlin
            // function type; see isRepeatedCallback.
            "android.view.View.setOnClickListener",
            "android.view.View.setOnLongClickListener",
            "android.widget.AdapterView.setOnItemClickListener",
            "androidx.swiperefreshlayout.widget.SwipeRefreshLayout.setOnRefreshListener",
            // Compose click and paging surfaces. Modifier.clickable carries most
            // Compose click handling; the pagers repeat their page content.
            "androidx.compose.foundation.clickable",
            "androidx.compose.foundation.combinedClickable",
            "androidx.compose.foundation.pager.HorizontalPager",
            "androidx.compose.foundation.pager.VerticalPager",
            "androidx.compose.material3.FloatingActionButton",
            "androidx.compose.material3.ExtendedFloatingActionButton",
            "androidx.compose.material3.Card",
            "androidx.compose.material3.NavigationBarItem",
            "androidx.compose.material3.Tab",
            "androidx.compose.foundation.lazy.LazyColumn",
            "androidx.compose.foundation.lazy.LazyRow",
            "androidx.compose.foundation.lazy.grid.LazyHorizontalGrid",
            "androidx.compose.foundation.lazy.grid.LazyVerticalGrid",
            "androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid",
            "androidx.compose.material3.Button",
            "androidx.compose.material3.ElevatedButton",
            "androidx.compose.material3.FilledTonalButton",
            "androidx.compose.material3.OutlinedButton",
            "androidx.compose.material3.TextButton",
            "androidx.compose.material3.IconButton",
            "androidx.compose.material3.FilledIconButton",
            "androidx.compose.material3.FilledTonalIconButton",
            "androidx.compose.material3.OutlinedIconButton",
            "androidx.compose.foundation.lazy.items",
            "androidx.compose.foundation.lazy.itemsIndexed",
            "androidx.compose.foundation.lazy.grid.items",
            "androidx.compose.foundation.lazy.grid.itemsIndexed",
            "androidx.compose.foundation.lazy.staggeredgrid.items",
            "androidx.compose.foundation.lazy.staggeredgrid.itemsIndexed",
        )
        val RETAINED_CONCURRENT_CALLBACK_FQ_NAMES: Set<String> = setOf(
            // Observable delegates retain the callback and run it on assignments.
            // Different threads can assign concurrently; each network operation
            // therefore needs its own shared self bound.
            "kotlin.properties.Delegates.observable",
            "de.nielsfalk.ktor.swagger.delete",
            "de.nielsfalk.ktor.swagger.get",
            "de.nielsfalk.ktor.swagger.patch",
            "de.nielsfalk.ktor.swagger.post",
            "de.nielsfalk.ktor.swagger.put",
            "io.beatmaps.api.util.getWithOptions",
            "io.beatmaps.api.util.postWithOptions",
            "io.beatmaps.common.amqp.consumeAck",
            "io.ktor.server.resources.delete",
            "io.ktor.server.resources.get",
            "io.ktor.server.resources.patch",
            "io.ktor.server.resources.post",
            "io.ktor.server.resources.put",
            "io.ktor.server.routing.delete",
            "io.ktor.server.routing.get",
            "io.ktor.server.routing.patch",
            "io.ktor.server.routing.post",
            "io.ktor.server.routing.put",
            "io.ktor.server.websocket.webSocketRaw",
        )
    }
}

/** How a recognized higher-order API invokes and retains its callback. */
private enum class RepeatedCallbackPolicy(
    val mayOutliveCall: Boolean,
    val invocationsMayOverlap: Boolean,
) {
    IMMEDIATE_SERIAL(mayOutliveCall = false, invocationsMayOverlap = false),
    RETAINED_SERIAL(mayOutliveCall = true, invocationsMayOverlap = false),
    RETAINED_CONCURRENT(mayOutliveCall = true, invocationsMayOverlap = true),
}
