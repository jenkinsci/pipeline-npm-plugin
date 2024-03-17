/*
 * The MIT License
 *
 * Copyright (c) 2017, Tim Downey.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

 package com.github.timothydowney.plugins.pipeline.npm;

 import edu.umd.cs.findbugs.annotations.NonNull;
 import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
 import hudson.AbortException;
 import hudson.EnvVars;
 import hudson.FilePath;
 import hudson.Launcher;
 import hudson.console.ConsoleLogFilter;
 import hudson.model.Computer;
 import hudson.model.Run;
 import hudson.model.TaskListener;
 import java.io.IOException;
 import java.io.PrintStream;
 import java.util.ArrayList;
 import java.util.HashMap;
 import java.util.Map;
 import java.util.Map.Entry;
 import java.util.logging.Level;
 import java.util.logging.Logger;
 import jenkins.model.Jenkins;
 import org.apache.commons.lang.StringUtils;
 import org.jenkinsci.lib.configprovider.ConfigProvider;
 import org.jenkinsci.lib.configprovider.model.Config;
 import org.jenkinsci.plugins.configfiles.ConfigFiles;
 import org.jenkinsci.plugins.workflow.steps.BodyExecution;
 import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
 import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
 import org.jenkinsci.plugins.workflow.steps.StepContext;
 import org.jenkinsci.plugins.workflow.steps.StepExecution;
 
 @SuppressFBWarnings(
         value = "SE_TRANSIENT_FIELD_NOT_RESTORED",
         justification = "Contextual fields used only in start(); no onResume needed")
 class WithNPMStepExecution extends StepExecution {
 
     private static final long serialVersionUID = 1L;
 
     private static final Logger LOGGER = Logger.getLogger(WithNPMStepExecution.class.getName());
 
     private final transient WithNPMStep step;
     private final transient TaskListener listener;
     private final transient FilePath ws;
     private final transient Launcher launcher;
     private transient EnvVars envOverride;
     private final transient Run<?, ?> build;
 
     private transient Computer computer;
     private transient BodyExecution body;
 
     private transient PrintStream console;
 
     WithNPMStepExecution(StepContext context, WithNPMStep step) throws Exception {
         super(context);
         this.step = step;
         listener = context.get(TaskListener.class);
         ws = context.get(FilePath.class);
         launcher = context.get(Launcher.class);
         build = context.get(Run.class);
     }
 
     @Override
     public boolean start() throws Exception {
         envOverride = new EnvVars();
         console = listener.getLogger();
 
         if (LOGGER.isLoggable(Level.FINE)) {
             LOGGER.log(Level.FINE, "npmrc Config: {0}", step.getNpmrcConfig());
         }
 
         getComputer();
 
         settingsFromConfig(step.getNpmrcConfig(), ws.child(".npmrc"));
 
         ConsoleLogFilter consFilter = getContext().get(ConsoleLogFilter.class);
         EnvironmentExpander envEx =
                 EnvironmentExpander.merge(getContext().get(EnvironmentExpander.class), new ExpanderImpl(envOverride));
 
         body = getContext()
                 .newBodyInvoker()
                 .withContexts(envEx, consFilter)
                 .withCallback(new Callback())
                 .start();
 
         return false;
     }
 
     private void settingsFromConfig(String settingsConfigId, FilePath settingsFile) throws AbortException {
         Config config = ConfigFiles.getByIdOrNull(build, settingsConfigId);
         if (config == null) {
             throw new AbortException("Could not find the NPM config file id:" + settingsConfigId
                     + ". Make sure it exists on Managed Files");
         }
 
         if (StringUtils.isBlank(config.content)) {
             console.println("Warning: The NPM config file is empty. Proceeding without it.");
             return; 
         }
 
         console.println("Using settings config with name " + config.name);
 
         try {
             if (settingsFile.exists()) {
                 console.println("A workspace local .npmrc already exists and will be overwritten for the build.");
             }
             ConfigProvider provider = config.getDescriptor();
             ArrayList<String> tempFiles = new ArrayList<>();
             String fileContent = provider.supplyContent(config, build, ws, listener, tempFiles);
 
             console.println("Writing .npmrc file: " + settingsFile);
 
             settingsFile.write(fileContent, getComputer().getDefaultCharset().name());
         } catch (Exception e) {
             throw new IllegalStateException(
                     "The npmrc could not be supplied for the current build: " + e.getMessage(), e);
         }
     }
 
 
 }
 
 private static final class ExpanderImpl extends EnvironmentExpander {
     private static final long serialVersionUID = 1;
     private final Map<String, String> overrides;
 
     private ExpanderImpl(EnvVars overrides) {
         LOGGER.log(Level.FINE, "Overrides: " + overrides.toString());
         this.overrides = new HashMap<String, String>();
         for (Entry<String, String> entry : overrides.entrySet()) {
             this.overrides.put(entry.getKey(), entry.getValue());
         }
     }
 
     @Override
     public void expand(EnvVars env) throws IOException, InterruptedException {
         env.overrideAll(overrides);
     }
 }
 
 private static class Callback extends BodyExecutionCallback.TailCall {
 
     @Override
     protected void finished(StepContext context) throws Exception {
         // nothing
     }
 
     private static final long serialVersionUID = 1L;
 }
 
 @Override
 public void stop(Throwable cause) throws Exception {
     if (body != null) {
         body.cancel(cause);
     }
 }
 
 private @NonNull Computer getComputer() throws AbortException {
     if (computer != null) {
         return computer;
     }
 
     String node = null;
     Jenkins j = Jenkins.get();
 
     for (Computer c : j.getComputers()) {
         if (c.getChannel() == launcher.getChannel()) {
             node = c.getName();
             break;
         }
     }
 
     if (node == null) {
         throw new AbortException("Could not find computer for the job");
     }
 
     computer = j.getComputer(node);
     if (computer == null) {
         throw new AbortException("No such computer " + node);
     }
 
     if (LOGGER.isLoggable(Level.FINE)) {
         LOGGER.log(Level.FINE, "Computer: {0}", computer.getName());
         try {
             LOGGER.log(Level.FINE, "Env: {0}", computer.getEnvironment());
         } catch (IOException | InterruptedException e) { // ignored
         }
     }
     return computer;
 }
 }
