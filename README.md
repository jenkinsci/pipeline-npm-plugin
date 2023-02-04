# Pipeline NPM Plugin

[![Build Status](https://ci.jenkins.io/job/Plugins/job/pipeline-npm-plugin/job/master/badge/icon)](https://ci.jenkins.io/job/Plugins/job/pipeline-npm-plugin/job/master/)
[![Jenkins Plugin](https://img.shields.io/jenkins/plugin/v/openshift-k8s-credentials.svg)](https://plugins.jenkins.io/openshift-k8s-credentials)
[![GitHub release](https://img.shields.io/github/release/jenkinsci/pipeline-npm-plugin.svg?label=changelog)](https://github.com/jenkinsci/pipeline-npm-plugin/releases/latest)
[![GitHub license](https://img.shields.io/github/license/jenkinsci/pipeline-npm-plugin)](https://github.com/jenkinsci/pipeline-npm-plugin/blob/master/LICENSE.md)
[![Jenkins Plugin Installs](https://img.shields.io/jenkins/plugin/i/openshift-k8s-credentials.svg?color=blue)](https://plugins.jenkins.io/openshift-k8s-credentials)

This plugin provides integration with Pipeline by configuring an NPM environment to use within a pipeline job by calling `sh npm` or `bat npm`.  This is accomplished by adding an `npmrc` as a custom config file to be centrally managed  by Jenkins.  This is useful for managing registries, authorizations, and any other npm settings that one would like to manage via Jenkins outside of the pipeline itself.

For example:
```
withNPM(npmrcConfig: 'my-custom-nprc') {
    sh 'npm install'
}
```
'my-custom-npmrc' is a config file that has been previously added to Jenkins via Managed Files.  Underneath, the custom `npmrc` file is being copied to the workspace where it will serve a local override for the build.  Nominally, npm would have allowed a command line mechanism to refer to an npmrc file, but for now, it does not appear to.

This plugin requires a local installation of NPM on the agent or may be used via a docker step.

Here's another example using Docker and [Pipeline Model Definition](https://github.com/jenkinsci/pipeline-model-definition-plugin/wiki/getting%20started):

```
stage('npm-build') {
    agent {
        docker {
            image 'node:7.4'
        }
    }

    steps {
        echo "Branch is ${env.BRANCH_NAME}..."

        withNPM(npmrcConfig:'my-custom-npmrc') {
            echo "Performing npm build..."
            sh 'npm install'
        }
    }
}
```

