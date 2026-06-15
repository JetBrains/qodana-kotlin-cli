package org.jetbrains.qodana.images

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context

class ImageToolCommand : CliktCommand(name = "image-tool") {
    override fun help(context: Context) = "Builds Qodana linter image artifacts (dist, inner-CLI, layout checks)."

    override fun run() = Unit
}
