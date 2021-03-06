package com.github.games647.lagmonitor.tasks;

import com.github.games647.lagmonitor.LagMonitor;
import com.github.games647.lagmonitor.PluginUtil;
import com.github.games647.lagmonitor.PluginViolation;
import com.google.common.collect.Sets;

import java.lang.Thread.State;
import java.util.Arrays;
import java.util.Map.Entry;
import java.util.Set;
import java.util.TimerTask;
import java.util.logging.Level;

import org.bukkit.plugin.Plugin;

public class BlockingIODetectorTask extends TimerTask {

    private final Thread mainThread;
    private final LagMonitor plugin;

    private final Set<PluginViolation> violations = Sets.newHashSet();
    private final Set<String> violatedPlugins = Sets.newHashSet();

    public BlockingIODetectorTask(LagMonitor plugin, Thread mainThread) {
        this.plugin = plugin;
        this.mainThread = mainThread;
    }

    @Override
    public void run() {
        //According to this post the thread is still in Runnable although it's waiting for
        //file/http ressources
        //https://stackoverflow.com/questions/20795295/why-jstack-out-says-thread-state-is-runnable-while-socketread
        if (mainThread.getState() == State.RUNNABLE) {
            //Based on this post we have to check the top element of the stack
            //https://stackoverflow.com/questions/20891386/how-to-detect-thread-being-blocked-by-io
            StackTraceElement[] stackTrace = mainThread.getStackTrace();
            StackTraceElement topElement = stackTrace[stackTrace.length - 1];
            if (topElement.isNativeMethod()) {
                //Socket/SQL (connect) - java.net.DualStackPlainSocketImpl.connect0
                //Socket/SQL (read) - java.net.SocketInputStream.socketRead0
                //Socket/SQL (write) - java.net.SocketOutputStream.socketWrite0
                if (isElementEqual(topElement, "java.net.DualStackPlainSocketImpl", "connect0")
                        || isElementEqual(topElement, "java.net.SocketInputStream", "socketRead0")
                        || isElementEqual(topElement, "java.net.SocketOutputStream", "socketWrite0")) {
                    logWarning("Server is performing socket operations on the main thread. Proparly caused by {0}");
                } //File (in) - java.io.FileInputStream.readBytes
                //File (out) - java.io.FileOutputStream.writeBytes
                else if (isElementEqual(topElement, "java.io.FileInputStream", "readBytes")
                        || isElementEqual(topElement, "java.io.FileOutputStream", "writeBytes")) {
                    logWarning("Server is performing file operations on the main thread. Proparly caused by {0}");
                }
            }
        }
    }

    private boolean isElementEqual(StackTraceElement traceElement, String className, String methodName) {
        return traceElement.getClassName().equals(className) && traceElement.getMethodName().equals(methodName);
    }

    private void logWarning(String message) {
        Exception stackTraceCreator = new Exception();
        StackTraceElement[] stackTrace = stackTraceCreator.getStackTrace();

        //remove the parts from LagMonitor
        StackTraceElement[] copyOfRange = Arrays.copyOfRange(stackTrace, 2, stackTrace.length);
        Entry<Plugin, StackTraceElement> foundPlugin = PluginUtil.findPlugin(copyOfRange);

        PluginViolation violation = new PluginViolation("");
        if (foundPlugin != null) {
            String pluginName = foundPlugin.getKey().getName();
            violation = new PluginViolation(pluginName, foundPlugin.getValue(), "");

            if (!violatedPlugins.add(violation.getPluginName()) && plugin.getConfig().getBoolean("oncePerPlugin")) {
                return;
            }
        }

        if (!violations.add(violation)) {
            return;
        }

        plugin.getLogger().log(Level.WARNING, message, violation.getPluginName());

        if (plugin.getConfig().getBoolean("hideStacktrace")) {
            plugin.getLogger().log(Level.WARNING, "Source: {0}, method {1}, line {2}"
                    , new Object[]{violation.getSourceFile(), violation.getMethodName(), violation.getLineNumber()});
        } else {
            plugin.getLogger().log(Level.WARNING, "", stackTraceCreator);
        }
    }
}
