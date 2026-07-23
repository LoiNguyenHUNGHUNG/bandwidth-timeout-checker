package io.github.loinguyen.bandwidth.compiler.ir

import io.github.loinguyen.bandwidth.core.NetworkEffect
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrContainerExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrFunctionExpression
import org.jetbrains.kotlin.ir.expressions.IrFunctionReference
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrLoop
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrTry
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrWhen
import org.jetbrains.kotlin.ir.visitors.IrVisitor
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/**
 * Translates executable Kotlin IR constructs into the language-independent
 * quantitative [NetworkEffect] values.
 *
 * New Kotlin syntax and concurrency patterns should be modeled by adding a
 * dedicated visitor override here. Function-level caching, recursion handling,
 * contract checking, and reporting remain in [KotlinNetworkEffectInference].
 */
internal class KotlinNetworkEffectVisitor(
    private val sourceFunctions: Set<IrFunction>,
    private val inferFunction: (IrFunction) -> NetworkEffect,
    private val reportProblem: (String, IrFunction, String) -> Unit,
) : IrVisitor<NetworkEffect, IrFunction>() {
    fun infer(
        element: IrElement?,
        currentFunction: IrFunction,
    ): NetworkEffect =
        element?.accept(this, currentFunction) ?: NetworkEffect.EMPTY

    override fun visitElement(
        element: IrElement,
        data: IrFunction,
    ): NetworkEffect = inferChildren(element, data)

    override fun visitFunction(
        declaration: IrFunction,
        data: IrFunction,
    ): NetworkEffect = NetworkEffect.EMPTY

    override fun visitFunctionExpression(
        expression: IrFunctionExpression,
        data: IrFunction,
    ): NetworkEffect = NetworkEffect.EMPTY

    override fun visitVariable(
        declaration: IrVariable,
        data: IrFunction,
    ): NetworkEffect = infer(declaration.initializer, data)

    override fun visitCall(
        expression: IrCall,
        data: IrFunction,
    ): NetworkEffect = inferCall(expression, data)

    override fun visitWhen(
        expression: IrWhen,
        data: IrFunction,
    ): NetworkEffect = choice(
        expression.branches.map { branch ->
            sequence(
                listOf(
                    infer(branch.condition, data),
                    infer(branch.result, data),
                ),
            )
        },
    )

    override fun visitTry(
        aTry: IrTry,
        data: IrFunction,
    ): NetworkEffect {
        val alternatives: NetworkEffect = choice(
            listOf(infer(aTry.tryResult, data)) +
                aTry.catches.map { infer(it.result, data) },
        )
        return sequence(
            listOf(
                alternatives,
                infer(aTry.finallyExpression, data),
            ),
        )
    }

    override fun visitLoop(
        loop: IrLoop,
        data: IrFunction,
    ): NetworkEffect = inferLoop(loop, data)

    override fun visitReturn(
        expression: IrReturn,
        data: IrFunction,
    ): NetworkEffect = infer(expression.value, data)

    override fun visitContainerExpression(
        expression: IrContainerExpression,
        data: IrFunction,
    ): NetworkEffect = sequence(
        expression.statements.map { infer(it, data) },
    )

    override fun visitBlockBody(
        body: IrBlockBody,
        data: IrFunction,
    ): NetworkEffect = sequence(
        body.statements.map { infer(it, data) },
    )

    override fun visitExpressionBody(
        body: IrExpressionBody,
        data: IrFunction,
    ): NetworkEffect = infer(body.expression, data)

    private fun inferCall(
        call: IrCall,
        currentFunction: IrFunction,
    ): NetworkEffect {
        validateHigherOrderArguments(call, currentFunction)
        val evaluatedArguments: NetworkEffect = sequence(
            call.arguments.map { infer(it, currentFunction) },
        )
        val target: IrFunction = call.symbol.owner
        val callEffect: NetworkEffect = when {
            target.downloadContract() != null -> {
                val contract: DownloadContract = requireNotNull(target.downloadContract())
                NetworkEffect.download(
                    maxBytes = contract.maxBytes,
                    completeTimeoutMillis = contract.completeTimeoutMillis,
                )
            }
            target.effectContract() != null -> {
                requireNotNull(target.effectContract()).toNetworkEffect()
            }
            target in sourceFunctions -> inferFunction(target)
            else -> higherOrderParameterEffect(call, currentFunction) ?: NetworkEffect.EMPTY
        }
        return sequence(listOf(evaluatedArguments, callEffect))
    }

    private fun validateHigherOrderArguments(
        call: IrCall,
        currentFunction: IrFunction,
    ) {
        val target: IrFunction = call.symbol.owner
        target.parameters.forEach { parameter ->
            if (parameter.kind != IrParameterKind.Regular) return@forEach
            val argument: IrElement = call.arguments[parameter] ?: return@forEach
            val actualEffect: NetworkEffect = argument.callbackEffect() ?: return@forEach
            val contract: EffectContract? = parameter.effectContract()
            if (contract == null) {
                if (actualEffect != NetworkEffect.EMPTY) {
                    problem(
                        key = "higher-order-argument:${currentFunction.displayName()}:" +
                            "${call.startOffset}:${parameter.name}",
                        function = currentFunction,
                        message = "Effectful higher-order argument for parameter '${parameter.name}' " +
                            "of ${target.displayName()} requires " +
                            "@BandwidthEffect(rMaxBytesPerSecond, nMax) on that parameter.",
                    )
                }
                return@forEach
            }

            val declaredEffect: NetworkEffect = contract.toNetworkEffect()
            if (!actualEffect.isCoveredBy(declaredEffect)) {
                problem(
                    key = "higher-order-contract:${currentFunction.displayName()}:" +
                        "${call.startOffset}:${parameter.name}",
                    function = currentFunction,
                    message = "Higher-order argument effect ${actualEffect.render()} is not covered " +
                        "by @BandwidthEffect(rMaxBytesPerSecond=${contract.rMaxBytesPerSecond}, " +
                        "nMax=${contract.nMax}) on parameter '${parameter.name}'.",
                )
            }
        }
    }

    private fun IrElement.callbackEffect(): NetworkEffect? = when (this) {
        is IrFunctionExpression -> inferFunction(function)
        is IrFunctionReference -> inferFunction(symbol.owner)
        is IrGetValue -> {
            val parameter: IrValueParameter = symbol.owner as? IrValueParameter ?: return null
            parameter.effectContract()?.toNetworkEffect()
        }
        is IrTypeOperatorCall -> argument.callbackEffect()
        else -> null
    }

    private fun higherOrderParameterEffect(
        call: IrCall,
        currentFunction: IrFunction,
    ): NetworkEffect? {
        if (call.symbol.owner.name.asString() != "invoke") return null
        val parameter: IrValueParameter =
            ((call.dispatchReceiver as? IrGetValue)?.symbol?.owner as? IrValueParameter)
                ?: return null
        val contract: EffectContract = parameter.effectContract() ?: run {
            problem(
                key = "higher-order:${currentFunction.displayName()}:${parameter.name}",
                function = currentFunction,
                message = "Higher-order parameter '${parameter.name}' is invoked without " +
                    "@BandwidthEffect(rMaxBytesPerSecond, nMax).",
            )
            return NetworkEffect.EMPTY
        }
        return contract.toNetworkEffect()
    }

    private fun inferLoop(
        loop: IrLoop,
        currentFunction: IrFunction,
    ): NetworkEffect {
        val oneIteration: NetworkEffect = sequence(
            listOf(
                infer(loop.condition, currentFunction),
                infer(loop.body, currentFunction),
            ),
        )
        if (oneIteration != NetworkEffect.EMPTY) {
            problem(
                key = "loop:${currentFunction.displayName()}:${loop.startOffset}",
                function = currentFunction,
                message = "Cannot infer an effectful loop in ${currentFunction.displayName()}; " +
                    "the current checker requires statically finite network structure.",
            )
        }
        return oneIteration
    }

    private fun inferChildren(
        element: IrElement,
        currentFunction: IrFunction,
    ): NetworkEffect {
        val children: MutableList<NetworkEffect> = mutableListOf()
        element.acceptChildrenVoid(
            object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    children += infer(element, currentFunction)
                }

                override fun visitFunction(declaration: IrFunction) = Unit
            },
        )
        return sequence(children)
    }

    private fun problem(
        key: String,
        function: IrFunction,
        message: String,
    ) {
        reportProblem(key, function, message)
    }

    private fun sequence(effects: List<NetworkEffect>): NetworkEffect =
        effects.fold(NetworkEffect.EMPTY, NetworkEffect::then)

    private fun choice(effects: List<NetworkEffect>): NetworkEffect =
        effects.fold(NetworkEffect.EMPTY, NetworkEffect::then)
}
