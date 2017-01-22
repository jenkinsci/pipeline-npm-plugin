package com.github.timothydowney.plugins.pipeline.npm;

import org.jenkinsci.lib.configprovider.ConfigProvider;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.plugins.configfiles.GlobalConfigFiles;
import org.jenkinsci.plugins.configfiles.custom.CustomConfig;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.BuildWatcher;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import hudson.model.Result;

/**
 * Unit tests for WithNPMStep
 * @author downeyt
 *
 */
public class WithNPMStepTest {

    @Rule
    public BuildWatcher buildWatcher = new BuildWatcher();

    @Rule
    public RestartableJenkinsRule story = new RestartableJenkinsRule();

    @Test
    public void testWithNPM() throws Exception {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
                WorkflowJob p = story.j.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("node {withNPM(npmrcConfig: '" + createConfig().id + "') {sh 'cat .npmrc'}}", true));
                WorkflowRun b = story.j.assertBuildStatusSuccess(p.scheduleBuild2(0));
                story.j.assertLogContains("some content", b);
            }
        });
    }

    @Test
    public void testWithNPMMissingNpmrc() throws Exception {
        story.addStep(new Statement() {
            @Override
            public void evaluate() throws Throwable {
            	createConfig();
                WorkflowJob p = story.j.createProject(WorkflowJob.class, "p");
                p.setDefinition(new CpsFlowDefinition("node {withNPM(npmrcConfig: 'missing') {sh 'cat .npmrc'}}", true));
                WorkflowRun b = story.j.assertBuildStatus(Result.FAILURE, p.scheduleBuild2(0));
            }
        });
    }
    
    public Config createConfig() {
        ConfigProvider configProvider = story.j.jenkins.getExtensionList(ConfigProvider.class).get(CustomConfig.CustomConfigProvider.class);
        String id = configProvider.getProviderId() + "my-npmrc";
        Config config = new CustomConfig(id, "My File", "", "some content");

        GlobalConfigFiles globalConfigFiles = story.j.jenkins.getExtensionList(GlobalConfigFiles.class).get(GlobalConfigFiles.class);
        globalConfigFiles.save(config);
        return config;
    }    
}
