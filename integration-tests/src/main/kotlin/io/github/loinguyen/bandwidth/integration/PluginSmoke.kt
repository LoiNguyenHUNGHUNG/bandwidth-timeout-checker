package io.github.loinguyen.bandwidth.integration

import io.github.loinguyen.bandwidth.annotations.BandwidthAlternative
import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
import io.github.loinguyen.bandwidth.annotations.BoundedScope
import io.github.loinguyen.bandwidth.annotations.NetworkDownload

@NetworkDownload(maxBytes = 8_000_000, completeTimeoutMillis = 10_000)
suspend fun primitiveDownload(): Unit = Unit

@NetworkDownload(maxBytes = 2_000_000, completeTimeoutMillis = 10_000)
suspend fun thumbnailDownload(): Unit = Unit

// Visible function bodies need no effect annotation: the plugin infers them
// from primitive operations and checked higher-order boundaries.
suspend fun loadImage(useLarge: Boolean) {
    thumbnailDownload()
    if (useLarge) {
        primitiveDownload()
    } else {
        thumbnailDownload()
    }
}

suspend fun invokeNetworkCallback(
    @BandwidthEffect(rMaxBytesPerSecond = 800_000, nMax = 1)
    callback: suspend () -> Unit,
) {
    callback()
}

suspend fun recoverImage() {
    try {
        primitiveDownload()
    } catch (_: Exception) {
        thumbnailDownload()
    }
}

@BoundedScope(k = 4)
val downloadScopePlaceholder: Any = Any()

fun comparableAlternative(): String =
    @BandwidthAlternative
    try {
        "large"
    } catch (_: Exception) {
        "small"
    }
