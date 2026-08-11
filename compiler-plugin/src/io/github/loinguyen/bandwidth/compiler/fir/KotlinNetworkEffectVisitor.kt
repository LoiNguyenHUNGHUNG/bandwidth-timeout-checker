package io.github.loinguyen.bandwidth.compiler.fir

import io.github.loinguyen.bandwidth.core.NetworkEffect
import io.github.loinguyen.bandwidth.core.DownloadLifetime
import java.util.IdentityHashMap
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirBlock
import org.jetbrains.kotlin.fir.expressions.FirCallableReferenceAccess
import org.jetbrains.kotlin.fir.expressions.FirDoWhileLoop
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirImplicitInvokeCall
import org.jetbrains.kotlin.fir.expressions.FirLoop
import org.jetbrains.kotlin.fir.expressions.FirNamedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirReturnExpression
import org.jetbrains.kotlin.fir.expressions.FirSpreadArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
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
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isSomeFunctionType
import org.jetbrains.kotlin.fir.visitors.FirVisitor
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid

/**
 * Infers the effect component of Kotlin's resolved FIR type-and-effect
 * judgment. Kotlin syntax and concurrency rules are centralized here.
 */
internal class KotlinNetworkEffectVisitor(
    private val session: FirSession,
    private val inferFunction: (FirFunction) -> KotlinFunctionEffect,
    private val reportProblem: (
        key: String,
        source: KtSourceElement?,
        message: String,
    ) -> Unit,
) : FirVisitor<KotlinExpressionEffect, KotlinEffectContext>() {
    /**
     * Infers the eager effect and callback-valued return of [function].
     *
     * @param latentValues shared bindings for function values resolved across
     * interprocedural analysis.
     */
    fun inferFunctionBody(
        function: FirFunction,
        latentValues: MutableMap<FirBasedSymbol<*>, LatentNetworkEffect>,
    ): KotlinFunctionEffect {
        val context = KotlinEffectContext(function, session, latentValues)
        val bodyEffect: KotlinExpressionEffect = infer(function.body, context)
        val expressionResult: LatentNetworkEffect? =
            if (function.returnTypeRef.coneType.isSomeFunctionType(session)) {
                bodyEffect.latent
            } else {
                null
            }
        return KotlinFunctionEffect(
            network = bodyEffect.network,
            returned = context.returnedLatent.join(expressionResult),
        )
    }

    /**
     * Infers [element], treating a missing FIR node as an effect-free expression.
     */
    fun infer(
        element: FirElement?,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect =
        element?.accept(this, context) ?: KotlinExpressionEffect()

    /**
     * Infers an otherwise unsupported element through its conversion operand or
     * non-function children.
     */
    override fun visitElement(
        element: FirElement,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect =
        element.functionTypeConversionOperand()?.let { infer(it, data) }
            ?: inferChildren(element, data)

    /**
     * Leaves a nested function declaration latent; its body is charged only on
     * invocation.
     */
    override fun visitFunction(
        function: FirFunction,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = KotlinExpressionEffect()

    /** Wraps a visible anonymous function's summary as a latent value. */
    override fun visitAnonymousFunctionExpression(
        anonymousFunctionExpression: FirAnonymousFunctionExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect =
        KotlinExpressionEffect(
            latent = inferFunction(anonymousFunctionExpression.anonymousFunction).asLatent(),
        )

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
        return KotlinExpressionEffect(
            network = receivers.network,
            latent = target?.let(::functionSummary)?.asLatent(),
        )
    }

    /**
     * Infers a property initializer and binds any resulting function value to
     * the property symbol.
     */
    override fun visitProperty(
        property: FirProperty,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val initializer = infer(property.initializer, data)
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
        return KotlinExpressionEffect(
            network = receivers.network,
            latent = data.latentOf(propertyAccessExpression.resolvedSymbol()),
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
        val value = infer(variableAssignment.rValue, data)
        val target = (variableAssignment.lValue as? FirPropertyAccessExpression)
            ?.resolvedSymbol()
        data.bind(target, value.latent)
        return KotlinExpressionEffect(
            network = value.network,
        )
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
        val value = infer(returnExpression.result, data)
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
        if (target.isStructuredCoroutineScope()) {
            return inferStructuredCoroutineScope(call, context)
        }
        if (target.isCoroutineBuilder()) {
            return inferUnstructuredCoroutineBuilder(call, context)
        }
        if (target.isAwaitAll()) {
            return inferAwaitAll(call, context)
        }
        val repeatedCallbackMayOutliveCall = target.repeatedCallbackMayOutliveCall()
        if (repeatedCallbackMayOutliveCall != null) {
            return inferRepeatedCallbackCall(
                call = call,
                target = target,
                context = context,
                mayOutliveCall = repeatedCallbackMayOutliveCall,
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
        validateHigherOrderArguments(
            call = call,
            target = target,
            argumentEffects = argumentEffects,
            context = context,
        )

        val evaluatedInputs = receiverEffects.then(evaluatedArguments)
        if (call is FirImplicitInvokeCall) {
            val invokedValue = call.explicitReceiver ?: call.dispatchReceiver
            return inferFunctionInvocation(
                call = call,
                invokedValue = invokedValue,
                invokedValueEffect = invokedValue?.let(::effectOf),
                evaluatedInputs = evaluatedInputs,
                context = context,
            )
        }

        val summary = functionSummary(target)
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
     * Infers a collection `map { async { ... } }` as an unknown number of
     * concurrently active structured children.
     *
     * The collection source and non-callback arguments are evaluated once.
     * Each mapped async body may overlap every other body, so all downloads in
     * one iteration need trusted self bounds before replication is finite.
     */
    private fun inferRepeatedCoroutineChildren(
        children: RepeatedStructuredChildren,
        context: KotlinEffectContext,
    ): CoroutineBuilderEffect {
        val mapCall = children.mapCall
        val callback = mapCall.visibleLambdaArgument()
        val inputs = sequence(
            mapCall.receiverExpressions().map { infer(it, context) } +
                mapCall.argumentList.arguments
                    .filter { it.visibleLambda() !== callback }
                    .map { infer(it, context) },
        )
        val child = inferCoroutineBuilder(children.builderCall, context)
        val perElement = child.inputs.then(child.body ?: KotlinExpressionEffect())
        val network = perElement.network
        if (network == NetworkEffect.EMPTY) {
            return CoroutineBuilderEffect(inputs = inputs, body = KotlinExpressionEffect())
        }
        if (network.escapingOnly() != NetworkEffect.EMPTY) {
            problem(
                key = "mapped-async-escaping:${context.function.displayName()}:" +
                    "${mapCall.source?.startOffset}",
                source = mapCall.source ?: context.function.source,
                message = "Cannot infer map { async { ... } } when an async body starts " +
                    "network work that may outlive that child. Keep nested work structured.",
            )
            return CoroutineBuilderEffect(inputs = inputs, body = perElement)
        }
        val repeated = repeatNetworkEffect(
            effect = network.withLifetime(DownloadLifetime.MAY_OUTLIVE_CALL),
            key = "mapped-async-self-bound:${context.function.displayName()}:" +
                "${mapCall.source?.startOffset}",
            source = mapCall.source ?: context.function.source,
            missingBoundMessage = "Mapped async children may overlap across an unknown " +
                "number of collection elements. Use a self bound for every download.",
        )
        return CoroutineBuilderEffect(
            inputs = inputs,
            body = KotlinExpressionEffect(
                network = repeated.withLifetime(DownloadLifetime.COMPLETES_WITH_CALL),
            ),
        )
    }

    /** A launch/async on an unproven receiver may outlive this expression. */
    private fun inferUnstructuredCoroutineBuilder(
        call: FirFunctionCall,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val resolved = inferCoroutineBuilder(call, context)
        val child = resolved.body ?: return resolved.inputs
        val inputs = resolved.inputs
        val repeated = repeatNetworkEffect(
            effect = child.network.withLifetime(DownloadLifetime.MAY_OUTLIVE_CALL),
            key = "escaping-coroutine-self-bound:${context.function.displayName()}:" +
                call.source?.startOffset,
            source = call.source ?: context.function.source,
            missingBoundMessage = "Network work launched on an escaping coroutine scope " +
                "requires a self bound for every download.",
        )
        return inputs.then(
            KotlinExpressionEffect(network = repeated),
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
                    network = argument.latent?.network ?: NetworkEffect.EMPTY,
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
        mayOutliveCall: Boolean,
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
            .filterKeys { it.returnTypeRef.coneType.isSomeFunctionType(session) }
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
        if (bodyNetwork == NetworkEffect.EMPTY) return evaluatedInputs

        val repeated = repeatNetworkEffect(
            effect = bodyNetwork,
            key = "repeated-network:${context.function.displayName()}:" +
                call.source?.startOffset,
            source = call.source ?: context.function.source,
            missingBoundMessage = "Escaping network work in repeated callback " +
                "${target.displayName()} may overlap across an unknown number of " +
                "invocations. Use a self bound for every download.",
        )
        val invocationEffect = if (mayOutliveCall) {
            repeated.withLifetime(DownloadLifetime.MAY_OUTLIVE_CALL)
        } else {
            repeated
        }
        return evaluatedInputs.then(
            KotlinExpressionEffect(network = invocationEffect),
        )
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

        block.statements.forEach { statement ->
            val repeatedChildren = statement.repeatedStructuredChildren()
            if (repeatedChildren != null) {
                val resolved = inferRepeatedCoroutineChildren(repeatedChildren, context)
                phases.recordStatement(resolved.inputs)
                resolved.body?.let {
                    phases.addChild(handle = repeatedChildren.handle, effect = it)
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
            if (waitedHandle != null && phases.synchronize(waitedHandle)) {
                return@forEach
            }

            phases.recordStatement(infer(statement, context))
        }

        return phases.finish()
    }

    /**
     * Unwraps this element as an eager collection `map` whose visible callback
     * consists solely of one structured `async` child.
     */
    private fun FirElement.repeatedStructuredChildren(): RepeatedStructuredChildren? {
        functionTypeConversionOperand()?.let { return it.repeatedStructuredChildren() }
        return when (this) {
            is FirFunctionCall -> {
                if (resolvedFunction()?.isEagerCollectionMap() != true) return null
                val body = visibleLambdaArgument()?.anonymousFunction?.body ?: return null
                val child = body.statements.singleOrNull()?.structuredChild() ?: return null
                RepeatedStructuredChildren(
                    handle = null,
                    mapCall = this,
                    builderCall = child.call,
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
                network = infer(lambda, context).latent?.network ?: NetworkEffect.EMPTY,
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

    /** Returns whether this function eagerly maps every collection element once. */
    private fun FirFunction.isEagerCollectionMap(): Boolean =
        symbol.callableId.asSingleFqName().asString() in EAGER_COLLECTION_MAP_FQ_NAMES

    /** Returns whether this function invokes its callback exactly once. */
    private fun FirFunction.isSingleCallbackFunction(): Boolean =
        symbol.callableId.asSingleFqName().asString() in SINGLE_CALLBACK_FQ_NAMES

    /**
     * Returns whether this function repeatedly invokes callbacks that may
     * outlive the call, or `null` when it is not a recognized repetition.
     */
    private fun FirFunction.repeatedCallbackMayOutliveCall(): Boolean? {
        val fqName = symbol.callableId.asSingleFqName().asString()
        return when (fqName) {
            in IMMEDIATE_REPEATED_CALLBACK_FQ_NAMES -> false
            in RETAINED_REPEATED_CALLBACK_FQ_NAMES -> true
            else -> null
        }
    }

    /**
     * Materializes the latent effect of an implicitly invoked function value
     * after its receiver and argument inputs have been evaluated.
     */
    private fun inferFunctionInvocation(
        call: FirImplicitInvokeCall,
        invokedValue: FirExpression?,
        invokedValueEffect: KotlinExpressionEffect?,
        evaluatedInputs: KotlinExpressionEffect,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val latent = invokedValueEffect?.latent
        if (latent == null) {
            val parameter = invokedValue
                ?.resolvedSymbol()
                ?.let { it as? FirValueParameterSymbol }
                ?.fir
            val message =
                if (parameter != null) {
                    "Higher-order parameter '${parameter.name}' is invoked without " +
                        "@BandwidthEffect(rMaxBytesPerSecond, nMax)."
                } else {
                    "Cannot infer the latent effect of an invoked function value. " +
                        "Keep the lambda visible or annotate its higher-order boundary."
                }
            problem(
                key = "higher-order-invoke:${context.function.displayName()}:" +
                    "${call.source?.startOffset}",
                source = call.source ?: context.function.source,
                message = message,
            )
            return evaluatedInputs
        }
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
    private fun functionSummary(target: FirFunction): KotlinFunctionEffect {
        target.downloadContract(session)?.let { contract ->
            return KotlinFunctionEffect(
                network = NetworkEffect.download(
                    maxBytes = contract.maxBytes,
                    completeTimeoutMillis = contract.completeTimeoutMillis,
                ),
            )
        }

        val invocationContract = target.effectContract(session)
        val returnedContract = target.returnTypeRef.effectContract(session)
        val returnsFunction =
            target.returnTypeRef.coneType.isSomeFunctionType(session)
        val shouldInfer =
            target.body != null &&
                (invocationContract == null || (returnsFunction && returnedContract == null))
        val inferred = if (shouldInfer) inferFunction(target) else null
        return KotlinFunctionEffect(
            network = invocationContract?.toNetworkEffect()
                ?: inferred?.network
                ?: NetworkEffect.EMPTY,
            returned = returnedContract?.toLatentEffect()
                ?: inferred?.returned,
        )
    }

    /**
     * Requires resolvable latent effects for function-valued arguments and
     * checks them against parameter contracts when supplied.
     */
    private fun validateHigherOrderArguments(
        call: FirFunctionCall,
        target: FirFunction,
        argumentEffects: Map<FirValueParameter, KotlinExpressionEffect>,
        context: KotlinEffectContext,
    ) {
        // launch/async are handled by structured coroutine rules, including
        // their visible lambda bodies. They are not ordinary library callback
        // contracts.
        if (target.isCoroutineBuilder()) return
        target.valueParameters.forEach { parameter ->
            if (!parameter.returnTypeRef.coneType.isSomeFunctionType(session)) {
                return@forEach
            }
            val actual = argumentEffects[parameter]?.latent
            val contract =
                parameter.effectContract(session)
                    ?: parameter.returnTypeRef.effectContract(session)
            if (actual == null) {
                problem(
                    key = "unknown-higher-order-argument:${context.function.displayName()}:" +
                        "${call.source?.startOffset}:${parameter.name}",
                    source = call.source ?: context.function.source,
                    message = "Cannot infer the latent effect of higher-order argument " +
                        "for parameter '${parameter.name}' of ${target.displayName()}. " +
                        "Annotate the source higher-order boundary with " +
                        "@BandwidthEffect(rMaxBytesPerSecond, nMax).",
                )
                return@forEach
            }

            if (contract == null) {
                if (actual.materialize() != NetworkEffect.EMPTY) {
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

            val declaredEffect = contract.toNetworkEffect()
            if (!actual.materialize().isCoveredBy(declaredEffect)) {
                problem(
                    key = "higher-order-contract:${context.function.displayName()}:" +
                        "${call.source?.startOffset}:${parameter.name}",
                    source = call.source ?: context.function.source,
                    message = "Higher-order argument effect ${actual.materialize().render()} is " +
                        "not covered by @BandwidthEffect contract " +
                        "${declaredEffect.render()} on parameter '${parameter.name}'.",
                )
            }
        }
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
        if (iterationNetwork == NetworkEffect.EMPTY) return KotlinExpressionEffect()

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
        effect: NetworkEffect,
        key: String,
        source: KtSourceElement?,
        missingBoundMessage: String,
    ): NetworkEffect {
        if (!effect.canRepeat) {
            problem(
                key = key,
                source = source,
                message = missingBoundMessage,
            )
            return effect
        }
        return effect.repeat()
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
    private fun FirFunctionCall.argumentEffectsByParameter(
        effectOf: (FirExpression) -> KotlinExpressionEffect,
    ): Map<FirValueParameter, KotlinExpressionEffect> {
        val mapping = (argumentList as? FirResolvedArgumentList)?.mapping ?: return emptyMap()
        return buildMap {
            mapping.forEach { (argument, parameter) ->
                val effect = effectOf(argument)
                put(parameter, get(parameter)?.let { thenValue(it, effect) } ?: effect)
            }
        }
    }

    /** Returns the resolved argument mapped to the parameter named [name]. */
    private fun FirFunctionCall.argumentForParameter(name: String): FirExpression? {
        val mapping = (argumentList as? FirResolvedArgumentList)?.mapping ?: return null
        return mapping.entries.firstOrNull { (_, parameter) ->
            parameter.name.asString() == name
        }?.key
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
            val builderCall: FirFunctionCall,
        )

        data class CoroutineBuilderEffect(
            val inputs: KotlinExpressionEffect,
            val body: KotlinExpressionEffect?,
        )

        val STRUCTURED_COROUTINE_SCOPE_FQ_NAMES: Set<String> = setOf(
            "kotlinx.coroutines.coroutineScope",
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
        val EAGER_COLLECTION_MAP_FQ_NAMES: Set<String> = setOf(
            "kotlin.collections.map",
            "kotlin.collections.mapIndexed",
        )
        val SINGLE_CALLBACK_FQ_NAMES: Set<String> = setOf(
            "androidx.tracing.traceAsync",
        )
        val IMMEDIATE_REPEATED_CALLBACK_FQ_NAMES: Set<String> = setOf(
            "kotlin.collections.forEach",
            "kotlin.collections.forEachIndexed",
            "kotlin.sequences.forEach",
        )
        val RETAINED_REPEATED_CALLBACK_FQ_NAMES: Set<String> = setOf(
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
    }
}
