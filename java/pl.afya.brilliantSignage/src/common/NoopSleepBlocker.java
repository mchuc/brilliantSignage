package common;

public class NoopSleepBlocker implements SleepBlocker {

    @Override
    public boolean start() {
        return false;
    }

    @Override
    public void stop() {
        // No-op.
    }

    @Override
    public boolean isSupported() {
        return false;
    }

    @Override
    public String getBackendName() {
        return "unsupported";
    }
}

