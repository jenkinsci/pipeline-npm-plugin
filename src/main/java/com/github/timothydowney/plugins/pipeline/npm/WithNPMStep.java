package com.github.timothydowney.plugins.pipeline.npm;

import java.util.Set;

import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.plugins.configfiles.ConfigFiles;
import org.jenkinsci.plugins.configfiles.custom.CustomConfig.CustomConfigProvider;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import com.google.common.collect.ImmutableSet;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.ItemGroup;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.ListBoxModel;

public class WithNPMStep extends Step {

    private String npmrcConfig;

    @DataBoundConstructor
    public WithNPMStep() {
    }

    
    public String getNpmrcConfig() {
        return npmrcConfig;
    }

    @DataBoundSetter
    public void setNpmrcConfig(String npmrcConfig) {
        this.npmrcConfig = npmrcConfig;
    }

    @Override
    public StepExecution start(StepContext context) throws Exception {
        return new WithNPMStepExecution(context, this);
    }

    @Extension
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public String getFunctionName() {
            return "withNPM";
        }

        @Override
        public String getDisplayName() {
            return "Provide NPM environment";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }

        @Override
        public Set<Class<?>> getRequiredContext() {
            return ImmutableSet.of(TaskListener.class, FilePath.class, Launcher.class, EnvVars.class, Run.class);
        }
        
        @Restricted(NoExternalUse.class) // Only for UI calls
        public ListBoxModel doFillNpmrcConfigItems(@AncestorInPath ItemGroup context) {
            ListBoxModel r = new ListBoxModel();
            r.add("--- Choose an npmrc from custom config files ---",null);
            for (Config config : ConfigFiles.getConfigsInContext(context, CustomConfigProvider.class)) {
                r.add(config.name, config.id);
            }
            return r;
        }

    }
	
}
