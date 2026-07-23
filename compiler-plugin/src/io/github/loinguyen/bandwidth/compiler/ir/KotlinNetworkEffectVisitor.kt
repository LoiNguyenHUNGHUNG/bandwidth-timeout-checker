package io.github.loinguyen.bandwidth.compiler.ir

import io.github.loinguyen.bandwidth.core.NetworkEffect
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrSetValue
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.visitors.IrVisitor
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/**
 * Infers immediate and higher-order latent effects directly from Kotlin IR.
 *
 * New Kotlin syntax and concurrency patterns should be modeled by adding a
 * dedicated visitor override here. Function-level caching, recursion handling,
 * contract checking, and reporting remain in [KotlinNetworkEffectInference].
 */
internal class KotlinNetworkEffectVisitor(
    private val sourceFunctions: Set<IrFunction>,
    private val inferFunction: (IrFunction) -> KotlinFunctionEffect,
    private val reportProblem: (String, IrFunction, String) -> Unit,
) : IrVisitor<KotlinExpressionEffect, KotlinEffectContext>() {
    private val latentValues: MutableMap<IrValueDeclaration, LatentNetworkEffect> =
        mutableMapOf()

    fun inferFunctionBody(function: IrFunction): KotlinFunctionEffect {
        val context = KotlinEffectContext(function, latentValues)
        val bodyEffect: KotlinExpressionEffect = infer(function.body, context)
        val expressionBodyReturn: LatentNetworkEffect? =
            if (function.body is IrExpressionBody) bodyEffect.latent else null
        return KotlinFunctionEffect(
            invocation = bodyEffect.immediate,
            returned = context.returnedLatent.join(expressionBodyReturn),
        )
    }

    fun infer(
        element: IrElement?,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect =
        element?.accept(this, context) ?: KotlinExpressionEffect()

    override fun visitElement(
        element: IrElement,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = inferChildren(element, data)

    override fun visitFunction(
        declaration: IrFunction,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = KotlinExpressionEffect()

    override fun visitFunctionExpression(
        expression: IrFunctionExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect =
        KotlinExpressionEffect(latent = inferFunction(expression.function).asLatent())

    override fun visitFunctionReference(
        expression: IrFunctionReference,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val capturedReceivers: KotlinExpressionEffect = inferChildren(expression, data)
        return KotlinExpressionEffect(
            immediate = capturedReceivers.immediate,
            latent = functionSummary(expression.symbol.owner).asLatent(),
        )
    }

    override fun visitVariable(
        declaration: IrVariable,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val initializer: KotlinExpressionEffect = infer(declaration.initializer, data)
        data.bind(declaration, initializer.latent)
        return KotlinExpressionEffect(immediate = initializer.immediate)
    }

    override fun visitGetValue(
        expression: IrGetValue,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect =
        KotlinExpressionEffect(latent = data.latentOf(expression.symbol.owner))

    override fun visitSetValue(
        expression: IrSetValue,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val value: KotlinExpressionEffect = infer(expression.value, data)
        data.bind(expression.symbol.owner, value.latent)
        return KotlinExpressionEffect(immediate = value.immediate)
    }

    override fun visitTypeOperator(
        expression: IrTypeOperatorCall,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = infer(expression.argument, data)

    override fun visitCall(
        expression: IrCall,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = inferCall(expression, data)

    override fun visitWhen(
        expression: IrWhen,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = choice(
        expression.branches.map { branch ->
            thenValue(
                infer(branch.condition, data),
                infer(branch.result, data),
            )
        },
    )

    override fun visitTry(
        aTry: IrTry,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val alternatives: KotlinExpressionEffect = choice(
            listOf(infer(aTry.tryResult, data)) +
                aTry.catches.map { infer(it.result, data) },
        )
        val finallyEffect: KotlinExpressionEffect = infer(aTry.finallyExpression, data)
        return KotlinExpressionEffect(
            immediate = alternatives.immediate.then(finallyEffect.immediate),
            latent = alternatives.latent,
        )
    }

    override fun visitLoop(
        loop: IrLoop,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = inferLoop(loop, data)

    override fun visitReturn(
        expression: IrReturn,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val value: KotlinExpressionEffect = infer(expression.value, data)
        data.recordReturn(value.latent)
        return KotlinExpressionEffect(immediate = value.immediate)
    }

    override fun visitContainerExpression(
        expression: IrContainerExpression,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = sequence(
        expression.statements.map { infer(it, data) },
    )

    override fun visitBlockBody(
        body: IrBlockBody,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = sequence(
        body.statements.map { infer(it, data) },
    )

    override fun visitExpressionBody(
        body: IrExpressionBody,
        data: KotlinEffectContext,
    ): KotlinExpressionEffect = infer(body.expression, data)

    private fun inferCall(
        call: IrCall,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val target: IrFunction = call.symbol.owner
        val argumentEffects: Map<IrValueParameter, KotlinExpressionEffect> =
            target.parameters.mapNotNull { parameter ->
                call.arguments[parameter]?.let { argument ->
                    parameter to infer(argument, context)
                }
            }.toMap()
        validateHigherOrderArguments(call, target, argumentEffects, context)

        val evaluatedArguments: NetworkEffect = argumentEffects.values
            .fold(NetworkEffect.EMPTY) { effect, argument ->
                effect.then(argument.immediate)
            }
        if (target.isKotlinFunctionInvoke()) {
            return inferFunctionInvocation(
                call = call,
                target = target,
                argumentEffects = argumentEffects,
                evaluatedArguments = evaluatedArguments,
                context = context,
            )
        }

        val summary: KotlinFunctionEffect = functionSummary(target)
        return KotlinExpressionEffect(
            immediate = evaluatedArguments.then(summary.invocation),
            latent = summary.returned,
        )
    }

    private fun inferFunctionInvocation(
        call: IrCall,
        target: IrFunction,
        argumentEffects: Map<IrValueParameter, KotlinExpressionEffect>,
        evaluatedArguments: NetworkEffect,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val receiverParameter: IrValueParameter? =
            target.parameters.firstOrNull { it.kind == IrParameterKind.DispatchReceiver }
        val latent: LatentNetworkEffect? = receiverParameter
            ?.let(argumentEffects::get)
            ?.latent
        if (latent == null) {
            val receiver: IrExpression? = call.dispatchReceiver
            val parameter: IrValueParameter? =
                ((receiver as? IrGetValue)?.symbol?.owner as? IrValueParameter)
            val message: String =
                if (parameter != null) {
                    "Higher-order parameter '${parameter.name}' is invoked without " +
                        "@BandwidthEffect(rMaxBytesPerSecond, nMax)."
                } else {
                    "Cannot infer the latent effect of an invoked function value. " +
                        "Keep the lambda visible or annotate its higher-order boundary."
                }
            problem(
                key = "higher-order-invoke:${context.function.displayName()}:${call.startOffset}",
                function = context.function,
                message = message,
            )
            return KotlinExpressionEffect(immediate = evaluatedArguments)
        }
        return KotlinExpressionEffect(
            immediate = evaluatedArguments.then(latent.invocation),
            latent = latent.returned,
        )
    }

    private fun functionSummary(target: IrFunction): KotlinFunctionEffect {
        target.downloadContract()?.let { contract ->
            return KotlinFunctionEffect(
                invocation = NetworkEffect.download(
                    maxBytes = contract.maxBytes,
                    completeTimeoutMillis = contract.completeTimeoutMillis,
                ),
            )
        }

        val inferred: KotlinFunctionEffect? =
            if (target in sourceFunctions) inferFunction(target) else null
        val invocation: NetworkEffect =
            target.effectContract()?.toNetworkEffect()
                ?: inferred?.invocation
                ?: NetworkEffect.EMPTY
        val returned: LatentNetworkEffect? =
            target.returnType.effectContract()?.toLatentEffect()
                ?: inferred?.returned
        return KotlinFunctionEffect(
            invocation = invocation,
            returned = returned,
        )
    }

    private fun validateHigherOrderArguments(
        call: IrCall,
        target: IrFunction,
        argumentEffects: Map<IrValueParameter, KotlinExpressionEffect>,
        context: KotlinEffectContext,
    ) {
        target.parameters.forEach { parameter ->
            if (parameter.kind != IrParameterKind.Regular) return@forEach
            val argument: IrExpression = call.arguments[parameter] ?: return@forEach
            if (!argument.type.isFunctionLike()) return@forEach

            val actual: LatentNetworkEffect? = argumentEffects[parameter]?.latent
            val contract: EffectContract? =
                parameter.effectContract() ?: parameter.type.effectContract()
            if (actual == null) {
                problem(
                    key = "unknown-higher-order-argument:${context.function.displayName()}:" +
                        "${call.startOffset}:${parameter.name}",
                    function = context.function,
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
                            "${call.startOffset}:${parameter.name}",
                        function = context.function,
                        message = "Effectful higher-order argument for parameter '${parameter.name}' " +
                            "of ${target.displayName()} requires " +
                            "@BandwidthEffect(rMaxBytesPerSecond, nMax) on that parameter.",
                    )
                }
                return@forEach
            }

            val declaredEffect: NetworkEffect = contract.toNetworkEffect()
            if (!actual.invocation.isCoveredBy(declaredEffect)) {
                problem(
                    key = "higher-order-contract:${context.function.displayName()}:" +
                        "${call.startOffset}:${parameter.name}",
                    function = context.function,
                    message = "Higher-order argument effect ${actual.invocation.render()} is not " +
                        "covered by @BandwidthEffect(" +
                        "rMaxBytesPerSecond=${contract.rMaxBytesPerSecond}, " +
                        "nMax=${contract.nMax}) on parameter '${parameter.name}'.",
                )
            }
        }
    }

    private fun inferLoop(
        loop: IrLoop,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val oneIteration: KotlinExpressionEffect = sequence(
            listOf(
                infer(loop.condition, context),
                infer(loop.body, context),
            ),
        )
        if (oneIteration.immediate != NetworkEffect.EMPTY) {
            problem(
                key = "loop:${context.function.displayName()}:${loop.startOffset}",
                function = context.function,
                message = "Cannot infer an effectful loop in ${context.function.displayName()}; " +
                    "the current checker requires statically finite network structure.",
            )
        }
        return KotlinExpressionEffect(immediate = oneIteration.immediate)
    }

    private fun inferChildren(
        element: IrElement,
        context: KotlinEffectContext,
    ): KotlinExpressionEffect {
        val children: MutableList<KotlinExpressionEffect> = mutableListOf()
        element.acceptChildrenVoid(
            object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    children += infer(element, context)
                }

                override fun visitFunction(declaration: IrFunction) = Unit
            },
        )
        return KotlinExpressionEffect(
            immediate = children.fold(NetworkEffect.EMPTY) { effect, child ->
                effect.then(child.immediate)
            },
        )
    }

    private fun problem(
        key: String,
        function: IrFunction,
        message: String,
    ) {
        reportProblem(key, function, message)
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

    private fun IrFunction.isKotlinFunctionInvoke(): Boolean {
        if (name.asString() != "invoke") return false
        val ownerName: String =
            parentAsClass.fqNameWhenAvailable?.asString() ?: return false
        return ownerName.isKotlinFunctionClassName()
    }

    private fun IrType.isFunctionLike(): Boolean {
        val typeName: String =
            classOrNull?.owner?.fqNameWhenAvailable?.asString() ?: return false
        return typeName.isKotlinFunctionClassName()
    }

    private fun String.isKotlinFunctionClassName(): Boolean =
        startsWith("kotlin.Function") ||
            startsWith("kotlin.coroutines.SuspendFunction") ||
            startsWith("kotlin.reflect.KFunction") ||
            startsWith("kotlin.jvm.functions.Function")
}
