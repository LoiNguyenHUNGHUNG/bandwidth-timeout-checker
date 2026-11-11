package io.github.loinguyen.bandwidth.compiler.fir

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirWrappedArgumentExpression
import org.jetbrains.kotlin.fir.expressions.FirWrappedDelegateExpression
import org.jetbrains.kotlin.fir.expressions.FirWrappedExpression
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.types.coneType

/**
 * Finds the initializer of the one-argument standard `lazy { ... }` delegate
 * for Flow storage. This recognizes syntax and resolution only: callers must
 * still prove that evaluating the callback is network-free and that its
 * returned Flow satisfies the property's collection-effect contract.
 *
 * Custom delegates, mode/lock overloads and opaque initializer values remain
 * unsupported. In particular this is not a general network-free Lazy rule.
 */
internal fun FirProperty.bandwidthPureLazyFlowInitializer(
    session: FirSession,
): FirAnonymousFunctionExpression? {
    if (!isVal || !returnTypeRef.coneType.isBandwidthFlow(session)) return null
    val expression = delegate?.unwrapPureLazyArgument() as? FirFunctionCall ?: return null
    val target = (expression.calleeReference as? FirResolvedNamedReference)
        ?.resolvedSymbol?.fir as? FirFunction ?: return null
    if (target.symbol.callableId.asSingleFqName().asString() != "kotlin.lazy" ||
        target.valueParameters.size != 1 || target.receiverParameter != null ||
        expression.argumentList.arguments.size != 1
    ) return null
    return expression.argumentList.arguments.single().unwrapPureLazyArgument()
        as? FirAnonymousFunctionExpression
}

private fun FirExpression.unwrapPureLazyArgument(): FirExpression = when (this) {
    is FirWrappedDelegateExpression -> if (provideDelegateCall == null) {
        expression.unwrapPureLazyArgument()
    } else {
        this
    }
    is FirWrappedArgumentExpression -> expression.unwrapPureLazyArgument()
    is FirWrappedExpression -> expression.unwrapPureLazyArgument()
    else -> this
}
