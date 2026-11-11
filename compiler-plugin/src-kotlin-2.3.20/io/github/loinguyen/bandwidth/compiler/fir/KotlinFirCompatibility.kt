package io.github.loinguyen.bandwidth.compiler.fir

import io.github.loinguyen.bandwidth.core.AnalysisMode
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.expressions.FirCollectionLiteral
import org.jetbrains.kotlin.fir.analysis.checkers.processOverriddenFunctionsSafe

/** Creates the Kotlin 2.3.20 function-checker set backed by [inference]. */
internal fun bandwidthFunctionEffectCheckers(
    inference: KotlinNetworkEffectInference,
): Set<FirDeclarationChecker<FirNamedFunction>> =
    setOf(BandwidthFunctionEffectChecker(inference))

private class BandwidthFunctionEffectChecker(
    private val inference: KotlinNetworkEffectInference,
) : FirDeclarationChecker<FirNamedFunction>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirNamedFunction) {
        inference.analyze(declaration, context, reporter)
    }
}

/** Kotlin 2.3.20 retains the source-path API from the earlier 2.3 compiler. */
internal fun CheckerContext.bandwidthSourcePath(): String =
    containingFilePath ?: "<unknown>"

/** Kotlin 2.3.20 does not expose a FIR function-type-conversion wrapper. */
internal fun FirElement.functionTypeConversionOperand(): FirExpression? = null

/** Kotlin 2.3.20 renamed FIR array literals to collection literals. */
internal fun FirExpression.annotationArrayElements(): List<FirExpression>? =
    (this as? FirCollectionLiteral)?.argumentList?.arguments

/** Collects inherited effect contracts from every safely resolved override. */
internal fun FirFunction.overriddenEffectContracts(
    session: org.jetbrains.kotlin.fir.FirSession,
    context: CheckerContext,
    analysisMode: AnalysisMode,
): List<EffectContract> {
    val function = this as? FirNamedFunction ?: return emptyList()
    return buildList {
        with(context) {
            function.symbol.processOverriddenFunctionsSafe { overridden ->
                overridden.fir.effectContract(session, analysisMode)?.let(::add)
            }
        }
    }
}

/** Collects inherited contracts for supported Flow-valued results. */
internal fun FirFunction.overriddenReturnedEffectContracts(
    session: org.jetbrains.kotlin.fir.FirSession,
    context: CheckerContext,
    analysisMode: AnalysisMode,
): List<EffectContract> {
    val function = this as? FirNamedFunction ?: return emptyList()
    return buildList {
        with(context) {
            function.symbol.processOverriddenFunctionsSafe { overridden ->
                val returnType = overridden.fir.returnTypeRef
                if (returnType.coneType.isBandwidthFlow(session)) {
                    returnType.effectContract(session, analysisMode)?.let(::add)
                }
            }
        }
    }
}
