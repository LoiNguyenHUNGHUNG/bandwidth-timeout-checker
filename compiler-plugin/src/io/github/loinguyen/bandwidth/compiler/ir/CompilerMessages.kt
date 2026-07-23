package io.github.loinguyen.bandwidth.compiler.ir

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.util.fileOrNull

internal fun IrDeclaration.messageLocation(): CompilerMessageSourceLocation? {
    val file = fileOrNull ?: return null
    if (startOffset < 0) return CompilerMessageLocation.create(file.fileEntry.name)
    return CompilerMessageLocation.create(
        file.fileEntry.name,
        file.fileEntry.getLineNumber(startOffset) + 1,
        file.fileEntry.getColumnNumber(startOffset) + 1,
        "",
    )
}
