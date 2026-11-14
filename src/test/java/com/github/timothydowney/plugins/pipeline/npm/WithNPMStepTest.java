package com.github.timothydowney.plugins.pipeline.npm;

import hudson.model.Result;
import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.plugins.configfiles.GlobalConfigFiles;
import org.jenkinsci.plugins.configfiles.custom.CustomConfig;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

/**
 * Unit tests for WithNPMStep
 * @author downeyt
 *
 */
@WithJenkins
class WithNPMStepTest {

    @Test
    void testWithNPM(JenkinsRule rule) throws Exception {
        WorkflowJob p = rule.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  withNPM(npmrcConfig: '" + createConfig(rule, "some content").id + "') {\n"
                        + "    echo(readFile('.npmrc'))\n"
                        + "  }\n"
                        + "  echo 'post-withNPM: fileExists=' + fileExists('.npmrc')\n"
                        + "}",
                true));
        WorkflowRun b = rule.assertBuildStatusSuccess(p.scheduleBuild2(0));
        rule.assertLogContains("some content", b);
        rule.assertLogContains("post-withNPM: fileExists=false", b);
        rule.assertLogContains("Cleaned up temporary .npmrc", b);
    }

    @Test
    void testWithNPMMissingNpmrc(JenkinsRule rule) throws Exception {
        createConfig(rule, "some content");
        WorkflowJob p = rule.createProject(WorkflowJob.class, "p-missing");
        p.setDefinition(
                new CpsFlowDefinition("node {withNPM(npmrcConfig: 'missing') {echo(readFile('.npmrc'))}}", true));
        rule.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
    }

    @Test
    void testWithNPMBlankConfig(JenkinsRule rule) throws Exception {
        Config emptyConfig = createConfig(rule, "");
        WorkflowJob p = rule.createProject(WorkflowJob.class, "p-blank");
        p.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  withNPM(npmrcConfig: '" + emptyConfig.id + "') {\n"
                        + "    echo 'inside-block: fileExists=' + fileExists('.npmrc')\n"
                        + "  }\n"
                        + "}",
                true));
        WorkflowRun b = rule.assertBuildStatusSuccess(p.scheduleBuild2(0));
        rule.assertLogContains("inside-block: fileExists=true", b);
    }

    @Test
    void testWithNPMPreExistingNpmrcRestored(JenkinsRule rule) throws Exception {
        Config config = createConfig(rule, "managed registry config");
        WorkflowJob p = rule.createProject(WorkflowJob.class, "p-restore");
        p.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  writeFile file: '.npmrc', text: 'original repo npmrc'\n"
                        + "  withNPM(npmrcConfig: '" + config.id + "') {\n"
                        + "    echo 'inside: ' + readFile('.npmrc')\n"
                        + "  }\n"
                        + "  echo 'restored: ' + readFile('.npmrc')\n"
                        + "}",
                true));
        WorkflowRun b = rule.assertBuildStatusSuccess(p.scheduleBuild2(0));
        rule.assertLogContains("inside: managed registry config", b);
        rule.assertLogContains("restored: original repo npmrc", b);
        rule.assertLogContains("Restored original .npmrc", b);
    }

    @Test
    void testWithNPMCleanupDisabled(JenkinsRule rule) throws Exception {
        Config config = createConfig(rule, "retained config");
        WorkflowJob p = rule.createProject(WorkflowJob.class, "p-no-cleanup");
        p.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  withNPM(npmrcConfig: '" + config.id + "', cleanup: false) {\n"
                        + "    echo 'inside: ' + readFile('.npmrc')\n"
                        + "  }\n"
                        + "  echo 'post: fileExists=' + fileExists('.npmrc')\n"
                        + "  echo 'post: ' + readFile('.npmrc')\n"
                        + "}",
                true));
        WorkflowRun b = rule.assertBuildStatusSuccess(p.scheduleBuild2(0));
        rule.assertLogContains("post: fileExists=true", b);
        rule.assertLogContains("post: retained config", b);
    }

    @Test
    void testWithNPMSpecificPath(JenkinsRule rule) throws Exception {
        Config config = createConfig(rule, "subdir config");
        WorkflowJob p = rule.createProject(WorkflowJob.class, "p-path");
        p.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  withNPM(npmrcConfig: '" + config.id + "', path: 'frontend') {\n"
                        + "    echo 'inside: fileExists=' + fileExists('frontend/.npmrc')\n"
                        + "    echo 'inside-content: ' + readFile('frontend/.npmrc')\n"
                        + "  }\n"
                        + "  echo 'post: fileExists=' + fileExists('frontend/.npmrc')\n"
                        + "}",
                true));
        WorkflowRun b = rule.assertBuildStatusSuccess(p.scheduleBuild2(0));
        rule.assertLogContains("inside: fileExists=true", b);
        rule.assertLogContains("inside-content: subdir config", b);
        rule.assertLogContains("post: fileExists=false", b);
        rule.assertLogContains("Cleaned up temporary .npmrc", b);
    }

    @Test
    void testWithNPMEnvVar(JenkinsRule rule) throws Exception {
        Config config = createConfig(rule, "env var config");
        WorkflowJob p = rule.createProject(WorkflowJob.class, "p-env");
        p.setDefinition(new CpsFlowDefinition(
                "node {\n"
                        + "  withNPM(npmrcConfig: '" + config.id + "') {\n"
                        + "    echo 'NPM_CONFIG_USERCONFIG=' + env.NPM_CONFIG_USERCONFIG\n"
                        + "  }\n"
                        + "}",
                true));
        WorkflowRun b = rule.assertBuildStatusSuccess(p.scheduleBuild2(0));
        rule.assertLogContains("NPM_CONFIG_USERCONFIG=", b);
        rule.assertLogContains(".npmrc", b);
    }

    private Config createConfig(JenkinsRule rule, String content) {
        ConfigProvider configProvider =
                rule.jenkins.getExtensionList(ConfigProvider.class).get(CustomConfig.CustomConfigProvider.class);
        String id = configProvider.getProviderId() + "my-npmrc-" + System.nanoTime();
        Config config = new CustomConfig(id, "My File " + System.nanoTime(), "", content);

        GlobalConfigFiles globalConfigFiles =
                rule.jenkins.getExtensionList(GlobalConfigFiles.class).get(GlobalConfigFiles.class);
        globalConfigFiles.save(config);
        return config;
    }
}
