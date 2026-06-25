// Root build script. Plugins are declared (apply false) here and applied in :app so the
// AGP + Kotlin versions resolve once. Pinned for assertion stability (QD-15022).
plugins {
    id("com.android.application") version "9.2.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
}
