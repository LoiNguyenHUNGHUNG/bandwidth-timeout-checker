package io.github.loinguyen.bandwidth.compiler.fir

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirArrayLiteral
import org.jetbrains.kotlin.fir.analysis.checkers.processOverriddenFunctionsSafe

internal fun bandwidthFunctionEffectCheckers(
    inference: KotlinNetworkEffectInference,
): Set<FirDeclarationChecker<FirSimpleFunction>> =
    setOf(BandwidthFunctionEffectChecker(inference))

private class BandwidthFunctionEffectChecker(
    private val inference: KotlinNetworkEffectInference,
) : FirDeclarationChecker<FirSimpleFunction>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirSimpleFunction) {
        inference.analyze(declaration, context, reporter)
    }
}

internal fun CheckerContext.bandwidthSourcePath(): String =
    containingFilePath ?: "<unknown>"

internal fun FirElement.functionTypeConversionOperand(): FirExpression? = null

internal fun FirExpression.annotationArrayElements(): List<FirExpression>? =
    (this as? FirArrayLiteral)?.argumentList?.arguments

internal fun FirFunction.overriddenEffectContracts(
    session: org.jetbrains.kotlin.fir.FirSession,
    context: CheckerContext,
): List<EffectContract> {
    val function = this as? FirSimpleFunction ?: return emptyList()
    return buildList {
        with(context) {
            function.symbol.processOverriddenFunctionsSafe { overridden ->
                overridden.fir.effectContract(session)?.let(::add)
            }
        }
    }
}
