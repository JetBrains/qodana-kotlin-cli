# Go → Kotlin: Exhaustive Gap Analysis

**Go**: 320 top-level test functions across 61 files (many with table-driven subtests)
**Kotlin**: 331 tests across 45 files

Module structure: `qodana-core`, `qodana-engine`, `qodana-cli`, `qodana-clang`, `qodana-cdnet`

---

## 1. Missing Functionality (Go feature exists, no Kotlin implementation)

### 1.1 IDE Installer/Downloader

**Go**: `internal/core/startup/installers_test.go` — 5 tests

- `TestGetIde`, `TestDownloadAndInstallIDE`, `TestInstallIdeFromZip`, `TestExtractArchive`, `TestExtractArchiveBadPath`
  **Status**: No `IdeInstaller` class in Kotlin. `PrepareHost.kt` exists but has no IDE download logic.

### 1.2 EAP Expiration Check

**Go**: `internal/platform/eap_test.go` — 4 tests

- `TestCheckEAP_NotEAP`, `TestCheckEAP_ValidEAP`, `TestCheckEAP_ExpiredEAP`, `TestCheckEAP_InvalidDateFormat`
  **Status**: `Linters.kt` has `isEapOnly` flag but zero expiry-date checking logic.

### 1.3 Device ID Generation

**Go**: `internal/core/core_test.go` — `Test_setDeviceID` (4 subtests: Empty, FromGit, FromQodanaRemoteUrl, FromEnv)
**Status**: Not implemented anywhere.

### 1.4 Cache Sync

**Go**: `internal/core/startup/prepare_test.go` — 4 tests

- `TestSyncCacheSyncConfig`, `TestSyncCacheSyncIdea`, `TestSyncCacheSyncIdeaNoOverwrite`, `TestSyncCacheSyncNoCacheNoProblem`
  **Status**: Not implemented. `PrepareHost.kt` only creates dirs and optionally clears cache.

### 1.5 XML Config Generation (JDK table, Android defaults)

**Go**: `internal/core/startup/xml_test.go` — 3 tests

- `TestJdkTableXml`, `TestAndroidProjectDefaultXml`, `TestWriteFileIfNew`
  **Status**: Not implemented.

### 1.6 SARIF Versioning / Git Metadata Enrichment

**Go**: `internal/platform/sarifVersioning_test.go` — 10 tests

- `TestGetBranchName`, `TestGetRevisionId`, `TestGetRevisionId_NoRepo`, `TestGetRepositoryUri`, `TestGetAuthorEmail`, `TestGetAuthorEmail_NoRepo`, `TestGetLastAuthorName`, `TestGetLastAuthorName_NoRepo`, `TestGetVersionDetails`, `TestGetVersionDetails_WithEnvOverrides`
  **Status**: Not implemented. No SARIF metadata enrichment code exists.

### 1.7 SARIF Short Report / Print Problems / Merge

**Go**: `internal/platform/sarif_test.go` — 18 tests

- `TestMakeShortSarif`, `TestMakeShortSarif_Additional`, `TestPrintSarifProblem`, `TestGetSeverity`, `TestRemoveDuplicates`, `TestGetFingerprint`, `TestGetRuleDescription`, `TestFindSarifFiles`, `TestGetSarifPath`, `TestGetShortSarifPath`, `TestMergeSarifReports`, `TestReadReport`, `TestReadReportFromString`, `TestWriteReport`, `TestJobUrl`, `TestReportId`, `TestRunGUID`
  **Status**: `QodanaSarifService` wraps the qodana-sarif library for read/write/merge/baseline, but lacks short-sarif generation, fingerprint extraction, severity mapping, problem printing, duplicate removal, GUID assignment.

### 1.8 Container Image Validation / User Selection

**Go**: `internal/core/container_test.go` — 10 tests

- `TestCheckImage`, `TestImageChecks`, `TestSelectUser`, `TestEncodeAuthToBase64`, `TestExtractDockerVolumes`, `TestFixDarwinCaches`, `TestGenerateDebugDockerRunCommand`, `TestGenerateDebugDockerRunCommand_FiltersTokens`, `TestIsDockerUnauthorizedError`, `TestRemovePortSocket`
  **Status**: `ContainerScan.kt` builds specs and runs containers, but has no image validation, user selection logic, auth encoding, Darwin cache fixing, or debug command generation.

### 1.9 Embedded Tool Mounting

**Go**: `internal/platform/embed_test.go` — 4 tests

- `TestMount`, `TestGetToolsMountPath`, `TestProcessAuxiliaryTool`, `TestIsInDirectory`
  **Status**: Not implemented as a generic system. ClangLinter/CdnetLinter have ad-hoc `mountTools()`.

### 1.10 License Setup Orchestration

**Go**: `internal/core/core_test.go` — `TestSetupLicense`, `TestSetupLicenseToken` (6+ subtests)
**Go**: `internal/cloud/license_test.go` — `TestRequestLicenseData`, `TestSetupLicenseToken` (5 tests)
**Go**: `internal/core/startup/license_test.go` — `TestAllCommunityNames`
**Status**: `LicenseValidator` does HTTP validation but lacks full setup orchestration (community name checks, license file writing, token file management).

### 1.11 Cloud Version Parsing / API Client

**Go**: `internal/cloud/cloud_test.go` — 10 tests

- `TestToCloudVersion`, `TestExtractVersions`, `TestApiVersionMismatchError`, `TestGetCloudTeamsPageUrl`, `TestGetReportUrl`, `TestParseProjectName`, `TestParseRawURL`, `TestGetEnvWithDefault`, `TestGetEnvWithDefaultInt`, `TestGetProjectByBadToken`
  **Go**: `internal/cloud/endpoints_test.go` — 7 tests
- `TestEndpoint`, `TestObtainEndpointAPI`, `TestNewCloudRequest`, `TestNewCloudApiClient`, `TestNewLintersApiClient`, `TestAPIError`, `TestWrongVersion`
  **Status**: `CloudClient.kt` has basic fetch+retry. Missing version parsing, API version mismatch handling, project/team URL generation, endpoint construction edge cases.

### 1.12 Terminal / Message Output

**Go**: `internal/platform/msg/output_test.go` — 22 tests

- `TestPrintHeader`, `TestPrintLines`, `TestPrintLinterLog`, `TestPrintPath`, `TestPrintProblem`, `TestPrintProcess`, `TestSpin`, `TestStartQodanaSpinner`, `TestPrimary`, `TestPrimaryBold`, `TestInfoString`, `TestSuccessMessage`, `TestWarningMessage`, `TestWarningMessageCI`, `TestErrorMessage`, `TestEmptyMessage`, `TestFormatMessageForCI`, `TestGetProblemsFoundMessage`, `TestGetTerminalWidth`, `TestIsInteractive`, `TestUpdateText`, `TestDisableColor`
  **Status**: `MordantTerminal.kt` exists with basic print/warn/error. Zero tests. Completely missing: formatted problem printing, CI-specific formatting, spinner control, color toggling, terminal width detection.

### 1.13 Environment / CI Detection

**Go**: `internal/platform/qdenv/qdenv_test.go` — 18 tests

- `TestGetEnv`, `TestSetEnv`, `TestGetEnvWithOsEnv`, `TestIsContainer`, `TestIsBitBucket`, `TestIsBitBucketPipe`, `TestIsGitLab`, `TestGetAzureJobUrl`, `TestGetBitBucketJobUrl`, `TestGetBitBucketCommit`, `TestGetBitBucketRepoFunctions`, `TestGetSpaceRemoteUrl`, `TestValidateBranch`, `TestValidateJobUrl`, `TestValidateRemoteUrl`, `TestInitializeAndGetQodanaGlobalEnv`, `TestEmptyEnvProvider`, `TestUnsetRubyVariables`
  **Status**: `CiDetector.kt` has 10 tests but only covers basic CI name detection. Missing: BitBucket-specific functions, Azure URL parsing, Space remote URL, branch/job URL validation, env provider pattern, Ruby variable unsetting.

### 1.14 Scan Context / Options Builder

**Go**: `internal/core/corescan/context_test.go` — 12 tests

- `TestContextBuilder_Build`, `TestContext_DetermineRunScenario`, `TestContext_GetAnalysisTimeout`, `TestContext_PropertiesAndFlags`, `TestContext_StartHash`, `TestContext_VmOptionsPath`, `TestContext_InstallPluginsVmOptionsPath`, `TestContext_LocalQodanaYamlExists`, `TestContext_ProjectDirPathRelativeToRepositoryRoot`, `TestArrayCopy`, `TestIsScopedScenario`, `TestYamlConfig`
  **Go**: `internal/core/corescan/env_test.go` — `Test_ExtractEnvironmentVariables`
  **Status**: `ScanContext` is a data class with 9 tests (basics). Missing: scenario determination logic, analysis timeout calculation, VM options path, plugin install path, project-dir-relative-to-repo, env extraction.

### 1.15 Product/Linter Methods

**Go**: `internal/platform/product/product_test.go` — 28 tests

- `TestProduct_GetVersionBranch`, `TestProduct_VersionChecks`, `TestProduct_isNotOlderThan`, `TestProduct_IdeBin`, `TestProduct_javaHome`, `TestProduct_JbrJava`, `TestProduct_VmOptionsEnv`, `TestProduct_CustomPluginsPath`, `TestProduct_DisabledPluginsFilePath`, `TestProduct_ParentPrefix`, `TestProduct_isRuby`, `TestGetProductNameFromCode`, `TestGetScriptSuffix`, `TestToQodanaCode`, `TestFindIde`, `TestFindLinterByImage`, `TestFindLinterByName`, `TestFindLinterByProductCode`, `TestIsEap`, `TestLangsToLinters_Coverage`, `TestAllLinters_NotEmpty`, `TestAllLintersFiltered`, `TestLinter_Image`, `TestLinter_NativeAnalyzer`, `TestLinter_DockerAnalyzer`, `TestDockerAnalyzer_Methods`, `TestNativeAnalyzer_Methods`, `TestPathNativeAnalyzer_Methods`
  **Status**: `LintersTest.kt` has 19 tests, `AnalyzerTest.kt` has 5 tests. Missing: version branch/checks/comparison (6 tests), IDE binary paths, JBR/Java home, VM options env, custom plugins path, disabled plugins path, parent prefix, Ruby detection, find by image/name/code (5 tests), script suffix, langs-to-linters coverage.

### 1.16 String Utilities

**Go**: `internal/platform/strutil/strutil_test.go` — 11 tests

- `TestAppend`, `TestContains`, `TestContainsWinSpecialChar`, `TestGetQuotedPath`, `TestIsStringQuoted`, `TestLower`, `TestQuoteForWindows`, `TestQuoteIfSpace`, `TestRemove`, `TestReverse`, `TestSafeSplit`
  **Status**: Not implemented as a utility module. Some logic inlined elsewhere.

### 1.17 File Operations / Archive Utilities

**Go**: `internal/platform/utils/fileops_test.go` — 6 tests

- `TestCopyDir`, `TestGetFileSha256`, `TestGetSha256`, `TestWalkArchive`, `TestWalkTarGzArchive`, `TestWalkZipArchive`
  **Go**: `internal/platform/utils/utils_test.go` — 7 tests
- `TestAppendToFile`, `TestCheckDirFiles`, `TestCopyFile`, `TestFindFiles`, `TestGetDefaultUser`, `TestIsInstalled`, `TestIsProcess`
  **Status**: `NioFileSystem` has 13 tests covering basic ops. Missing: SHA256, archive walking (tar.gz/zip), directory copy, default user, process detection.

### 1.18 Command Execution Utilities

**Go**: `internal/platform/utils/cmd_test.go` — 8 tests

- `TestBootstrap`, `TestRunCmd`, `TestRunCmdRedirectOutput`, `TestRunCmdWithTimeout`, `TestGetCwdPath`, `TestCopyToChannel`, `TestClosePipe`, `TestClosePipes`
  **Status**: `SystemProcessRunner` has 8 tests. Missing: bootstrap execution, pipe management, output redirection.

### 1.19 Effective Config / YAML loading

**Go**: `internal/platform/qdyaml/yaml_test.go` — 7 tests

- `TestLoadQodanaYaml`, `TestLoadQodanaYamlByFullPath`, `TestGetLocalNotEffectiveQodanaYamlFullPath`, `TestQodanaYaml_IsDotNet`, `TestQodanaYaml_Sort`, `TestSetQodanaDotNet`, `TestDotNet_IsEmpty`
  **Go**: `internal/platform/effectiveconfig/config_test.go` — 2 tests
- `TestSuccess`, `TestError`
  **Status**: `EffectiveConfigTest.kt` has 12 tests, `QodanaYamlTest.kt` has 8 tests. Missing: isDotNet check, yaml sort, dotnet-specific yaml methods.

### 1.20 Analyzer Selection / CLI Options

**Go**: `internal/platform/commoncontext/` — 12 tests across 5 files

- `TestAnalyzerCliOptions`, `TestNativePathAnalyzerParams`, `TestAnalyzerQodanaYamlOptions`, `TestSelectAnalyzer`, `TestReadIdeaDir`, `TestIsAndroidProject`, `TestNoCache`, `Test_runCmd`, `TestComputeCommonRepositoryRootValidationWithRealFiles`, `TestContainsDotNetProjects`, `TestContainsUnityProjects`, `TestDirLanguagesExcluded`
  **Status**: No analyzer selection logic. No language recognition. No Android/Unity/DotNet project detection.

### 1.21 Failure Thresholds (severity combos)

**Go**: `internal/platform/thresholds_test.go` — `TestFailureThresholds` (many subtests with severity combos)
**Status**: `ReportProcessorTest.kt` has basic threshold tests (8). Missing: per-severity threshold combos (the Go test has extensive table-driven tests).

### 1.22 Token Loading

**Go**: `internal/platform/tokenloader/token_loader_test.go` — 4 tests

- `TestLoadCloudUploadToken`, `TestSaveAndGetCloudToken`, `TestGetTokenFromKeychain`, `TestIsCloudTokenRequired`
  **Status**: `TokenStoreTest.kt` has 12 tests covering EnvTokenStore and FileTokenStore. Missing: keychain integration, `IsCloudTokenRequired` logic.

### 1.23 Third-Party Scan Context

**Go**: `internal/platform/thirdpartyscan/context_test.go` — 6 tests

- `TestContextBuilder_Build`, `TestContext_IsCommunity`, `TestContext_ClangPath`, `TestContext_Property`, `TestLinterInfo_GetMajorVersion`, `TestYamlConfig`
  **Status**: `ThirdPartyScanContext` is a data class, zero tests. Missing all validation.

### 1.24 Contributors / Mailmap

**Go**: `internal/core/contributors_test.go` — 6 tests
**Go**: `internal/core/contributors_mailmap_test.go` — 2 tests

- `TestGetContributors`, `TestParseCommits`, `TestParseCommitsEdgeCases`, `TestAuthorIsBot`, `TestAuthorGetId`, `TestToJSON`, `TestGetContributorsMailmapNameMapping`, `TestGetContributorsWithMailmap`
  **Status**: `ContributorAnalyzerTest.kt` has 9 tests. Missing: mailmap support (2 tests), edge cases.

### 1.25 Git Operations

**Go**: `internal/platform/git/git_test.go` — 13 tests
**Go**: `internal/platform/git/git_changes_test.go` — 1 test

- `TestBranch`, `TestCheckout`, `TestCheckoutAndUpdateSubmodule`, `TestClean`, `TestCurrentRevision`, `TestGitFunctionalityChange`, `TestGitRunReportsErrors`, `TestRemoteUrl`, `TestReset`, `TestResetBack`, `TestRevisions`, `TestRevParse`, `TestSubmoduleUpdate`, `TestChangesCalculation`
  **Status**: `SystemGitClientTest.kt` has 11 tests. Missing: submodule operations (2), clean, reset back, changes calculation.

### 1.26 Run/System Orchestration

**Go**: `internal/core/core_test.go` — 14+ tests (beyond device ID / license)

- `Test_Bootstrap`, `Test_createUser`, `Test_ideaExitCode`, `Test_isProcess`, `Test_Properties`, `Test_syncIdeaCache`, `TestCliArgs`, `TestDockerCliArgs`, `TestGetPluginIds`, `TestLegacyFixStrategies`, `TestQodanaOptions_RequiresToken`, `TestScanFlags_Script`, `TestWriteConfig`
  **Go**: `internal/core/system_test.go` — 7 tests
- `TestCheckForUpdates`, `TestGetLatestVersion`, `TestGetScanStages`, `TestIsHomeDirectory`, `TestOpenDir`, `TestReverseScopedScript`, `TestScopedScript`
  **Go**: `internal/core/run_scenario_test.go` — 1 test
  **Go**: `internal/platform/run_test.go` — 4 tests
- `TestCopyQodanaYamlToLogDir`, `TestPrintLinterLicense`, `TestPrintQodanaLogo`, `TestQodanaLogo`
  **Status**: Scattered partial coverage. Missing: bootstrap execution, user creation, CLI arg building (Docker args), plugin ID extraction, legacy fix strategies, update checking, scan stages, scoped/reverse-scoped scripts, YAML-to-log copy, logo printing.

### 1.27 Algorithms / Filter / Unique

**Go**: `internal/platform/algorithm/algorithm_test.go` — 2 tests (10 subtests)
**Status**: Not implemented.

### 1.28 SARIF Schema (PropertyBag)

**Go**: `internal/sarif/schema_test.go` — 3 tests

- `TestPropertyBag_MarshalJSON`, `TestPropertyBag_UnmarshalJSON`, `TestPropertyBag_RoundTrip`
  **Status**: Using qodana-sarif library instead of custom schema. Not applicable.

### 1.29 Publisher Args

**Go**: `internal/platform/publisher_test.go` — `TestGetPublisherArgs`
**Status**: `PublisherAdapter` uses qodana-publisher library directly. Different approach but untested.

### 1.30 Container Client / Log Level

**Go**: `internal/platform/qdcontainer/container_test.go` — `TestClientCreationKeepsLogLevel`
**Status**: `DockerJavaEngine` has integration tests but no log-level preservation test.

### 1.31 Scan Options Parsing

**Go**: `internal/platform/cmd/scan_options_test.go` — `TestGetShowReportPortParsing`
**Status**: Not implemented.

### 1.32 Options / Fetch Analyzer Settings

**Go**: `internal/platform/options_test.go` — `TestFetchAnalyzerSettings`
**Status**: Not implemented.

### 1.33 JBR/Tooling

**Go**: `internal/tooling/qodana_jbr_test.go` — `TestGetQodanaJBRPath`
**Status**: Intentionally dropped (GraalVM native replaces JBR).

### 1.34 3rd Party Linter Integration

**Go**: `test_linter/3rd_party_linter_jbr_integration_test.go` — `TestQodana3rdPartyLinterWithMockedCloud`
**Status**: Not implemented.

### 1.35 Baseline Tracking (bundled `baseline-cli`)

**Go**: bundles `baseline-cli-1.0.4.jar` (~12 MB) via `//go:embed`; invoked from `internal/platform/baseline.go:36`.
**Status**: No Kotlin equivalent in [gradle/libs.versions.toml](../gradle/libs.versions.toml). Baseline-aware SARIF comparison + `--baseline` flag handling are absent.
**Tracked in**: [QD-14726](https://youtrack.jetbrains.com/issue/QD-14726).

### 1.36 Bundled Web UI (`qodana view` rendering)

**Go**: bundles `qodana-web-ui-0.12.5.jar` (~1.4 MB) — Kotlin reads it from a `web-ui.zip` classpath resource via [WebUiExtractor.kt:12](../qodana-engine/src/main/kotlin/org/jetbrains/qodana/engine/fs/WebUiExtractor.kt#L12), but the resource is absent from the source tree. `qodana view` currently fails on both JVM and native binaries when it tries to extract the UI assets.
**Status**: Need to either add the artifact as a Maven dependency, package the zip during the build, or replace the feature.
**Tracked in**: [QD-14726](https://youtrack.jetbrains.com/issue/QD-14726).

---

## 2. Missing Tests (Kotlin implementation exists, test missing or thin)

### 2.1 qodana-engine

- [ ] **OkHttpTransport** — `OkHttpTransport.kt` exists, zero tests. Needs MockWebServer.
- [ ] **PublisherAdapter** — uses qodana-publisher library, zero tests.
- [ ] **ReportConverterAdapter** — thin wrapper around qodana-report-converter, zero tests.
- [ ] **FuserAdapter** — uses FUS reporting library + HTTP, zero tests (FuserSerializer tested).
- [ ] **Cloud Endpoint edge cases** — `CloudClientTest` has 6 tests. Go has 17 tests across cloud + endpoints. Missing: version mismatch, endpoint construction, project URL, team URL.
- [ ] **Failure threshold severity combos** — `ReportProcessorTest` has 8 tests. Go `TestFailureThresholds` has extensive severity matrix.
- [ ] **ContainerScan Docker arg details** — Go `TestDockerCliArgs`, `TestGenerateDebugDockerRunCommand`. Kotlin `ContainerScanTest` has 9 tests but missing debug command gen, token filtering.

### 2.2 qodana-core

- [ ] **MordantTerminal** — exists, zero tests. Go has 22 output tests.
- [ ] **Product version methods** — Go has 6 tests for version branch/checks/comparison. Kotlin has zero.
- [ ] **Linter finder methods** — Go: `TestFindLinterByImage`, `TestFindLinterByName`, `TestFindLinterByProductCode`, `TestFindIde`. Not implemented in Kotlin.
- [ ] **ThirdPartyScanContext validation** — data class, zero tests. Go has 6 tests.

### 2.3 qodana-cli

- [ ] **ScanCommand flag parsing** — Go: `TestDeprecatedScanFlags`, `TestScanFlags_Script`, `TestExclusiveFixesCommand`. Only basic help tests exist.
- [ ] **InitCommand** — Go: `TestInitCommand` (creates yaml). Only help test exists.
- [ ] **ContributorsCommand** — Go: `TestContributorsCommand`. No test.
- [ ] **PullCommand** — Go: `TestPullImage`, `TestPullInNative`. No test.
- [ ] **CLI integration** — Go: `TestAllCommandsWithContainer` (gated). No equivalent.

### 2.4 qodana-clang

- [ ] **ClangRunner** — parallel execution, zero tests. Go: `TestLinterRun` covers the full pipeline.
- [ ] **ClangCommand** — Clikt command, zero tests.

### 2.5 qodana-cdnet

- [ ] **CdnetCommand** — Clikt command, zero tests.

---

## Summary

| Category                   | Go Tests           | Kotlin Tests  | Gap                                                |
| -------------------------- | ------------------ | ------------- | -------------------------------------------------- |
| Product/Linter definitions | 28                 | 24            | ~15 missing methods/tests                          |
| Cloud/License/Endpoints    | 22                 | 16            | ~17 missing (version parsing, endpoint edge cases) |
| SARIF operations           | 18 + 3 + 10        | 7             | ~24 missing (short sarif, versioning, fingerprint) |
| Container/Docker           | 10                 | 9 + 11        | ~10 missing (image validation, user selection)     |
| Git operations             | 14                 | 11            | ~5 missing (submodules, changes calc)              |
| CLI commands               | 15                 | 6             | ~10 missing (flag parsing, integration)            |
| Terminal/output            | 22                 | 0             | 22 missing                                         |
| CI/env detection           | 18                 | 10            | ~12 missing (BitBucket, Azure, validation)         |
| Context/options            | 12 + 12            | 9             | ~15 missing (scenario, analyzer selection)         |
| Core orchestration         | 14 + 7 + 4         | 6 + 6         | ~15 missing (bootstrap, stages, scripts)           |
| Startup (IDE, cache, XML)  | 5 + 8 + 3          | 6             | ~12 missing (installer, cache sync, XML)           |
| File/archive utils         | 13                 | 13            | ~6 missing (SHA256, archive walking)               |
| Process/cmd utils          | 8                  | 8             | ~3 missing (bootstrap, pipes)                      |
| Contributors               | 8                  | 9             | ~2 missing (mailmap)                               |
| Thresholds (severity)      | 1 (many subtests)  | 8             | severity matrix missing                            |
| Token loading              | 4                  | 12            | ~1 missing (keychain, required check)              |
| Third-party context        | 6                  | 0             | 6 missing                                          |
| String utils               | 11                 | 0             | 11 missing (or N/A if inlined)                     |
| Algorithms                 | 2                  | 0             | 2 missing (or N/A)                                 |
| Nuget                      | 3                  | 4             | covered                                            |
| Config (YAML/effective)    | 9                  | 20            | mostly covered, ~3 missing                         |
| **TOTAL**                  | **~320 functions** | **331 tests** | **~190 test-equivalent gaps**                      |

The Kotlin project has more raw test count but covers narrower surface area. The Go codebase tests many features that don't exist in Kotlin yet.
