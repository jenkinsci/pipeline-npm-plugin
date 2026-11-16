# AGENTS.md — Jenkins Pipeline NPM Integration Plugin (`pipeline-npm`)

## Overview
- **Plugin ID**: `pipeline-npm`
- **Repository**: `jenkinsci/pipeline-npm-plugin`
- **Java Package**: `com.github.timothydowney.plugins.pipeline.npm`
- **Jenkins Baseline**: `2.541.x` (`org.jenkins-ci.plugins:plugin:6.2250.va_d01d559a_209`, Java 17 / 21+)

Provides the `withNPM` Pipeline step (`WithNPMStep`), integrating with the Config File Provider plugin (`NPMConfig`) to provision `.npmrc` configurations with expanded registry credentials, monorepo subdirectory support (`path`), `NPM_CONFIG_USERCONFIG` environment injection, and automatic workspace credential cleanup (`cleanup`) with pre-existing `.npmrc` backup/restoration.

---

## Build, Test & Verification Commands

### Prerequisites
- **JDK**: Java 21 (recommended) or Java 17+
- **Maven**: Apache Maven `3.9+`

### Core Maven Commands
```bash
# Full build, unit/integration tests, SpotBugs, and Enforcer checks (always run before opening a PR)
mvn clean verify

# Run unit/pipeline tests quickly (skipping InjectedTest during development)
mvn test -Dtest=WithNPMStepTest

# Run a single test method in WithNPMStepTest
mvn test -Dtest=WithNPMStepTest#testExecuteWithSubfolderPath

# Run SpotBugs and Maven Enforcer checks directly
mvn spotbugs:check enforcer:enforce

# Package the .hpi plugin artifact without running tests
mvn clean package -DskipTests

# Start a local development Jenkins instance at http://localhost:8080/jenkins
mvn hpi:run
```

---

## Architecture & Source Layout

- `src/main/java/com/github/timothydowney/plugins/pipeline/npm/`
  - `WithNPMStep.java` — Pipeline `Step` definition (`withNPM`) with `@DataBoundConstructor` and `@DataBoundSetter` properties (`npmrcConfig`, `path`, `cleanup`) and `DescriptorImpl`.
  - `WithNPMStepExecution.java` — `GeneralNonBlockingStepExecution` that resolves the `NPMConfig` via `ConfigFileProvider`, backs up any pre-existing `.npmrc`, writes the expanded `.npmrc` (in the workspace root or target `path` subdirectory), injects `NPM_CONFIG_USERCONFIG` via `EnvironmentExpander`, and purges/restores `.npmrc` in `TailCall.finished(...)`.
- `src/main/resources/com/github/timothydowney/plugins/pipeline/npm/WithNPMStep/`
  - `config.groovy` — Snippet Generator UI form definition.
  - `help-npmrcConfig.html`, `help-path.html`, `help-cleanup.html` — Inline field documentation.
- `src/test/java/com/github/timothydowney/plugins/pipeline/npm/`
  - `WithNPMStepTest.java` — JUnit 5 `@WithJenkins` pipeline integration tests covering default `.npmrc` provisioning, blank/dynamic template expansion, monorepo `path` subdirectory placement, credential cleanup, pre-existing `.npmrc` restoration, and `NPM_CONFIG_USERCONFIG` injection.

---

## Coding, Remoting & Security Guardrails

1. **Workspace Credential Hygiene**:
   - Expanded `.npmrc` files contain sensitive registry authentication tokens. Always ensure temporary files and expanded `.npmrc` files are deleted in `TailCall.finished(StepContext)` (unless the user explicitly sets `cleanup: false`), and never log `.npmrc` contents to `TaskListener`.
   - Preserve and restore any pre-existing workspace `.npmrc` file when `cleanup` is enabled.
2. **Jenkins Remoting & CPS Serialization**:
   - `WithNPMStepExecution` and its `TailCall` / `EnvironmentExpander` inner classes are serialized across Pipeline CPS checkpoints. Keep all fields `Serializable` (use remote path strings rather than non-serializable objects where appropriate) and perform all agent file operations through `hudson.FilePath`.
3. **Pull Request & Dependency Hygiene**:
   - Keep BOM/parent POM updates separate from feature or bugfix PRs unless required by an API change.
   - Never use raw emojis in UI templates or user-facing strings, and never use native browser `alert()`, `confirm()`, or `prompt()`.
