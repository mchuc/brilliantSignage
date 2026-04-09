package common;

public interface SleepBlocker {

    boolean start();

    void stop();

    boolean isSupported();

    String getBackendName();
}

