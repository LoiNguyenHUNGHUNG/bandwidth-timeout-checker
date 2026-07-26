package io.github.loinguyen.bandwidth.compiler.fir

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionTypeConversionExpression
import org.jetbrains.kotlin.fir.analysis.checkers.processOverriddenFunctionsSafe

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

internal fun CheckerContext.bandwidthSourcePath(): String =
    containingFile?.path ?: "<unknown>"

internal fun FirElement.functionTypeConversionOperand(): FirExpression? =
    (this as? FirFunctionTypeConversionExpression)?.expression

internal fun FirFunction.overriddenEffectContracts(
    session: org.jetbrains.kotlin.fir.FirSession,
    context: CheckerContext,
): List<EffectContract> {
    val function = this as? FirNamedFunction ?: return emptyList()
    return buildList {
        with(context) {
            function.symbol.processOverriddenFunctionsSafe { overridden ->
                overridden.fir.effectContract(session)?.let(::add)
            }
        }
    }
}
