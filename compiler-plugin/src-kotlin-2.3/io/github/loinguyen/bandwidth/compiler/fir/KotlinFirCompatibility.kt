package io.github.loinguyen.bandwidth.compiler.fir

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.expressions.FirExpression

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
