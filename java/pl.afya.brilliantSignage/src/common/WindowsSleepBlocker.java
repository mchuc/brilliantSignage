package common;

import java.io.IOException;

public class WindowsSleepBlocker implements SleepBlocker {

    private static final String POWERSHELL_SCRIPT =
            "$signature='[DllImport(\"kernel32.dll\")]public static extern uint SetThreadExecutionState(uint esFlags);';"
                    + "Add-Type -Name NativeMethods -Namespace Win32 -MemberDefinition $signature;"
                    + "$flags=0x80000003;"
                    + "[Win32.NativeMethods]::SetThreadExecutionState($flags) | Out-Null;"
                    + "while ($true) {"
                    + "  Start-Sleep -Seconds 30;"
                    + "  [Win32.NativeMethods]::SetThreadExecutionState($flags) | Out-Null;"
                    + "}";

    private Process keepAwakeProcess;

    @Override
    public synchronized boolean start() {
        if (keepAwakeProcess != null && keepAwakeProcess.isAlive()) {
            return true;
        }

        try {
            keepAwakeProcess = new ProcessBuilder(
                    "powershell",
                    "-NoLogo",
                    "-NoProfile",
                    "-ExecutionPolicy",
                    "Bypass",
                    "-Command",
                    POWERSHELL_SCRIPT
            ).start();
            return keepAwakeProcess.isAlive();
        } catch (IOException ignored) {
            keepAwakeProcess = null;
            return false;
        }
    }

    @Override
    public synchronized void stop() {
        if (keepAwakeProcess == null) {
            return;
        }
        keepAwakeProcess.destroy();
        keepAwakeProcess = null;
    }

    @Override
    public boolean isSupported() {
        return true;
    }

    @Override
    public String getBackendName() {
        return "windows-setthreadexecutionstate";
    }
}

