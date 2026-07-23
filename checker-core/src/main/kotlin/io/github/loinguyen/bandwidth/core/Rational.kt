package io.github.loinguyen.bandwidth.core

import java.math.BigInteger

/**
 * An exact rational number. Effects use this instead of floating point so that
 * size/timeout calculations and feasibility comparisons do not round.
 */
public class Rational private constructor(
    public val numerator: BigInteger,
    public val denominator: BigInteger,
) : Comparable<Rational> {
    init {
        require(denominator.signum() > 0) { "denominator must be positive" }
    }

    public operator fun plus(other: Rational): Rational = of(
        numerator * other.denominator + other.numerator * denominator,
        denominator * other.denominator,
    )

    public operator fun times(multiplier: Int): Rational {
        require(multiplier >= 0) { "multiplier must be non-negative" }
        return of(numerator * multiplier.toBigInteger(), denominator)
    }

    public override fun compareTo(other: Rational): Int =
        (numerator * other.denominator).compareTo(other.numerator * denominator)

    public override fun equals(other: Any?): Boolean =
        other is Rational && numerator == other.numerator && denominator == other.denominator

    public override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

    public override fun toString(): String =
        if (denominator == BigInteger.ONE) numerator.toString() else "$numerator/$denominator"

    public companion object {
        public val ZERO: Rational = of(0, 1)

        public fun of(numerator: Long, denominator: Long = 1): Rational =
            of(numerator.toBigInteger(), denominator.toBigInteger())

        public fun of(numerator: BigInteger, denominator: BigInteger): Rational {
            require(denominator.signum() != 0) { "denominator must not be zero" }

            val sign: BigInteger =
                if (denominator.signum() < 0) BigInteger.valueOf(-1) else BigInteger.ONE
            val signedNumerator: BigInteger = numerator * sign
            val positiveDenominator: BigInteger = denominator * sign
            val divisor: BigInteger = signedNumerator.gcd(positiveDenominator)
            return Rational(signedNumerator / divisor, positiveDenominator / divisor)
        }
    }
}
