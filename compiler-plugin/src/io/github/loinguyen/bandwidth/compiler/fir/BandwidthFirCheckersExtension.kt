package io.github.loinguyen.bandwidth.compiler.fir

import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirDeclarationChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirDeclaration

internal class BandwidthFirCheckersExtension(
    session: FirSession,
    messages: MessageCollector,
    reportEffects: Boolean,
    applicationEntryPoints: ApplicationEntryPointEffects,
) : FirAdditionalCheckersExtension(session) {
    private val inference = KotlinNetworkEffectInference(
        session = session,
        messages = messages,
        reportEffects = reportEffects,
        applicationEntryPoints = applicationEntryPoints,
    )

    override val declarationCheckers: DeclarationCheckers =
        object : DeclarationCheckers() {
            override val basicDeclarationCheckers =
                setOf(BandwidthAnnotationChecker)
            override val simpleFunctionCheckers =
                bandwidthFunctionEffectCheckers(inference)
        }
}

private object BandwidthAnnotationChecker :
    FirDeclarationChecker<FirDeclaration>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    /** Reports every invalid bandwidth annotation attached to [declaration]. */
    override fun check(declaration: FirDeclaration) {
        declaration.validateBandwidthAnnotations(context.session).forEach { problem ->
            reporter.reportOn(
                problem.source ?: declaration.source,
                BandwidthDiagnostics.ERROR,
                problem.message,
                context,
            )
        }
    }
}
