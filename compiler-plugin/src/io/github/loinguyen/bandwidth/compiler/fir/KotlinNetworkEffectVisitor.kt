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
import org.jetbrains.kotlin.fir.expressions.FirFunctionTypeConversionExpression
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
    ): KotlinExpressionEffect = inferChildren(element, data)

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

    override fun visitFunctionTypeConversionExpression(
        functionTypeConversionExpression: FirFunctionTypeConversionExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = infer(functionTypeConversionExpression.expression, data)

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
        return KotlinExpressionEffect(
            immediate = evaluatedInputs.then(summary.invocation),
            latent = summary.returned,
        )
    }

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
        if (oneIteration.immediate != NetworkEffect.EMPTY) {
            problem(
                key = "loop:${context.function.displayName()}:${loop.source?.startOffset}",
                source = loop.source ?: context.function.source,
                message = "Cannot infer an effectful loop in " +
                    "${context.function.displayName()}; the current checker requires " +
                    "statically finite network structure.",
            )
        }
        return KotlinExpressionEffect(immediate = oneIteration.immediate)
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
        return KotlinExpressionEffect(
            immediate = children.fold(NetworkEffect.EMPTY) { effect, child ->
                effect.then(child.immediate)
            },
        )
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
        KotlinExpressionEffect(
            immediate = effects.fold(NetworkEffect.EMPTY) { effect, next ->
                effect.then(next.immediate)
            },
            latent = effects.lastOrNull()?.latent,
        )

    private fun choice(effects: List<KotlinExpressionEffect>): KotlinExpressionEffect =
        KotlinExpressionEffect(
            immediate = effects.fold(NetworkEffect.EMPTY) { effect, branch ->
                effect.then(branch.immediate)
            },
            latent = effects.fold(null as LatentNetworkEffect?) { latent, branch ->
                latent.join(branch.latent)
            },
        )

    private fun thenValue(
        first: KotlinExpressionEffect,
        second: KotlinExpressionEffect,
    ): KotlinExpressionEffect =
        KotlinExpressionEffect(
            immediate = first.immediate.then(second.immediate),
            latent = second.latent,
        )
}
