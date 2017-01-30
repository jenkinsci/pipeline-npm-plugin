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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.annotation.Nonnull;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.lib.configprovider.model.Config;
import org.jenkinsci.plugins.configfiles.ConfigFiles;
import org.jenkinsci.plugins.workflow.steps.BodyExecution;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.AbortException;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Launcher.ProcStarter;
import hudson.Proc;
import hudson.Util;
import hudson.console.ConsoleLogFilter;
import hudson.model.Computer;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.WorkspaceList;
import hudson.util.ArgumentListBuilder;
import jenkins.model.Jenkins;

@SuppressFBWarnings(value="SE_TRANSIENT_FIELD_NOT_RESTORED", justification="Contextual fields used only in start(); no onResume needed")
class WithNPMStepExecution extends StepExecution {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(WithNPMStepExecution.class.getName());

    private final transient WithNPMStep step;
    private final transient TaskListener listener;
    private final transient FilePath ws;
    private final transient Launcher launcher;
    private final transient EnvVars env;
    private transient EnvVars envOverride;
    private final transient Run<?, ?> build;

    private transient Computer computer;
    private transient FilePath tempBinDir;
    private transient BodyExecution body;

    /**
     * Inidicates if running on docker with <tt>docker.image()</tt>
     */
    private boolean withContainer;

    private transient PrintStream console;

    WithNPMStepExecution(StepContext context, WithNPMStep step) throws Exception {
        super(context);
        this.step = step;
        // Or just delete these fields and inline:
        listener = context.get(TaskListener.class);
        ws = context.get(FilePath.class);
        launcher = context.get(Launcher.class);
        env = context.get(EnvVars.class);
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

        withContainer = detectWithContainer();

        setupNPM();

        ConsoleLogFilter consFilter = getContext().get(ConsoleLogFilter.class);
        EnvironmentExpander envEx = EnvironmentExpander.merge(getContext().get(EnvironmentExpander.class), 
        		new ExpanderImpl(envOverride));

        body = getContext().newBodyInvoker().withContexts(envEx, consFilter).withCallback(new Callback(tempBinDir)).start();

        return false;
    }

    /**
     * Detects if this step is running inside <tt>docker.image()</tt>
     * 
     * This has the following implications:
     * <li>Tool intallers do no work, as they install in the host, see:
     * https://issues.jenkins-ci.org/browse/JENKINS-36159
     * <li>Environment variables do not apply because they belong either to the master or the agent, but not to the
     * container running the <tt>sh</tt> command for npm This is due to the fact that <tt>docker.image()</tt> all it
     * does is decorate the launcher and excute the command with a <tt>docker run</tt> which means that the inherited
     * environment from the OS will be totally different eg: MAVEN_HOME, JAVA_HOME, PATH, etc.
     * 
     * @see <a href=
     * "https://github.com/jenkinsci/docker-workflow-plugin/blob/master/src/main/java/org/jenkinsci/plugins/docker/workflow/WithContainerStep.java#L213">
     * WithContainerStep</a>
     * @return true if running inside docker container with <tt>docker.image()</tt>
     */
    private boolean detectWithContainer() {
        Launcher launcher1 = launcher;
        while (launcher1 instanceof Launcher.DecoratedLauncher) {
            if (launcher1.getClass().getName().contains("WithContainerStep")) {
                LOGGER.fine("Step running within docker.image()");
                return true;
            }
            launcher1 = ((Launcher.DecoratedLauncher) launcher1).getInner();
        }
        return false;
    }

    private void setupNPM() throws AbortException, IOException, InterruptedException {
        String npmExecPath = obtainNPMExec();

        // Temp dir with the wrapper that will be prepended to the path
        tempBinDir = tempDir(ws).child("withNPM" + Util.getDigestOf(UUID.randomUUID().toString()).substring(0, 8));
        tempBinDir.mkdirs();
        // set the path to our script
        envOverride.put("PATH+NPM", tempBinDir.getRemote());

        LOGGER.log(Level.FINE, "Using temp dir: {0}", tempBinDir.getRemote());

        if (npmExecPath == null) {
            throw new AbortException("Couldn\u2019t find any npm executable");
        }

        FilePath npmExec = new FilePath(ws.getChannel(), npmExecPath);
        
        // Create the .npmrc in the workspace so that it overrides the
        // user or global .npmrc
        //
        // TODO:  Check to see if file already exists in workspace since if it does, we probably
        // want to abort.
        settingsFromConfig(step.getNpmrcConfig(), ws.child(".npmrc"));
        
        String content = npmWrapperContent(npmExec);

        createWrapperScript(tempBinDir, npmExec.getName(), content);

    }

    private String obtainNPMExec() throws AbortException, InterruptedException {
    	String npmExecPath = null;
    	
    	LOGGER.fine("Setting up npm");
    	
    	/*
    	 * NPM doesn't have a tool installation and does not include environment
    	 * variable based approaches like Maven, so it needs to be directly located.
    	 */
        if (Boolean.TRUE.equals(getComputer().isUnix())) {
        	npmExecPath = readFromProcess("/bin/sh", "-c", "which npm");
        } else {
        	npmExecPath = readFromProcess("where", "npm.cmd");
            if (npmExecPath == null) {
            	npmExecPath = readFromProcess("where", "npm.bat");
            }
        }
    	
        // TODO:  This is sort of bad, but npm isn't going to be installed in the
        // jenkins.io environment when this builds...need a better way to test this.
        if (npmExecPath == null) {
        	LOGGER.log(Level.WARNING, "Could not find npm on the path...please correct.");
	        console.printf("npm not found on the path....please correct");
	        npmExecPath = "missing-npm";
        } else {
	        LOGGER.log(Level.FINE, "Found exec for npm on: {0}", npmExecPath);
	        console.printf("Using npm exec: %s%n", npmExecPath);
        }
        
    	return npmExecPath;
    }

    /**
     * Executes a command and reads the result to a string. It uses the launcher to run the command to make sure the
     * launcher decorator is used ie. docker.image step
     * 
     * @param args command arguments
     * @return output from the command
     * @throws InterruptedException if interrupted
     */
    private String readFromProcess(String... args) throws InterruptedException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ProcStarter ps = launcher.launch();
            Proc p = launcher.launch(ps.cmds(args).stdout(baos));
            int exitCode = p.join();
            if (exitCode == 0) {
                return baos.toString(getComputer().getDefaultCharset().name()).replaceAll("[\t\r\n]+", " ").trim();
            } else {
                return null;
            }
        } catch (IOException e) {
            e.printStackTrace(console.format("Error executing command '%s' : %s%n", Arrays.toString(args), e.getMessage()));
        }
        return null;
    }

    /**
     * Generates the npm wrapper script.
     * 
     * @param npmExec The npm executable location.
     * @return Wrapper script content
     * @throws AbortException If something goes wrong
     */
    private String npmWrapperContent(FilePath npmExec) throws AbortException {
    	ArgumentListBuilder argList = new ArgumentListBuilder(npmExec.getRemote());
    	
        boolean isUnix = Boolean.TRUE.equals(getComputer().isUnix());

        String lineSep = isUnix ? "\n" : "\r\n";

        StringBuilder c = new StringBuilder();

        if (isUnix) {
            c.append("#!/bin/sh -e").append(lineSep);
        } else {
            c.append("@echo off").append(lineSep);
        }

        c.append("echo ----- withNPM Wrapper script -----").append(lineSep);
        c.append(argList.toString()).append(isUnix ? " \"$@\"" : " %*").append(lineSep);

        String content = c.toString();
        LOGGER.log(Level.FINE, "Generated wrapper: {0}", content);
        return content;
    	
    }
    
    /**
     * Creates the actual wrapper script file and sets the permissions.
     * 
     * @param tempBinDir dir to create the script file on
     * @param name the script file name
     * @param content contents of the file
     * @return
     * @throws InterruptedException when processing remote calls
     * @throws IOException when reading files
     */
    private FilePath createWrapperScript(FilePath tempBinDir, String name, String content) throws IOException, InterruptedException {
        FilePath scriptFile = tempBinDir.child(name);

        scriptFile.write(content, getComputer().getDefaultCharset().name());
        scriptFile.chmod(0755);

        return scriptFile;
    }

    /**
     * Reads the config file from Config File Provider, expands the credentials and stores it in a file on the temp
     * folder to use it with the maven wrapper script
     * 
     * @param settingsConfigId config file id from Config File Provider
     * @param settingsFile path to write te content to
     * @return the {@link FilePath} to the settings file
     * @throws AbortException in case of error
     */
    private void settingsFromConfig(String settingsConfigId, FilePath settingsFile) throws AbortException {

        Config config = ConfigFiles.getByIdOrNull(build, settingsConfigId);
        if (config == null) {
            throw new AbortException("Could not find the NPM config file id:" + settingsConfigId + ". Make sure it exists on Managed Files");
        }
        if (StringUtils.isBlank(config.content)) {
            throw new AbortException("Could not create NPM config file id:" + settingsConfigId + ". Content of the file is empty");
        }
        
        console.println("Using settings config with name " + config.name);

        // TODO:  We could update the _auth token here via credentials!

        try {
        	settingsFile.write(config.content, getComputer().getDefaultCharset().name());
        } catch (Exception e) {
        	throw new IllegalStateException("The npmrc could not be supplied for the current build: " + e.getMessage(), e);
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
     * Callback to cleanup tmp script after finishing the job
     */
    private static class Callback extends BodyExecutionCallback.TailCall {
        FilePath tempBinDir;

        public Callback(FilePath tempBinDir) {
            this.tempBinDir = tempBinDir;
        }

        @Override
        protected void finished(StepContext context) throws Exception {
            try {
                tempBinDir.deleteRecursive();
            } catch (IOException | InterruptedException e) {
                try {
                    TaskListener listener = context.get(TaskListener.class);
                    if (e instanceof IOException) {
                        Util.displayIOException((IOException) e, listener); // Better IOException display on windows
                    }
                    e.printStackTrace(listener.fatalError("Error deleting temporary files"));
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }

        private static final long serialVersionUID = 1L;
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
    private @Nonnull Computer getComputer() throws AbortException {
        if (computer != null) {
            return computer;
        }

        String node = null;
        Jenkins j = Jenkins.getActiveInstance();

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
            } catch (IOException | InterruptedException e) {// ignored
            }
        }
        return computer;
    }

    /**
     * Calculates a temporary dir path
     * 
     * @param ws current workspace
     * @return the temporary dir
     */
    private static FilePath tempDir(FilePath ws) {
        // TODO replace with WorkspaceList.tempDir(ws) after 1.652
        return ws.sibling(ws.getName() + System.getProperty(WorkspaceList.class.getName(), "@") + "tmp");
    }

}
