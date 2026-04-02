package com.spikeshield;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.ParticlesMode;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpikeShieldMod implements ClientModInitializer {

    public static final String MOD_ID = "spikeshield";
    public static final Logger LOGGER  = LoggerFactory.getLogger(MOD_ID);

    // ── Tick-lag fallback thresholds ─────────────────────────────────
    private static final long LAG_THRESHOLD_MS    = 120;  // tick > 120ms = spike
    private static final int  RECOVERY_TICKS      = 100;  // ~5s of healthy ticks to recover

    // ── FPS caps applied at each heat level ─────────────────────────
    private static final int FPS_WARN      = 45;
    private static final int FPS_CRITICAL  = 30;
    private static final int FPS_EMERGENCY = 20;

    // ── Entity distance scale (1.0 = full, 0.5 = half) ──────────────
    private static final double ENTITY_SCALE_HOT      = 0.75;
    private static final double ENTITY_SCALE_CRITICAL  = 0.5;
    private static final double ENTITY_SCALE_EMERGENCY = 0.25;

    // ── GC pressure threshold ─────────────────────────────────────────
    private static final double GC_THRESHOLD = 0.87;

    // ── Internal state ────────────────────────────────────────────────
    private long  lastTickTime   = 0;
    private int   recoveryTicks  = 0;
    private boolean lagActive    = false;

    // Saved original values so we can restore them
    private int    savedMaxFps      = 120;
    private double savedEntityScale = 1.0;

    // Rate-limit chat messages so we don't spam
    private int ticksSinceLastMessage = 0;
    private static final int MESSAGE_COOLDOWN = 200; // ~10 seconds

    // Track last alert level to avoid spamming the same message
    private int lastAlertLevel = 0; // 0=ok, 1=warn, 2=critical, 3=emergency

    @Override
    public void onInitializeClient() {
        ThermalMonitor.start();

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> ThermalMonitor.stop());

        LOGGER.info("[SpikeShield] Loaded. Monitoring CPU temperature and lag spikes.");
    }

    // ── Main tick handler ────────────────────────────────────────────
    private void onTick(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            lastTickTime = 0;
            return;
        }

        ticksSinceLastMessage++;

        long now   = System.currentTimeMillis();
        long delta = (lastTickTime == 0) ? 0 : (now - lastTickTime);
        lastTickTime = now;

        // ── Memory pressure GC nudge ─────────────────────────────────
        Runtime rt = Runtime.getRuntime();
        double heapUsed = (double)(rt.totalMemory() - rt.freeMemory()) / rt.maxMemory();
        if (heapUsed > GC_THRESHOLD) {
            System.gc();
        }

        // ── Determine current heat level ─────────────────────────────
        int temp       = ThermalMonitor.currentTempCelsius.get();
        boolean hasTmp = ThermalMonitor.thermalReadSupported.get();

        int alertLevel;

        if (hasTmp && temp >= ThermalMonitor.TEMP_EMERGENCY) {
            alertLevel = 3; // EMERGENCY
        } else if (hasTmp && temp >= ThermalMonitor.TEMP_CRITICAL) {
            alertLevel = 2; // CRITICAL
        } else if (hasTmp && temp >= ThermalMonitor.TEMP_WARN) {
            alertLevel = 1; // WARN
        } else if (!hasTmp && delta > LAG_THRESHOLD_MS) {
            // Fallback: no temp reading, detect via tick lag instead
            alertLevel = 2;
        } else {
            alertLevel = 0; // OK
        }

        // ── Apply actions based on heat level ────────────────────────
        switch (alertLevel) {

            case 3 -> { // EMERGENCY ≥94°C
                if (lastAlertLevel != 3) {
                    saveOriginalSettings(client);
                    applyEmergencySettings(client);
                    sendAlert(client,
                        "⚠ CPU CRITICAL: " + temp + "°C — Emergency mode ON. Close other apps NOW!",
                        Formatting.RED);
                    lastAlertLevel = 3;
                    lagActive = true;
                }
                // Keep nudging GC every alert
                System.gc();
            }

            case 2 -> { // CRITICAL ≥89°C or tick-lag fallback
                if (lastAlertLevel != 2 && lastAlertLevel != 3) {
                    saveOriginalSettings(client);
                    applyCriticalSettings(client);
                    String msg = hasTmp
                        ? "⚠ CPU HOT: " + temp + "°C — Reducing load. Check your vents!"
                        : "⚠ Severe lag spike detected — reducing load temporarily.";
                    sendAlert(client, msg, Formatting.GOLD);
                    lastAlertLevel = 2;
                    lagActive = true;
                }
            }

            case 1 -> { // WARN ≥82°C
                if (lastAlertLevel == 0) {
                    saveOriginalSettings(client);
                    applyWarnSettings(client);
                    sendAlert(client,
                        "⚡ CPU Warming: " + temp + "°C — Slightly reducing load as precaution.",
                        Formatting.YELLOW);
                    lastAlertLevel = 1;
                    lagActive = true;
                }
            }

            case 0 -> { // NORMAL — check recovery
                if (lagActive) {
                    recoveryTicks++;
                    if (recoveryTicks >= RECOVERY_TICKS) {
                        restoreSettings(client);
                        String msg = hasTmp
                            ? "✔ CPU cooled to " + temp + "°C — Settings restored."
                            : "✔ Lag resolved — Settings restored.";
                        sendAlert(client, msg, Formatting.GREEN);
                        lastAlertLevel = 0;
                        lagActive      = false;
                        recoveryTicks  = 0;
                    }
                } else {
                    recoveryTicks = 0;
                    lastAlertLevel = 0;
                }
            }
        }

        // If we recovered from a worse state to a better state, update settings
        if (lagActive && alertLevel < lastAlertLevel && alertLevel > 0) {
            lastAlertLevel = alertLevel;
            switch (alertLevel) {
                case 1 -> applyWarnSettings(client);
                case 2 -> applyCriticalSettings(client);
            }
        }
    }

    // ── Settings Levels ──────────────────────────────────────────────

    /** Save originals exactly once so we can restore them later */
    private void saveOriginalSettings(MinecraftClient client) {
        if (lagActive) return; // already saved
        savedMaxFps      = client.options.getMaxFps().getValue();
        savedEntityScale = client.options.getEntityDistanceScaling().getValue();
    }

    private void applyWarnSettings(MinecraftClient client) {
        client.options.getMaxFps().setValue(FPS_WARN);
        client.options.getEntityDistanceScaling().setValue(ENTITY_SCALE_HOT);
        client.options.getParticles().setValue(ParticlesMode.MINIMAL);
    }

    private void applyCriticalSettings(MinecraftClient client) {
        client.options.getMaxFps().setValue(FPS_CRITICAL);
        client.options.getEntityDistanceScaling().setValue(ENTITY_SCALE_CRITICAL);
        client.options.getParticles().setValue(ParticlesMode.MINIMAL);
        System.gc();
    }

    private void applyEmergencySettings(MinecraftClient client) {
        client.options.getMaxFps().setValue(FPS_EMERGENCY);
        client.options.getEntityDistanceScaling().setValue(ENTITY_SCALE_EMERGENCY);
        client.options.getParticles().setValue(ParticlesMode.MINIMAL);
        System.gc();
        System.gc(); // double nudge at emergency
    }

    private void restoreSettings(MinecraftClient client) {
        client.options.getMaxFps().setValue(savedMaxFps);
        client.options.getEntityDistanceScaling().setValue(savedEntityScale);
        // Leave particles as user set them (they said it's always minimal anyway)
    }

    // ── Chat helper ──────────────────────────────────────────────────
    private void sendAlert(MinecraftClient client, String message, Formatting color) {
        if (ticksSinceLastMessage < MESSAGE_COOLDOWN && ticksSinceLastMessage != 0) return;
        ticksSinceLastMessage = 0;

        if (client.player != null) {
            client.player.sendMessage(
                Text.literal("[SpikeShield] " + message).formatted(color),
                false  // false = chat, not action bar
            );
        }
        LOGGER.info("[SpikeShield] {}", message);
    }
}
