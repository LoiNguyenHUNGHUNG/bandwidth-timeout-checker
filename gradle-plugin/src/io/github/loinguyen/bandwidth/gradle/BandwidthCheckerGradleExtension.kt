package io.github.loinguyen.bandwidth.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import javax.inject.Inject

abstract class BandwidthCheckerGradleExtension @Inject constructor(
    objects: ObjectFactory,
) {
    val enabled: Property<Boolean> = objects.property(Boolean::class.java).convention(true)
    val reportEffects: Property<Boolean> = objects.property(Boolean::class.java).convention(false)
    val entryPoints: ListProperty<String> =
        objects.listProperty(String::class.java).convention(emptyList())
}
