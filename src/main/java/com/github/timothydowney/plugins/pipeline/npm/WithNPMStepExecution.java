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
import org.apache.commons.lang3.StringUtils;
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
        // Or just delete these fields and inline:
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

        FilePath targetDir = ws;
        if (StringUtils.isNotBlank(step.getPath())) {
            targetDir = ws.child(step.getPath().trim());
            targetDir.mkdirs();
        }
        FilePath settingsFile = targetDir.child(".npmrc");
        ArrayList<String> tempFiles = new ArrayList<>();
        boolean originalExisted = settingsFile.exists();
        FilePath backupFile = null;

        try {
            if (originalExisted) {
                backupFile = targetDir.child(".npmrc.bak." + System.currentTimeMillis());
                settingsFile.copyTo(backupFile);
                console.println("A workspace local .npmrc already exists and was backed up to " + backupFile.getName()
                        + " for the duration of the build.");
            }

            settingsFromConfig(step.getNpmrcConfig(), settingsFile, tempFiles);
        } catch (Exception e) {
            if (originalExisted && backupFile != null) {
                try {
                    if (backupFile.exists()) {
                        backupFile.copyTo(settingsFile);
                        backupFile.delete();
                    }
                } catch (Exception restoreEx) { // ignored
                }
            } else if (!originalExisted) {
                try {
                    if (settingsFile.exists()) {
                        settingsFile.delete();
                    }
                } catch (Exception deleteEx) { // ignored
                }
            }
            throw e;
        }

        envOverride.put("NPM_CONFIG_USERCONFIG", settingsFile.getRemote());

        ConsoleLogFilter consFilter = getContext().get(ConsoleLogFilter.class);
        EnvironmentExpander envEx =
                EnvironmentExpander.merge(getContext().get(EnvironmentExpander.class), new ExpanderImpl(envOverride));

        body = getContext()
                .newBodyInvoker()
                .withContexts(envEx, consFilter)
                .withCallback(new Callback(settingsFile, backupFile, originalExisted, step.isCleanup(), tempFiles))
                .start();

        return false;
    }

    /**
     * Reads the config file from Config File Provider, expands the credentials and writes it to settingsFile.
     *
     * @param settingsConfigId config file id from Config File Provider
     * @param settingsFile path to write the content to
     * @param tempFiles list of temporary files created during content expansion
     * @throws AbortException in case of error
     */
    private void settingsFromConfig(String settingsConfigId, FilePath settingsFile, ArrayList<String> tempFiles)
            throws AbortException {

        Config config = ConfigFiles.getByIdOrNull(build, settingsConfigId);
        if (config == null) {
            throw new AbortException("Could not find the NPM config file id:" + settingsConfigId
                    + ". Make sure it exists on Managed Files");
        }

        console.println("Using settings config with name " + config.name);

        try {
            ConfigProvider provider = config.getDescriptor();
            String fileContent = provider.supplyContent(config, build, ws, listener, tempFiles);
            if (fileContent == null) {
                fileContent = "";
            }

            console.println("Writing .npmrc file: " + settingsFile);

            settingsFile.write(fileContent, getComputer().getDefaultCharset().name());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "The npmrc could not be supplied for the current build: " + e.getMessage(), e);
        }
    }

    /**
     * Takes care of overriding the environment with our defined overrides
     */
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

    /**
     * Callback to cleanup temporary files and restore original config after finishing the job
     */
    private static class Callback extends BodyExecutionCallback.TailCall {

        private static final long serialVersionUID = 1L;

        private final FilePath settingsFile;
        private final FilePath backupFile;
        private final boolean originalExisted;
        private final boolean cleanup;
        private final ArrayList<String> tempFiles;

        Callback(
                FilePath settingsFile,
                FilePath backupFile,
                boolean originalExisted,
                boolean cleanup,
                ArrayList<String> tempFiles) {
            this.settingsFile = settingsFile;
            this.backupFile = backupFile;
            this.originalExisted = originalExisted;
            this.cleanup = cleanup;
            this.tempFiles = tempFiles;
        }

        @Override
        protected void finished(StepContext context) throws Exception {
            TaskListener listener = context.get(TaskListener.class);
            PrintStream console = listener != null ? listener.getLogger() : null;

            if (tempFiles != null && settingsFile != null) {
                for (String tempPath : tempFiles) {
                    try {
                        FilePath tf = new FilePath(settingsFile.getChannel(), tempPath);
                        if (tf.exists()) {
                            tf.delete();
                        }
                    } catch (Exception e) {
                        if (console != null) {
                            console.println(
                                    "Warning: Could not remove temporary file " + tempPath + ": " + e.getMessage());
                        }
                    }
                }
            }

            if (!cleanup) {
                if (backupFile != null) {
                    try {
                        if (backupFile.exists()) {
                            backupFile.delete();
                        }
                    } catch (Exception e) { // ignored
                    }
                }
                return;
            }

            if (settingsFile == null) {
                return;
            }

            try {
                if (originalExisted) {
                    if (backupFile != null && backupFile.exists()) {
                        backupFile.copyTo(settingsFile);
                        backupFile.delete();
                        if (console != null) {
                            console.println("Restored original .npmrc in " + settingsFile.getRemote());
                        }
                    }
                } else {
                    if (settingsFile.exists()) {
                        settingsFile.delete();
                        if (console != null) {
                            console.println("Cleaned up temporary .npmrc from " + settingsFile.getRemote());
                        }
                    }
                }
            } catch (Exception e) {
                if (console != null) {
                    console.println("Warning: Failed to cleanup .npmrc: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public void stop(Throwable cause) throws Exception {
        if (body != null) {
            body.cancel(cause);
        }
    }

    /**
     * Gets the computer for the current launcher.
     *
     * @return the computer
     * @throws AbortException in case of error.
     */
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
