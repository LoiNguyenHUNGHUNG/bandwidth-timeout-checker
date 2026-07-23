package io.github.loinguyen.bandwidth.compiler.ir

import io.github.loinguyen.bandwidth.core.NetworkEffect
import io.github.loinguyen.bandwidth.core.NetworkEffectAnalyzer
import io.github.loinguyen.bandwidth.core.NetworkProgram
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
 * [NetworkProgram].
 *
 * New Kotlin syntax and concurrency patterns should be modeled by adding a
 * dedicated visitor override here. Function-level caching, recursion handling,
 * contract checking, and reporting remain in [KotlinNetworkProgramLowering].
 */
internal class KotlinNetworkProgramVisitor(
    private val sourceFunctions: Set<IrFunction>,
    private val inferFunction: (IrFunction) -> NetworkProgram,
    private val reportProblem: (String, IrFunction, String) -> Unit,
) : IrVisitor<NetworkProgram, IrFunction>() {
    fun lower(
        element: IrElement?,
        currentFunction: IrFunction,
    ): NetworkProgram =
        element?.accept(this, currentFunction) ?: NetworkProgram.Pure

    override fun visitElement(
        element: IrElement,
        data: IrFunction,
    ): NetworkProgram = lowerChildren(element, data)

    override fun visitFunction(
        declaration: IrFunction,
        data: IrFunction,
    ): NetworkProgram = NetworkProgram.Pure

    override fun visitFunctionExpression(
        expression: IrFunctionExpression,
        data: IrFunction,
    ): NetworkProgram = NetworkProgram.Pure

    override fun visitVariable(
        declaration: IrVariable,
        data: IrFunction,
    ): NetworkProgram = lower(declaration.initializer, data)

    override fun visitCall(
        expression: IrCall,
        data: IrFunction,
    ): NetworkProgram = lowerCall(expression, data)

    override fun visitWhen(
        expression: IrWhen,
        data: IrFunction,
    ): NetworkProgram = choice(
        expression.branches.map { branch ->
            sequence(
                listOf(
                    lower(branch.condition, data),
                    lower(branch.result, data),
                ),
            )
        },
    )

    override fun visitTry(
        aTry: IrTry,
        data: IrFunction,
    ): NetworkProgram {
        val alternatives: NetworkProgram = choice(
            listOf(lower(aTry.tryResult, data)) +
                aTry.catches.map { lower(it.result, data) },
        )
        return sequence(
            listOf(
                alternatives,
                lower(aTry.finallyExpression, data),
            ),
        )
    }

    override fun visitLoop(
        loop: IrLoop,
        data: IrFunction,
    ): NetworkProgram = lowerLoop(loop, data)

    override fun visitReturn(
        expression: IrReturn,
        data: IrFunction,
    ): NetworkProgram = lower(expression.value, data)

    override fun visitContainerExpression(
        expression: IrContainerExpression,
        data: IrFunction,
    ): NetworkProgram = sequence(
        expression.statements.map { lower(it, data) },
    )

    override fun visitBlockBody(
        body: IrBlockBody,
        data: IrFunction,
    ): NetworkProgram = sequence(
        body.statements.map { lower(it, data) },
    )

    override fun visitExpressionBody(
        body: IrExpressionBody,
        data: IrFunction,
    ): NetworkProgram = lower(body.expression, data)

    private fun lowerCall(
        call: IrCall,
        currentFunction: IrFunction,
    ): NetworkProgram {
        validateHigherOrderArguments(call, currentFunction)
        val evaluatedArguments: NetworkProgram = sequence(
            call.arguments.map { lower(it, currentFunction) },
        )
        val target: IrFunction = call.symbol.owner
        val callProgram: NetworkProgram = when {
            target.downloadContract() != null -> {
                val contract: DownloadContract = requireNotNull(target.downloadContract())
                NetworkProgram.Download(
                    maxBytes = contract.maxBytes,
                    completeTimeoutMillis = contract.completeTimeoutMillis,
                )
            }
            target.effectContract() != null -> {
                NetworkProgram.OpaqueCall(requireNotNull(target.effectContract()).toNetworkEffect())
            }
            target in sourceFunctions -> inferFunction(target)
            else -> higherOrderParameterProgram(call, currentFunction) ?: NetworkProgram.Pure
        }
        return sequence(listOf(evaluatedArguments, callProgram))
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
        is IrFunctionExpression -> NetworkEffectAnalyzer.analyze(inferFunction(function))
        is IrFunctionReference -> NetworkEffectAnalyzer.analyze(inferFunction(symbol.owner))
        is IrGetValue -> {
            val parameter: IrValueParameter = symbol.owner as? IrValueParameter ?: return null
            parameter.effectContract()?.toNetworkEffect()
        }
        is IrTypeOperatorCall -> argument.callbackEffect()
        else -> null
    }

    private fun higherOrderParameterProgram(
        call: IrCall,
        currentFunction: IrFunction,
    ): NetworkProgram? {
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
            return NetworkProgram.Pure
        }
        return NetworkProgram.OpaqueCall(contract.toNetworkEffect())
    }

    private fun lowerLoop(
        loop: IrLoop,
        currentFunction: IrFunction,
    ): NetworkProgram {
        val oneIteration: NetworkProgram = sequence(
            listOf(
                lower(loop.condition, currentFunction),
                lower(loop.body, currentFunction),
            ),
        )
        if (NetworkEffectAnalyzer.analyze(oneIteration) != NetworkEffect.EMPTY) {
            problem(
                key = "loop:${currentFunction.displayName()}:${loop.startOffset}",
                function = currentFunction,
                message = "Cannot infer an effectful loop in ${currentFunction.displayName()}; " +
                    "the current checker requires statically finite network structure.",
            )
        }
        return oneIteration
    }

    private fun lowerChildren(
        element: IrElement,
        currentFunction: IrFunction,
    ): NetworkProgram {
        val children: MutableList<NetworkProgram> = mutableListOf()
        element.acceptChildrenVoid(
            object : IrVisitorVoid() {
                override fun visitElement(element: IrElement) {
                    children += lower(element, currentFunction)
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

    private fun sequence(programs: List<NetworkProgram>): NetworkProgram {
        val steps: List<NetworkProgram> = programs
            .flatMap {
                when (it) {
                    NetworkProgram.Pure -> emptyList()
                    is NetworkProgram.Sequence -> it.steps
                    else -> listOf(it)
                }
            }
        return when (steps.size) {
            0 -> NetworkProgram.Pure
            1 -> steps.single()
            else -> NetworkProgram.Sequence(steps)
        }
    }

    private fun choice(programs: List<NetworkProgram>): NetworkProgram {
        val branches: List<NetworkProgram> = programs
            .flatMap {
                when (it) {
                    is NetworkProgram.Choice -> it.branches
                    else -> listOf(it)
                }
            }
        return when (branches.size) {
            0 -> NetworkProgram.Pure
            1 -> branches.single()
            else -> NetworkProgram.Choice(branches)
        }
    }
}
