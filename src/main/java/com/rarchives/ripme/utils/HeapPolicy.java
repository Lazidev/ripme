package com.rarchives.ripme.utils;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.PlatformManagedObject;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Keeps a {@code java -jar} launch from sitting on the JVM's default heap.
 * On a large machine that default is many gigabytes, and G1 will not hand
 * unused regions back while the free portion stays under 70 percent.
 */
public final class HeapPolicy {

    /** Above this, a jar launch is restarted with an explicit modest heap. */
    private static final long RELAUNCH_ABOVE_BYTES = 2L * 1024 * 1024 * 1024;

    private HeapPolicy() {
    }

    /**
     * Restarts the jar once when the default max heap is huge.
     * A caller-supplied {@code -Xmx} of 2 GB or less is left alone.
     * Returns only when this process should continue; otherwise the process exits.
     */
    public static void relaunchIfUnbounded(String[] args) {
        if (Boolean.getBoolean("ripme.heapTuned")) {
            return;
        }
        if (Runtime.getRuntime().maxMemory() <= RELAUNCH_ABOVE_BYTES) {
            return;
        }
        Path jar = currentJar();
        if (jar == null) {
            return;
        }
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.add("-Xms64m");
        command.add("-Xmx512m");
        command.add("-XX:ReservedCodeCacheSize=64m");
        command.add("-XX:MinHeapFreeRatio=5");
        command.add("-XX:MaxHeapFreeRatio=20");
        command.add("-XX:G1PeriodicGCInterval=30000");
        command.add("-Dripme.heapTuned=true");
        command.add("-jar");
        command.add(jar.toAbsolutePath().toString());
        command.addAll(Arrays.asList(args));
        try {
            new ProcessBuilder(command).inheritIO().start();
        } catch (IOException e) {
            return;
        }
        System.exit(0);
    }

    /**
     * Asks G1 to return unused heap while this process is idle.
     * Min must be lowered before max, or the VM rejects the pair.
     */
    public static void apply() {
        setOption("MinHeapFreeRatio", "5");
        setOption("MaxHeapFreeRatio", "20");
        setOption("G1PeriodicGCInterval", "30000");
    }

    private static void setOption(String name, String value) {
        try {
            Class<? extends PlatformManagedObject> type = Class.forName("com.sun.management.HotSpotDiagnosticMXBean")
                    .asSubclass(PlatformManagedObject.class);
            Object bean = ManagementFactory.getPlatformMXBean(type);
            Method setVMOption = type.getMethod("setVMOption", String.class, String.class);
            setVMOption.invoke(bean, name, value);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Another JVM, or a flag that is not writable after startup.
        }
    }

    private static Path currentJar() {
        try {
            URL location = HeapPolicy.class.getProtectionDomain().getCodeSource().getLocation();
            if (location == null) {
                return null;
            }
            Path path = Path.of(location.toURI());
            String name = path.getFileName() == null ? "" : path.getFileName().toString();
            if (Files.isRegularFile(path) && name.endsWith(".jar")) {
                return path;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private static String javaExecutable() {
        return ProcessHandle.current().info().command().filter(command -> !command.isBlank()).orElseGet(() -> {
            String bin = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
            return Path.of(System.getProperty("java.home"), "bin", bin).toString();
        });
    }
}
