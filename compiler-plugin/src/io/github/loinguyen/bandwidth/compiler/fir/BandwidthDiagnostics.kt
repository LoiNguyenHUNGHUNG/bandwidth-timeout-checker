// Kotlin 2.4's custom FIR diagnostic renderer map is still an internal API.
@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package io.github.loinguyen.bandwidth.compiler.fir

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.diagnostics.KtDiagnosticFactoryToRendererMap
import org.jetbrains.kotlin.diagnostics.KtDiagnosticsContainer
import org.jetbrains.kotlin.diagnostics.error1
import org.jetbrains.kotlin.diagnostics.rendering.BaseDiagnosticRendererFactory
import org.jetbrains.kotlin.diagnostics.rendering.Renderer

internal object BandwidthDiagnostics : KtDiagnosticsContainer() {
    val ERROR by error1<PsiElement, String>()

    override fun getRendererFactory(): BaseDiagnosticRendererFactory =
        BandwidthDiagnosticRendererFactory

    init {
        BandwidthDiagnosticRendererFactory.MAP.put(
            ERROR,
            "{0}",
            Renderer { message -> message },
        )
    }
}

private object BandwidthDiagnosticRendererFactory : BaseDiagnosticRendererFactory() {
    override val MAP: KtDiagnosticFactoryToRendererMap =
        KtDiagnosticFactoryToRendererMap("Bandwidth")
}
