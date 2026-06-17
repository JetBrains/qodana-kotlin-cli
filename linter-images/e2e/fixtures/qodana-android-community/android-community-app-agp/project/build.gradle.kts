// Root build script. Plugins are declared (apply false) here and applied in :app so the
// AGP + Kotlin versions resolve once. Pinned for assertion stability (QD-15022).
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
