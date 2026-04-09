package common;

import java.util.Locale;

public final class SleepBlockerFactory {

    private SleepBlockerFactory() {
    }

    public static SleepBlocker createForCurrentOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return new WindowsSleepBlocker();
        }
        if (os.contains("linux")) {
            return new LinuxSleepBlocker();
        }
        return new NoopSleepBlocker();
    }
}

