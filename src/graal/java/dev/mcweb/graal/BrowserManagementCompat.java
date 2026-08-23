package dev.mcweb.graal;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.RuntimeMXBean;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

/**
 * Browser stand-ins for {@code java.lang.management} platform beans that Web
 * Image does not expose. Wired only through exact ASM retargets of Minecraft
 * call sites (debug F3 entries, system/crash reports, uptime formatting).
 */
public final class BrowserManagementCompat {
    private BrowserManagementCompat() {
    }

    /** F3 allocation-rate calculator: no GC beans, so rates stay at zero. */
    public static List<GarbageCollectorMXBean> getGarbageCollectorMXBeans() {
        return List.of();
    }

    public static MemoryMXBean getMemoryMXBean() {
        return MemoryBean.INSTANCE;
    }

    public static RuntimeMXBean getRuntimeMXBean() {
        return RuntimeBean.INSTANCE;
    }

    private static MemoryUsage runtimeHeapUsage() {
        Runtime runtime = Runtime.getRuntime();
        long total = runtime.totalMemory();
        long free = runtime.freeMemory();
        long max = runtime.maxMemory();
        return new MemoryUsage(0L, Math.max(0L, total - free), total, max);
    }

    private static ObjectName objectName(String name) {
        try {
            return ObjectName.getInstance(name);
        } catch (MalformedObjectNameException failure) {
            throw new IllegalStateException(name, failure);
        }
    }

    private enum MemoryBean implements MemoryMXBean {
        INSTANCE;

        private final ObjectName objectName =
                objectName("java.lang:type=Memory");

        @Override
        public MemoryUsage getHeapMemoryUsage() {
            return runtimeHeapUsage();
        }

        @Override
        public MemoryUsage getNonHeapMemoryUsage() {
            return new MemoryUsage(0L, 0L, 0L, 0L);
        }

        @Override
        public int getObjectPendingFinalizationCount() {
            return 0;
        }

        @Override
        public boolean isVerbose() {
            return false;
        }

        @Override
        public void setVerbose(boolean value) {
        }

        @Override
        public void gc() {
            System.gc();
        }

        @Override
        public ObjectName getObjectName() {
            return objectName;
        }
    }

    private enum RuntimeBean implements RuntimeMXBean {
        INSTANCE;

        private final long startTime = System.currentTimeMillis();
        private final ObjectName objectName =
                objectName("java.lang:type=Runtime");

        @Override
        public String getName() {
            return "mc-web@browser";
        }

        @Override
        public String getVmName() {
            return "GraalVM Web Image";
        }

        @Override
        public String getVmVendor() {
            return "browser";
        }

        @Override
        public String getVmVersion() {
            return System.getProperty("java.vm.version", "unknown");
        }

        @Override
        public String getSpecName() {
            return "Java Virtual Machine Specification";
        }

        @Override
        public String getSpecVendor() {
            return "Oracle Corporation";
        }

        @Override
        public String getSpecVersion() {
            return System.getProperty("java.specification.version", "25");
        }

        @Override
        public String getManagementSpecVersion() {
            return "1.0";
        }

        @Override
        public String getClassPath() {
            return "";
        }

        @Override
        public String getLibraryPath() {
            return "";
        }

        @Override
        public boolean isBootClassPathSupported() {
            return false;
        }

        @Override
        public String getBootClassPath() {
            throw new UnsupportedOperationException("boot class path");
        }

        @Override
        public List<String> getInputArguments() {
            return List.of();
        }

        @Override
        public long getUptime() {
            return Math.max(0L, System.currentTimeMillis() - startTime);
        }

        @Override
        public long getStartTime() {
            return startTime;
        }

        @Override
        public Map<String, String> getSystemProperties() {
            return Collections.emptyMap();
        }

        @Override
        public ObjectName getObjectName() {
            return objectName;
        }
    }
}
