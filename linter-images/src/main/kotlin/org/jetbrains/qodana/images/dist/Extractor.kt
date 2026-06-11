package org.jetbrains.qodana.images.dist

import java.nio.file.Path

/**
 * Extracts an IDE distribution archive into [targetDir], flattening the single top-level directory
 * so [targetDir] IS the IDE root that directly contains `product-info.json` (mirrors
 * qodana-engine's `IdeInstaller.resolveInstallDir` single-top-level-dir walk).
 */
interface Extractor {
    fun extractFlattened(
        archive: Path,
        targetDir: Path,
    )
}
