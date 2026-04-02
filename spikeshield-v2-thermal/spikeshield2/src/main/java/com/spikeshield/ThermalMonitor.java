package com.spikeshield;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reads actual CPU temperature from Windows every 5 seconds.
 * Uses MSAcpi_ThermalZoneTemperature via PowerShell (no admin needed on most laptops).
 * Falls back to tick-lag detection if the OS doesn't expose thermal data.
 */
public class ThermalMonitor {

    // ── Public state (read from main thread, written from bg thread) ──
    public static final AtomicInteger currentTempCelsius = new AtomicInteger(-1);
    public static final AtomicBoolean thermalReadSupported = new AtomicBoolean(false);

    // Temperature thresholds (Celsius)
    public static final int TEMP_WARN     = 82;   // yellow warning in chat
    public static final int TEMP_CRITICAL = 89;   // red alert + actions
    public static final int TEMP_EMERGENCY = 94;  // throttle is almost certain

    private static ScheduledExecutorService scheduler;
    private static boolean initialized = false;

    public static void start() {
        if (initialized) return;
        initialized = true;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SpikeShield-ThermalMonitor");
            t.setDaemon(true);   // won't block JVM shutdown
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

        // First reading after 3s, then every 5s
        scheduler.scheduleAtFixedRate(ThermalMonitor::pollTemperature, 3, 5, TimeUnit.SECONDS);
        SpikeShieldMod.LOGGER.info("[SpikeShield] Thermal monitor started.");
    }

    public static void stop() {
        if (scheduler != null) scheduler.shutdownNow();
    }

    // ── Temperature polling ──────────────────────────────────────────
    private static void pollTemperature() {
        try {
            // PowerShell reads ACPI thermal zones (built into Windows, no extra tools needed)
            // Temperature is returned in tenths of Kelvin
            ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Get-WmiObject -Namespace root/wmi -Class MSAcpi_ThermalZoneTemperature " +
                "| Select-Object -ExpandProperty CurrentTemperature"
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            int highestTemp = Integer.MIN_VALUE;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {

                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    try {
                        // Value is in tenths of Kelvin → convert to Celsius
                        int tenthsKelvin = Integer.parseInt(line);
                        int celsius = (int) Math.round(tenthsKelvin / 10.0 - 273.15);
                        if (celsius > highestTemp) highestTemp = celsius;
                    } catch (NumberFormatException ignored) {
                        // header line or garbage — skip
                    }
                }
            }

            proc.waitFor(3, TimeUnit.SECONDS);
            proc.destroyForcibly();

            if (highestTemp > Integer.MIN_VALUE && highestTemp > 0 && highestTemp < 120) {
                currentTempCelsius.set(highestTemp);
                thermalReadSupported.set(true);
            } else {
                // ACPI didn't return useful data — set -1 so we fall back to tick lag
                currentTempCelsius.set(-1);
            }

        } catch (Exception e) {
            currentTempCelsius.set(-1);
            SpikeShieldMod.LOGGER.warn("[SpikeShield] Temperature read failed: {}", e.getMessage());
        }
    }
}
