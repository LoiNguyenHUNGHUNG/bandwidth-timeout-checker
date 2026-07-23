package io.github.loinguyen.bandwidth.compiler.ir

import io.github.loinguyen.bandwidth.core.NetworkEffect
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid

/**
 * Infers [NetworkEffect] directly from the recursion-free sequential Kotlin subset.
 *
 * This pass is deliberately path-insensitive: ordinary branches and catches
 * are joined conservatively with sequential effect composition.
 */
internal class KotlinNetworkEffectInference(
    moduleFragment: IrModuleFragment,
    private val messages: MessageCollector,
    private val reportEffects: Boolean,
) {
    private val sourceFunctions: Set<IrFunction> = collectSourceFunctions(moduleFragment)
    private val inferredEffects: MutableMap<IrFunction, KotlinFunctionEffect> = mutableMapOf()
    private val functionsBeingInferred: MutableSet<IrFunction> = mutableSetOf()
    private val reportedProblems: MutableSet<String> = mutableSetOf()
    private val effectVisitor: KotlinNetworkEffectVisitor = KotlinNetworkEffectVisitor(
        sourceFunctions = sourceFunctions,
        inferFunction = ::inferFunctionEffect,
        reportProblem = ::problem,
    )

    fun analyze() {
        sourceFunctions
            .filter { it.body != null }
            .forEach { function ->
                val inferred: KotlinFunctionEffect = inferFunctionEffect(function)
                val inferredEffect: NetworkEffect = inferred.invocation
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
                function.returnType.effectContract()?.let { contract ->
                    val inferredLatent: NetworkEffect =
                        inferred.returned?.invocation ?: NetworkEffect.EMPTY
                    val declaredLatent: NetworkEffect = contract.toNetworkEffect()
                    if (!inferredLatent.isCoveredBy(declaredLatent)) {
                        error(
                            function,
                            "Inferred returned latent effect ${inferredLatent.render()} is not " +
                                "covered by @BandwidthEffect(" +
                                "rMaxBytesPerSecond=${contract.rMaxBytesPerSecond}, " +
                                "nMax=${contract.nMax}) on the return type.",
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

    private fun inferFunctionEffect(function: IrFunction): KotlinFunctionEffect {
        function.downloadContract()?.let { contract ->
            return KotlinFunctionEffect(
                invocation = NetworkEffect.download(
                    maxBytes = contract.maxBytes,
                    completeTimeoutMillis = contract.completeTimeoutMillis,
                ),
            )
        }
        inferredEffects[function]?.let { return it }
        if (!functionsBeingInferred.add(function)) {
            problem(
                key = "recursion:${function.displayName()}",
                function = function,
                message = "Cannot infer recursive network function ${function.displayName()}. " +
                    "Add @BandwidthEffect(rMaxBytesPerSecond, nMax) as a recursion boundary.",
            )
            return KotlinFunctionEffect()
        }

        val result: KotlinFunctionEffect = effectVisitor.inferFunctionBody(function)
        functionsBeingInferred.remove(function)
        inferredEffects[function] = result
        return result
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
}

internal fun EffectContract.toNetworkEffect(): NetworkEffect =
    NetworkEffect.summary(rMaxBytesPerSecond, nMax)

internal fun IrFunction.displayName(): String =
    fqNameWhenAvailable?.asString() ?: name.asString()

internal fun NetworkEffect.render(): String =
    obligations.joinToString(prefix = "{", postfix = "}") {
        "(${it.requiredRateBytesPerSecond}, ${it.concurrency})"
    }
