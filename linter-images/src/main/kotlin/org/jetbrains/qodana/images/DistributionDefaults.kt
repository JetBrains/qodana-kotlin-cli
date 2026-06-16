package org.jetbrains.qodana.images

/** Public JetBrains distribution feed. A1/A2/A3/A4 reference this; do not inline it per-command. */
const val DEFAULT_DISTRIBUTION_FEED = "https://download.jetbrains.com/qodana/feed"

/** Env var carrying the bearer token for a private feed. Shared by every feed-touching command. */
const val QD_FEED_TOKEN = "QD_FEED_TOKEN"
