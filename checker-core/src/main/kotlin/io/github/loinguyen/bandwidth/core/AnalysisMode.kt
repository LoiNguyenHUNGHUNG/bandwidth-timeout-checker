package io.github.loinguyen.bandwidth.core

/** Immutable choice of effect algebra for one analysis; no process-wide state is used. */
public enum class AnalysisMode(public val optionValue: String) {
    /** Pareto-normalized pair sets, preserving rate/concurrency correlations. */
    DETAILED("detailed"),
    /** Independent rate/concurrency maxima after each composition, per lifetime. */
    SIMPLIFIED("simplified"),
    /** Retains distinct pairs, without comparing placeholder rates numerically. */
    DIAGNOSTIC("diagnostic");

    public companion object {
        /** Parses the compiler/Gradle option, rejecting misspellings rather than changing analyses. */
        public fun parse(value: String): AnalysisMode = entries.firstOrNull { it.optionValue == value }
            ?: throw IllegalArgumentException(
                "Invalid analysisMode '$value'; expected detailed, simplified, or diagnostic",
            )
    }
}
