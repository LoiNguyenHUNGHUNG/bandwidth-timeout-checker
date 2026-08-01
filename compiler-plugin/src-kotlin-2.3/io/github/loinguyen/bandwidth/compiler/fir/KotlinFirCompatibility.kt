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

/** Creates the Kotlin 2.3 function-checker set backed by [inference]. */
internal fun bandwidthFunctionEffectCheckers(
    inference: KotlinNetworkEffectInference,
): Set<FirDeclarationChecker<FirSimpleFunction>> =
    setOf(BandwidthFunctionEffectChecker(inference))

private class BandwidthFunctionEffectChecker(
    private val inference: KotlinNetworkEffectInference,
) : FirDeclarationChecker<FirSimpleFunction>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    /** Runs bandwidth-effect inference for [declaration]. */
    override fun check(declaration: FirSimpleFunction) {
        inference.analyze(declaration, context, reporter)
    }
}

/** Returns the current Kotlin 2.3 source path or a stable fallback label. */
internal fun CheckerContext.bandwidthSourcePath(): String =
    containingFilePath ?: "<unknown>"

/**
 * Returns the operand of a function-type conversion.
 *
 * Kotlin 2.3 exposes no corresponding FIR wrapper, so this adapter always
 * returns `null`.
 */
internal fun FirElement.functionTypeConversionOperand(): FirExpression? = null

/** Returns the elements of a Kotlin 2.3 FIR array literal, if this is one. */
internal fun FirExpression.annotationArrayElements(): List<FirExpression>? =
    (this as? FirArrayLiteral)?.argumentList?.arguments

/** Collects inherited effect contracts from every safely resolved override. */
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
