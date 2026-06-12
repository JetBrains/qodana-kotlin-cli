package org.jetbrains.qodana.images

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import org.jetbrains.qodana.images.cli.InstallCliCommand
import org.jetbrains.qodana.images.dist.DistVerifier
import org.jetbrains.qodana.images.dist.FeedClient
import org.jetbrains.qodana.images.dist.TarGzExtractor
import org.jetbrains.qodana.images.dist.VerifyDistLayoutCommand
import org.jetbrains.qodana.images.process.ProcessCommandRunner

/**
 * Assembles the `image-tool` root command with production collaborators (canonical factory).
 * One real `ProcessCommandRunner` backs every subcommand (curl/gpg/sha256sum/tar through the port);
 * there is no separate downloader.
 */
fun buildImageTool(): CliktCommand {
    val runner = ProcessCommandRunner()
    return ImageToolCommand().subcommands(
        ProvisionDistCommand(
            feedClient = FeedClient(runner),
            verifier = DistVerifier(runner),
            extractor = TarGzExtractor(),
            getEnv = System::getenv,
        ),
        InstallCliCommand(runner = runner),
        VerifyDistLayoutCommand(),
        VerifyPinCommand(
            feedClient = FeedClient(runner),
            verifier = DistVerifier(runner),
            getEnv = System::getenv,
        ),
    )
}

fun main(args: Array<String>) = buildImageTool().main(args)
