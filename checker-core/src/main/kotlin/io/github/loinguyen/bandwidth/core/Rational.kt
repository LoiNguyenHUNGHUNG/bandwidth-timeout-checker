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

    /**
     * Adds [other] to this value without losing precision.
     *
     * @return the normalized exact sum.
     */
    public operator fun plus(other: Rational): Rational = of(
        numerator * other.denominator + other.numerator * denominator,
        denominator * other.denominator,
    )

    /**
     * Multiplies this value by a non-negative integer.
     *
     * @param multiplier the integer scale factor.
     * @return the normalized exact product.
     * @throws IllegalArgumentException if [multiplier] is negative.
     */
    public operator fun times(multiplier: Int): Rational {
        require(multiplier >= 0) { "multiplier must be non-negative" }
        return of(numerator * multiplier.toBigInteger(), denominator)
    }

    /**
     * Compares this value with [other] by exact cross multiplication.
     *
     * @return a negative value, zero, or a positive value as this value is less
     * than, equal to, or greater than [other].
     */
    public override fun compareTo(other: Rational): Int =
        (numerator * other.denominator).compareTo(other.numerator * denominator)

    /** Returns whether [other] has the same normalized numerator and denominator. */
    public override fun equals(other: Any?): Boolean =
        other is Rational && numerator == other.numerator && denominator == other.denominator

    /** Returns a hash code consistent with [equals]. */
    public override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

    /** Returns an integer string or a normalized `numerator/denominator` string. */
    public override fun toString(): String =
        if (denominator == BigInteger.ONE) numerator.toString() else "$numerator/$denominator"

    public companion object {
        public val ZERO: Rational = of(0, 1)

        /**
         * Creates a normalized rational from signed 64-bit components.
         *
         * @param numerator the numerator.
         * @param denominator the non-zero denominator.
         * @return the reduced rational with a positive denominator.
         * @throws IllegalArgumentException if [denominator] is zero.
         */
        public fun of(numerator: Long, denominator: Long = 1): Rational =
            of(numerator.toBigInteger(), denominator.toBigInteger())

        /**
         * Creates a normalized rational from arbitrary-precision components.
         *
         * @param numerator the numerator.
         * @param denominator the non-zero denominator.
         * @return the reduced rational with a positive denominator.
         * @throws IllegalArgumentException if [denominator] is zero.
         */
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
