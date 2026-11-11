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
import org.jetbrains.kotlin.fir.declarations.FirAnonymousInitializer
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirProperty

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
        object : BandwidthFunctionDeclarationCheckers(inference) {
            override val basicDeclarationCheckers =
                setOf(BandwidthAnnotationChecker)
            override val propertyCheckers = setOf(BandwidthPropertyEffectChecker(inference))
            override val constructorCheckers = setOf(BandwidthConstructorEffectChecker(inference))
            override val anonymousInitializerCheckers =
                setOf(BandwidthInitializerEffectChecker(inference))
        }
}

private class BandwidthPropertyEffectChecker(
    private val inference: KotlinNetworkEffectInference,
) : FirDeclarationChecker<FirProperty>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirProperty) {
        inference.analyzeProperty(declaration, context, reporter)
    }
}

private class BandwidthConstructorEffectChecker(
    private val inference: KotlinNetworkEffectInference,
) : FirDeclarationChecker<FirConstructor>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirConstructor) {
        inference.analyze(declaration, context, reporter)
    }
}

private class BandwidthInitializerEffectChecker(
    private val inference: KotlinNetworkEffectInference,
) : FirDeclarationChecker<FirAnonymousInitializer>(MppCheckerKind.Common) {
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirAnonymousInitializer) {
        inference.analyzeInitializer(declaration, context, reporter)
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
