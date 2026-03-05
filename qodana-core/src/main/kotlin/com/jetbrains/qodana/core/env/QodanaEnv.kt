package com.jetbrains.qodana.core.env

object QodanaEnv {
    const val TOKEN = "QODANA_TOKEN"
    const val LICENSE_ONLY_TOKEN = "QODANA_LICENSE_ONLY_TOKEN"
    const val ENDPOINT = "QODANA_ENDPOINT"
    const val REMOTE_URL = "QODANA_REMOTE_URL"
    const val DOCKER = "QODANA_DOCKER"
    const val TOOL = "QODANA_TOOL"
    const val CONF = "QODANA_CONF"
    const val CLEAR_KEYRING = "QODANA_CLEAR_KEYRING"
    const val ENV = "QODANA_ENV"
    const val JOB_URL = "QODANA_JOB_URL"
    const val BRANCH = "QODANA_BRANCH"
    const val REVISION = "QODANA_REVISION"
    const val CLI_CONTAINER_NAME = "QODANA_CLI_CONTAINER_NAME"
    const val CLI_CONTAINER_KEEP = "QODANA_CLI_CONTAINER_KEEP"
    const val DIST = "QODANA_DIST"
    const val CORETTO_SDK = "QODANA_CORETTO_SDK"
    const val LICENSE = "QODANA_LICENSE"
    const val TREAT_AS_RELEASE = "QODANA_TREAT_AS_RELEASE"
    const val PROJECT_ID_HASH = "QODANA_PROJECT_ID_HASH"
    const val ORGANISATION_ID_HASH = "QODANA_ORGANISATION_ID_HASH"
    const val NUGET_URL = "QODANA_NUGET_URL"
    const val NUGET_USER = "QODANA_NUGET_USER"
    const val NUGET_PASSWORD = "QODANA_NUGET_PASSWORD"
    const val NUGET_NAME = "QODANA_NUGET_NAME"
    const val CLOUD_REQUEST_COOLDOWN = "QODANA_CLOUD_REQUEST_COOLDOWN"
    const val CLOUD_REQUEST_TIMEOUT = "QODANA_CLOUD_REQUEST_TIMEOUT"
    const val CLOUD_REQUEST_RETRIES = "QODANA_CLOUD_REQUEST_RETRIES"

    // Non-Qodana env vars used by the CLI
    const val ANDROID_SDK_ROOT = "ANDROID_SDK_ROOT"
    const val GEM_HOME = "GEM_HOME"
    const val BUNDLE_APP_CONFIG = "BUNDLE_APP_CONFIG"

    // Default endpoint
    const val DEFAULT_ENDPOINT = "https://qodana.cloud"
}
