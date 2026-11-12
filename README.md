# Pipeline NPM Plugin

[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/pipeline-npm.svg)](https://plugins.jenkins.io/pipeline-npm)
[![GitHub release](https://img.shields.io/github/release/jenkinsci/pipeline-npm-plugin.svg?label=changelog)](https://github.com/jenkinsci/pipeline-npm-plugin/releases/latest)
[![GitHub license](https://img.shields.io/github/license/jenkinsci/pipeline-npm-plugin)](https://github.com/jenkinsci/pipeline-npm-plugin/blob/main/LICENSE)
[![Jenkins Plugin Installs](https://img.shields.io/jenkins/plugin/i/pipeline-npm.svg?color=blue)](https://plugins.jenkins.io/pipeline-npm)

This plugin provides integration with Jenkins Pipeline by provisioning a custom `.npmrc` configuration for build steps like `sh 'npm ...'` or `bat 'npm ...'`. Configuration files are centrally and securely managed in Jenkins via the **Config File Provider** plugin. This enables managing scoped private registries, authentication tokens, proxies, and custom npm settings outside source control.

The plugin also automatically exposes `NPM_CONFIG_USERCONFIG` pointing to the generated `.npmrc`, ensuring that npm, yarn, and npx discover the configuration across subshells and scripts.

## Basic Usage

```groovy
withNPM(npmrcConfig: 'my-custom-npmrc') {
    sh 'npm install'
    sh 'npm publish'
}
```

`my-custom-npmrc` is the identifier of an `.npmrc` managed configuration file defined under **Manage Jenkins > Managed Files**.

During execution, `withNPM`:
1. Expands registry credentials and writes the `.npmrc` file to the workspace.
2. Injects the `NPM_CONFIG_USERCONFIG` environment variable.
3. Automatically cleans up the `.npmrc` file when the block exits to prevent credential leaks (restoring any pre-existing workspace `.npmrc` if one existed prior to the step).

## Monorepos & Subdirectories

For monorepos or multi-module projects where JavaScript code resides in a subfolder (e.g. `frontend/` or `packages/client`), pass the optional `path` parameter:

```groovy
withNPM(npmrcConfig: 'my-custom-npmrc', path: 'frontend') {
    dir('frontend') {
        sh 'npm install'
        sh 'npm test'
    }
}
```

## Docker Agent Example

This plugin requires Node and NPM to be available on the agent `PATH` or provided via a container image:

```groovy
stage('npm-build') {
    agent {
        docker {
            image 'node:20-alpine'
        }
    }

    steps {
        withNPM(npmrcConfig: 'my-custom-npmrc') {
            sh 'npm ci'
            sh 'npm test'
        }
    }
}
```

## Configuration Parameters

| Parameter | Type | Default | Description |
|---|---|---|---|
| `npmrcConfig` | String | *(required)* | ID of the managed `.npmrc` file from Config File Provider. |
| `path` | String | `""` (workspace root) | Optional relative subfolder path where `.npmrc` should be written. |
| `cleanup` | boolean | `true` | Automatically deletes the generated `.npmrc` upon block completion (and restores any pre-existing `.npmrc`). Set to `false` to retain the file. |
