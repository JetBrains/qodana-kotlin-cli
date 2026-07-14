package org.jetbrains.qodana.images

import java.time.Instant

/** A registry tag with its push time (from the registry API), for retention decisions. */
data class RegistryTag(
    val name: String,
    val pushed: Instant,
)
