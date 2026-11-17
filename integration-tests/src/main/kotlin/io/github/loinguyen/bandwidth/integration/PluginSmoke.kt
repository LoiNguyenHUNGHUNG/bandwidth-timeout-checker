package io.github.loinguyen.bandwidth.integration

import io.github.loinguyen.bandwidth.annotations.BandwidthAlternative
import io.github.loinguyen.bandwidth.annotations.BandwidthEffect
import io.github.loinguyen.bandwidth.annotations.BoundedScope
import io.github.loinguyen.bandwidth.annotations.NetworkDownload

/** Models a large transfer at a library call; no real I/O is performed. */
suspend fun primitiveDownload() {
    @NetworkDownload(maxBytes = 8_000_000, completeTimeoutMillis = 10_000)
    checkNotNull(Unit)
}

/** Models a thumbnail transfer at a library call; the wrapper effect is inferred. */
suspend fun thumbnailDownload() {
    @NetworkDownload(maxBytes = 2_000_000, completeTimeoutMillis = 10_000)
    checkNotNull(Unit)
}

/** Exercises sequential calls followed by conservative branch inference. */
suspend fun loadImage(useLarge: Boolean) {
    thumbnailDownload()
    if (useLarge) {
        primitiveDownload()
    } else {
        thumbnailDownload()
    }
}

/** Invokes a higher-order parameter with a declared bandwidth-effect contract. */
suspend fun invokeNetworkCallback(
    @BandwidthEffect(rMaxBytesPerSecond = 800_000, nMax = 1)
    callback: suspend () -> Unit,
) {
    callback()
}

/** Exercises conservative `try`/`catch` effect inference. */
suspend fun recoverImage() {
    try {
        primitiveDownload()
    } catch (_: Exception) {
        thumbnailDownload()
    }
}

@BoundedScope(k = 4)
val downloadScopePlaceholder: Any = Any()

/** Exercises source-retained `@BandwidthAlternative` annotation discovery. */
fun comparableAlternative(): String =
    @BandwidthAlternative
    try {
        "large"
    } catch (_: Exception) {
        "small"
    }
