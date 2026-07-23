package io.github.loinguyen.bandwidth.compiler.ir

import io.github.loinguyen.bandwidth.core.NetworkEffect
import io.github.loinguyen.bandwidth.core.NetworkEffectAnalyzer
import io.github.loinguyen.bandwidth.core.NetworkProgram
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.IrVariable
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
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
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrVisitor
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/**
 * Lowers the recursion-free sequential Kotlin subset to [NetworkProgram].
 *
 * This pass is deliberately path-insensitive: ordinary branches and catches
 * become [NetworkProgram.Choice], whose effects are joined by the core.
 */
internal class KotlinNetworkProgramLowering(
    moduleFragment: IrModuleFragment,
    private val messages: MessageCollector,
    private val reportEffects: Boolean,
) {
    private val sourceFunctions: Set<IrFunction> = collectSourceFunctions(moduleFragment)
    private val inferredPrograms: MutableMap<IrFunction, NetworkProgram> = mutableMapOf()
    private val functionsBeingInferred: MutableSet<IrFunction> = mutableSetOf()
    private val reportedProblems: MutableSet<String> = mutableSetOf()
    private val programVisitor: IrVisitor<NetworkProgram, IrFunction> = NetworkProgramVisitor()

    fun analyze() {
        sourceFunctions
            .filter { it.body != null }
            .forEach { function ->
                val program: NetworkProgram = inferFunction(function)
                val inferredEffect: NetworkEffect = NetworkEffectAnalyzer.analyze(program)
                function.effectContract()?.let { contract ->
                    val declaredEffect: NetworkEffect = contract.toNetworkEffect()
                    if (!inferredEffect.isCoveredBy(declaredEffect)) {
                        error(
                            function,
                            "Inferred effect ${inferredEffect.render()} is not covered by " +
                                "@BandwidthEffect(rMaxBytesPerSecond=${contract.rMaxBytesPerSecond}, " +
                                "nMax=${contract.nMax}).",
                        )
                    }
                }
                if (reportEffects && inferredEffect != NetworkEffect.EMPTY) {
                    messages.report(
                        CompilerMessageSeverity.INFO,
                        "Inferred bandwidth effect for ${function.displayName()}: " +
                            "${inferredEffect.render()}, " +
                            "ReqBW=${inferredEffect.requiredBandwidthBytesPerSecond()} bytes/s.",
                        function.messageLocation(),
                    )
                }
            }
    }

    private fun inferFunction(function: IrFunction): NetworkProgram {
        function.downloadContract()?.let { contract ->
            return NetworkProgram.Download(
                maxBytes = contract.maxBytes,
                completeTimeoutMillis = contract.completeTimeoutMillis,
            )
        }
        inferredPrograms[function]?.let { return it }
        if (!functionsBeingInferred.add(function)) {
            problem(
                key = "recursion:${function.displayName()}",
                function = function,
                message = "Cannot infer recursive network function ${function.displayName()}. " +
                    "Add @BandwidthEffect(rMaxBytesPerSecond, nMax) as a recursion boundary.",
            )
            return NetworkProgram.Pure
        }

        val result: NetworkProgram = lower(function.body, function)
        functionsBeingInferred.remove(function)
        inferredPrograms[function] = result
        return result
    }

    private fun lower(element: IrElement?, currentFunction: IrFunction): NetworkProgram =
        element?.accept(programVisitor, currentFunction) ?: NetworkProgram.Pure

    /**
     * Return-valued Kotlin IR visitor for the executable sequential subset.
     *
     * Each supported Kotlin construct has an explicit extension point. The
     * default implementation preserves evaluation order by lowering immediate
     * children as a sequence.
     */
    private inner class NetworkProgramVisitor : IrVisitor<NetworkProgram, IrFunction>() {
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
    }

    private fun lowerCall(call: IrCall, currentFunction: IrFunction): NetworkProgram {
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
        if (reportedProblems.add(key)) {
            error(function, message)
        }
    }

    private fun error(function: IrFunction, message: String) {
        messages.report(
            CompilerMessageSeverity.ERROR,
            message,
            function.messageLocation(),
        )
    }

    private fun EffectContract.toNetworkEffect(): NetworkEffect =
        NetworkEffect.summary(rMaxBytesPerSecond, nMax)

    private fun IrFunction.displayName(): String =
        fqNameWhenAvailable?.asString() ?: name.asString()

    private fun NetworkEffect.render(): String =
        obligations.joinToString(prefix = "{", postfix = "}") {
            "(${it.requiredRateBytesPerSecond}, ${it.concurrency})"
        }

    private fun collectSourceFunctions(moduleFragment: IrModuleFragment): Set<IrFunction> =
        buildSet {
            moduleFragment.acceptChildrenVoid(
                object : IrVisitorVoid() {
                    override fun visitElement(element: IrElement) {
                        element.acceptChildrenVoid(this)
                    }

                    override fun visitFunction(declaration: IrFunction) {
                        add(declaration)
                        declaration.acceptChildrenVoid(this)
                    }
                },
            )
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
