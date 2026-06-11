package org.jetbrains.qodana.images.dist

/** Thrown on ANY verification failure (GPG import/verify, signer mismatch, sha256 mismatch). */
class VerificationException(
    message: String,
) : Exception(message)
