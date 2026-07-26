package io.github.loinguyen.bandwidth.compiler.fir

import io.github.loinguyen.bandwidth.core.NetworkEffect
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
            invocation = bodyEffect.immediate,
            returned = context.returnedLatent.join(expressionResult),
        )
    }

    fun infer(
        element: FirElement?,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect =
        element?.accept(this, context) ?: KotlinExpressionEffect()

    override fun visitElement(
        element: FirElement,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect =
        element.functionTypeConversionOperand()?.let { infer(it, data) }
            ?: inferChildren(element, data)

    override fun visitFunction(
        function: FirFunction,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = KotlinExpressionEffect()

    override fun visitAnonymousFunctionExpression(
        anonymousFunctionExpression: FirAnonymousFunctionExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect =
        KotlinExpressionEffect(
            latent = inferFunction(anonymousFunctionExpression.anonymousFunction).asLatent(),
        )

    override fun visitCallableReferenceAccess(
        callableReferenceAccess: FirCallableReferenceAccess,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val receivers = inferReceivers(callableReferenceAccess, data)
        val target = callableReferenceAccess.resolvedSymbol()?.fir as? FirFunction
        return KotlinExpressionEffect(
            immediate = receivers.immediate,
            latent = target?.let(::functionSummary)?.asLatent(),
        )
    }

    override fun visitProperty(
        property: FirProperty,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val initializer = infer(property.initializer, data)
        data.bind(property.symbol, initializer.latent)
        return KotlinExpressionEffect(immediate = initializer.immediate)
    }

    override fun visitPropertyAccessExpression(
        propertyAccessExpression: FirPropertyAccessExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val receivers = inferReceivers(propertyAccessExpression, data)
        return KotlinExpressionEffect(
            immediate = receivers.immediate,
            latent = data.latentOf(propertyAccessExpression.resolvedSymbol()),
        )
    }

    override fun visitVariableAssignment(
        variableAssignment: FirVariableAssignment,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val value = infer(variableAssignment.rValue, data)
        val target = (variableAssignment.lValue as? FirPropertyAccessExpression)
            ?.resolvedSymbol()
        data.bind(target, value.latent)
        return KotlinExpressionEffect(immediate = value.immediate)
    }

    override fun visitTypeOperatorCall(
        typeOperatorCall: FirTypeOperatorCall,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect =
        infer(typeOperatorCall.argumentList.arguments.singleOrNull(), data)

    override fun visitWrappedArgumentExpression(
        wrappedArgumentExpression: FirWrappedArgumentExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = infer(wrappedArgumentExpression.expression, data)

    override fun visitNamedArgumentExpression(
        namedArgumentExpression: FirNamedArgumentExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = infer(namedArgumentExpression.expression, data)

    override fun visitSpreadArgumentExpression(
        spreadArgumentExpression: FirSpreadArgumentExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = infer(spreadArgumentExpression.expression, data)

    override fun visitWrappedExpression(
        wrappedExpression: FirWrappedExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = infer(wrappedExpression.expression, data)

    override fun visitFunctionCall(
        functionCall: FirFunctionCall,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = inferCall(functionCall, data)

    override fun visitImplicitInvokeCall(
        implicitInvokeCall: FirImplicitInvokeCall,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = inferCall(implicitInvokeCall, data)

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

    override fun visitTryExpression(
        tryExpression: FirTryExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val alternatives = choice(
            listOf(infer(tryExpression.tryBlock, data)) +
                tryExpression.catches.map { infer(it.block, data) },
        )
        val finallyEffect = infer(tryExpression.finallyBlock, data)
        return KotlinExpressionEffect(
            immediate = alternatives.immediate.then(finallyEffect.immediate),
            latent = alternatives.latent,
        )
    }

    override fun visitLoop(
        loop: FirLoop,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = inferLoop(loop, data)

    override fun visitWhileLoop(
        whileLoop: FirWhileLoop,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = inferLoop(whileLoop, data)

    override fun visitDoWhileLoop(
        doWhileLoop: FirDoWhileLoop,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = inferLoop(doWhileLoop, data)

    override fun visitReturnExpression(
        returnExpression: FirReturnExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val value = infer(returnExpression.result, data)
        data.recordReturn(value.latent)
        return KotlinExpressionEffect(immediate = value.immediate)
    }

    override fun visitBlock(
        block: FirBlock,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = sequence(
        block.statements.map { infer(it, data) },
    )

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
            return inferInlineAwaitAll(call, context)
        }
        if (target.isSequentialCallbackFunction()) {
            return inferSequentialCallbackCall(call, context)
        }
        val cachedEffects = IdentityHashMap<FirExpression, KotlinExpressionEffect>()
        fun effectOf(expression: FirExpression): KotlinExpressionEffect =
            cachedEffects.getOrPut(expression) { infer(expression, context) }

        val receivers = call.receiverExpressions()
        val receiverEffects = sequence(receivers.map(::effectOf))
        val arguments = call.argumentList.arguments
        val evaluatedArguments = arguments
            .map(::effectOf)
            .fold(NetworkEffect.EMPTY) { effect, argument ->
                effect.then(argument.immediate)
            }
        val argumentEffects = call.argumentEffectsByParameter(::effectOf)
        validateHigherOrderArguments(
            call = call,
            target = target,
            argumentEffects = argumentEffects,
            context = context,
        )

        val evaluatedInputs = receiverEffects.immediate.then(evaluatedArguments)
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
        val invocation = call.clientSelfBound(target)?.let(summary.invocation::withSelfBound)
            ?: summary.invocation
        return KotlinExpressionEffect(
            immediate = evaluatedInputs.then(invocation),
            latent = summary.returned,
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

    private fun inferInlineAwaitAll(
        call: FirFunctionCall,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val arguments = call.argumentList.arguments.flatMap { it.flattenVarargArguments() }
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
        val parallelChildren = children.fold(KotlinExpressionEffect()) { effect, child ->
            effect.parallel(inferCoroutineChild(child.lambda, context))
        }
        val receivers = inferReceivers(call, context)
        return receivers.then(parallelChildren)
    }

    /**
     * A launch/async outside a visible structured scope can overlap an
     * unbounded number of future invocations (for example, UI click events).
     * Keep its rate effect but leave n unresolved for a client bound to
     * discharge at the final check.
     */
    private fun inferUnstructuredCoroutineBuilder(
        call: FirFunctionCall,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val lambda = call.visibleLambdaArgument()
        val child = lambda?.let { inferCoroutineChild(it, context) }
            ?: return KotlinExpressionEffect()
        val inputs = sequence(
            call.receiverExpressions().map { infer(it, context) } +
                call.argumentList.arguments
                    .filter { it.visibleLambda() !== lambda }
                    .map { infer(it, context) },
        )
        return inputs.then(
            KotlinExpressionEffect(
                immediate = child.immediate.withUnknownConcurrency(),
            ),
        )
    }

    private fun inferSequentialCallbackCall(
        call: FirFunctionCall,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val cachedEffects = IdentityHashMap<FirExpression, KotlinExpressionEffect>()
        fun effectOf(expression: FirExpression): KotlinExpressionEffect =
            cachedEffects.getOrPut(expression) { infer(expression, context) }

        val receiverEffects = sequence(call.receiverExpressions().map(::effectOf))
        val argumentEffects = call.argumentList.arguments.map(::effectOf)
        val evaluatedArguments = argumentEffects.fold(NetworkEffect.EMPTY) { effect, argument ->
            effect.then(argument.immediate)
        }
        val invokedCallbacks = argumentEffects.fold(KotlinExpressionEffect()) { effect, argument ->
            effect.then(
                KotlinExpressionEffect(
                    immediate = argument.latent?.invocation ?: NetworkEffect.EMPTY,
                ),
            )
        }
        return KotlinExpressionEffect(immediate = receiverEffects.immediate)
            .then(KotlinExpressionEffect(immediate = evaluatedArguments))
            .then(invokedCallbacks)
    }

    private fun inferCoroutineStatements(
        block: FirBlock,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val phases = CoroutinePhaseState<FirBasedSymbol<*>>()

        block.statements.forEach { statement ->
            val child = statement.structuredChild()
            if (child != null) {
                phases.addChild(
                    handle = child.handle,
                    effect = inferCoroutineChild(child.lambda, context),
                )
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

    private fun inferCoroutineChild(
        lambda: FirAnonymousFunctionExpression,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val body = lambda.anonymousFunction.body
        return body?.let { inferCoroutineStatements(it, context) }
            ?: KotlinExpressionEffect(
                immediate = infer(lambda, context).latent?.invocation ?: NetworkEffect.EMPTY,
            )
    }

    private fun FirElement.structuredChild(): StructuredChild? {
        functionTypeConversionOperand()?.let { return it.structuredChild() }
        return when (this) {
            is FirFunctionCall ->
                if (resolvedFunction()?.isCoroutineBuilder() == true) {
                    visibleLambdaArgument()?.let { StructuredChild(handle = null, lambda = it) }
                } else {
                    null
                }
            is FirProperty -> initializer?.structuredChild()?.copy(handle = symbol)
            is FirWrappedArgumentExpression -> expression.structuredChild()
            is FirNamedArgumentExpression -> expression.structuredChild()
            is FirSpreadArgumentExpression -> expression.structuredChild()
            is FirWrappedExpression -> expression.structuredChild()
            else -> null
        }
    }

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
        if (call.resolvedFunction()?.isCoroutineWait() != true) {
            return null
        }
        return call.receiverExpressions()
            .asSequence()
            .mapNotNull { it.resolvedSymbol() }
            .firstOrNull()
    }

    private fun FirFunctionCall.visibleLambdaArgument(): FirAnonymousFunctionExpression? =
        argumentList.arguments
            .asSequence()
            .mapNotNull { it.visibleLambda() }
            .singleOrNull()

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

    private fun FirExpression.flattenVarargArguments(): List<FirExpression> =
        if (this is FirVarargArgumentsExpression) {
            arguments.flatMap { it.flattenVarargArguments() }
        } else {
            listOf(this)
        }

    private fun FirFunctionCall.resolvedFunction(): FirFunction? =
        (resolvedSymbol() as? FirFunctionSymbol<*>)?.fir

    private fun FirFunction.isStructuredCoroutineScope(): Boolean =
        symbol.callableId.asSingleFqName().asString() in STRUCTURED_COROUTINE_SCOPE_FQ_NAMES

    private fun FirFunction.isCoroutineBuilder(): Boolean =
        symbol.callableId.asSingleFqName().asString() in COROUTINE_BUILDER_FQ_NAMES

    private fun FirFunction.isCoroutineWait(): Boolean =
        symbol.callableId.asSingleFqName().asString() in COROUTINE_WAIT_FQ_NAMES

    private fun FirFunction.isAwaitAll(): Boolean =
        symbol.callableId.asSingleFqName().asString() == AWAIT_ALL_FQ_NAME

    private fun FirFunction.isSequentialCallbackFunction(): Boolean =
        symbol.callableId.asSingleFqName().asString() in SEQUENTIAL_CALLBACK_FQ_NAMES

    private fun inferFunctionInvocation(
        call: FirImplicitInvokeCall,
        invokedValue: FirExpression?,
        invokedValueEffect: KotlinExpressionEffect?,
        evaluatedInputs: NetworkEffect,
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
            return KotlinExpressionEffect(immediate = evaluatedInputs)
        }
        return KotlinExpressionEffect(
            immediate = evaluatedInputs.then(latent.invocation),
            latent = latent.returned,
        )
    }

    private fun functionSummary(target: FirFunction): KotlinFunctionEffect {
        target.downloadContract(session)?.let { contract ->
            return KotlinFunctionEffect(
                invocation = NetworkEffect.download(
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
            invocation = invocationContract?.toNetworkEffect()
                ?: inferred?.invocation
                ?: NetworkEffect.EMPTY,
            returned = returnedContract?.toLatentEffect()
                ?: inferred?.returned,
        )
    }

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
                if (actual.invocation != NetworkEffect.EMPTY) {
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
            if (!actual.invocation.isCoveredBy(declaredEffect)) {
                problem(
                    key = "higher-order-contract:${context.function.displayName()}:" +
                        "${call.source?.startOffset}:${parameter.name}",
                    source = call.source ?: context.function.source,
                    message = "Higher-order argument effect ${actual.invocation.render()} is not " +
                        "covered by @BandwidthEffect(" +
                        "rMaxBytesPerSecond=${contract.rMaxBytesPerSecond}, " +
                        "nMax=${contract.nMax}) on parameter '${parameter.name}'.",
                )
            }
        }
    }

    private fun inferLoop(
        loop: FirLoop,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val oneIteration = sequence(
            listOf(
                infer(loop.condition, context),
                infer(loop.block, context),
            ),
        )
        return KotlinExpressionEffect(
            immediate = oneIteration.immediate.withUnknownConcurrency(),
        )
    }

    private fun inferChildren(
        element: FirElement,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val children = mutableListOf<KotlinExpressionEffect>()
        element.acceptChildren(
            object : FirVisitorVoid() {
                override fun visitElement(element: FirElement) {
                    children += infer(element, context)
                }

                override fun visitFunction(function: FirFunction) = Unit
            },
        )
        return sequence(children)
    }

    private fun inferReceivers(
        expression: FirQualifiedAccessExpression,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect = sequence(
        expression.receiverExpressions().map { infer(it, context) },
    )

    private fun FirQualifiedAccessExpression.receiverExpressions(): List<FirExpression> {
        val result = mutableListOf<FirExpression>()
        listOf(explicitReceiver, dispatchReceiver, extensionReceiver).forEach { receiver ->
            if (receiver != null && result.none { it === receiver }) {
                result += receiver
            }
        }
        return result
    }

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

    private fun FirElement.resolvedSymbol(): FirBasedSymbol<*>? =
        when (this) {
            is FirQualifiedAccessExpression ->
                (calleeReference as? FirResolvedNamedReference)?.resolvedSymbol
            else -> null
        }

    private fun problem(
        key: String,
        source: KtSourceElement?,
        message: String,
    ) {
        reportProblem(key, source, message)
    }

    private fun sequence(effects: List<KotlinExpressionEffect>): KotlinExpressionEffect =
        effects.fold(KotlinExpressionEffect()) { effect, next -> effect.then(next) }

    private fun choice(effects: List<KotlinExpressionEffect>): KotlinExpressionEffect =
        effects.fold(KotlinExpressionEffect()) { effect, branch ->
            KotlinExpressionEffect(
                immediate = effect.immediate.then(branch.immediate),
                latent = effect.latent.join(branch.latent),
            )
        }

    private fun thenValue(
        first: KotlinExpressionEffect,
        second: KotlinExpressionEffect,
    ): KotlinExpressionEffect = first.then(second)

    private fun FirFunctionCall.clientSelfBound(target: FirFunction): Int? {
        if (target.downloadContract(session) == null) return null
        val bounds = receiverExpressions()
            .mapNotNull { receiver ->
                val symbol = receiver.resolvedSymbol() ?: return@mapNotNull null
                val declaration = symbol.fir
                    as? org.jetbrains.kotlin.fir.FirAnnotationContainer
                val capacity = declaration?.boundedClientContract(session)?.k
                    ?: return@mapNotNull null
                capacity
            }
            .distinct()
        return bounds.singleOrNull()
    }

    private companion object {
        data class StructuredChild(
            val handle: FirBasedSymbol<*>?,
            val lambda: FirAnonymousFunctionExpression,
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
        const val AWAIT_ALL_FQ_NAME: String = "kotlinx.coroutines.awaitAll"
        val SEQUENTIAL_CALLBACK_FQ_NAMES: Set<String> = setOf(
            "kotlin.collections.forEach",
            "kotlin.collections.forEachIndexed",
            "kotlin.sequences.forEach",
            "androidx.tracing.traceAsync",
        )
    }
}
