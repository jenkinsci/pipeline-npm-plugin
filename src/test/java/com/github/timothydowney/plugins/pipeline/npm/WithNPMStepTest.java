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
                "node {withNPM(npmrcConfig: '" + createConfig(rule).id + "') {echo(readFile('.npmrc'))}}", true));
        WorkflowRun b = rule.assertBuildStatusSuccess(p.scheduleBuild2(0));
        rule.assertLogContains("some content", b);
    }

    @Test
    void testWithNPMMissingNpmrc(JenkinsRule rule) throws Exception {
        createConfig(rule);
        WorkflowJob p = rule.createProject(WorkflowJob.class, "p");
        p.setDefinition(
                new CpsFlowDefinition("node {withNPM(npmrcConfig: 'missing') {echo(readFile('.npmrc'))}}", true));
        rule.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
    }

    private Config createConfig(JenkinsRule rule) {
        ConfigProvider configProvider =
                rule.jenkins.getExtensionList(ConfigProvider.class).get(CustomConfig.CustomConfigProvider.class);
        String id = configProvider.getProviderId() + "my-npmrc";
        Config config = new CustomConfig(id, "My File", "", "some content");

        GlobalConfigFiles globalConfigFiles =
                rule.jenkins.getExtensionList(GlobalConfigFiles.class).get(GlobalConfigFiles.class);
        globalConfigFiles.save(config);
        return config;
    }
}
