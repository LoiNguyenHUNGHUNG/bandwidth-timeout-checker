package io.github.loinguyen.bandwidth.integration

import io.github.loinguyen.bandwidth.annotations.BandwidthAlternative
import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
import io.github.loinguyen.bandwidth.annotations.BoundedScope
import io.github.loinguyen.bandwidth.annotations.NetworkDownload

@NetworkDownload(maxBytes = 8_000_000, completeTimeoutMillis = 10_000)
suspend fun primitiveDownload(): Unit = Unit

@BandwidthEffect(rMaxBytesPerSecond = 800_000, nMax = 1)
suspend fun opaqueNetworkOperation(): Unit = Unit

fun invokeNetworkCallback(
    @BandwidthEffect(rMaxBytesPerSecond = 800_000, nMax = 1)
    callback: suspend () -> Unit,
): suspend () -> Unit = callback

@BoundedScope(k = 4)
val downloadScopePlaceholder: Any = Any()

fun comparableAlternative(): String =
    @BandwidthAlternative
    try {
        "large"
    } catch (_: Exception) {
        "small"
    }
