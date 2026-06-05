package net.zerohpminecraft;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * All Litematica / malilib reflection in one place, so the rest of the mod doesn't
 * carry a hard compile dependency on a mod that may not be present (and whose internals
 * shift between versions and forks). Litematica is a soft dependency.
 *
 * <p>The carpet auto-printer ({@link AutoPrintHandler}) places blocks via the Litematica
 * <em>printer</em> — the continuous, every-tick auto-placement added by the printer fork
 * (aleksilassila / Icetank {@code litematica-printer}). Loominary only toggles it on while
 * laying the floor and off while navigating / restocking; the fork does the placement.
 *
 * <p>The printer's persistent on/off flag is the fork's {@code PRINT_MODE} config (a malilib
 * {@code ConfigBoolean} named "printingMode" — "Autobuild / print loaded selection"), held on
 * {@code me.aleksilassila.litematica.printer.<mcver>.LitematicaMixinMod}. Confirmed by
 * decompiling {@code litematica-printer-3.4.0-mc1.21.4.jar}. We set its value directly via
 * {@code IConfigBoolean.setBooleanValue} (idempotent, so ON/OFF is deterministic) rather than
 * firing the {@code TOGGLE_PRINTING_MODE} hotkey, which only flips. If the exact class can't be
 * found (a different fork/version) we fall back to scanning base Litematica's
 * {@code Configs$Generic} for any boolean config named like "printer".
 */
public final class LitematicaBridge {

    private static final String TAG = "[Loominary]";

    private LitematicaBridge() {}

    // Cached reflective handles, resolved lazily on first use.
    private static boolean resolved = false;
    private static Object  printerConfig = null;       // the IConfigBoolean instance
    private static Method  getBooleanValue = null;
    private static Method  setBooleanValue = null;
    private static boolean warnedMissing = false;

    /** True if Litematica (any variant) is on the classpath. */
    public static boolean litematicaPresent() {
        try {
            Class.forName("fi.dy.masa.litematica.config.Configs");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Turns the Litematica printer on or off. Returns true if the toggle was applied,
     * false if no printer config could be found (Litematica/printer fork absent).
     */
    public static boolean setPrinter(boolean on) {
        resolve();
        if (printerConfig == null || setBooleanValue == null) {
            if (!warnedMissing) {
                warnedMissing = true;
                System.out.println(TAG + " No Litematica printer config found — is the "
                        + "litematica-printer fork installed? Auto-print can't place blocks.");
            }
            return false;
        }
        try {
            setBooleanValue.invoke(printerConfig, on);
            return true;
        } catch (Throwable t) {
            System.out.println(TAG + " Failed to toggle the Litematica printer: " + t.getMessage());
            return false;
        }
    }

    /** Current printer state, or false if it can't be read. */
    public static boolean isPrinterOn() {
        resolve();
        if (printerConfig == null || getBooleanValue == null) return false;
        try {
            return (boolean) getBooleanValue.invoke(printerConfig);
        } catch (Throwable t) {
            return false;
        }
    }

    /** True if a printer toggle was located (the fork is installed and wired). */
    public static boolean printerAvailable() {
        resolve();
        return printerConfig != null && setBooleanValue != null;
    }

    // ── Printer hotbar-slot count (how many colours can be held without swapping) ───────────────
    private static boolean slotsResolved = false;
    private static Object  hotbarSlotsConfig = null;
    private static Method  getStringValue = null;
    private static final int DEFAULT_SLOTS = 9;

    /**
     * How many hotbar slots the printer is allowed to use ({@code PRINTER_HOTBAR_SLOTS}) — the
     * number of carpet colours it can keep in hand without swapping from the main inventory.
     * Read from the fork's config; defaults to 9 (the max) if unavailable.
     */
    public static int printerHotbarSlots() {
        resolveSlots();
        if (hotbarSlotsConfig == null || getStringValue == null) return DEFAULT_SLOTS;
        try {
            String s = (String) getStringValue.invoke(hotbarSlotsConfig);
            if (s == null) return DEFAULT_SLOTS;
            java.util.Set<Character> seen = new java.util.HashSet<>();
            for (char c : s.toCharArray()) if (c >= '1' && c <= '9') seen.add(c);   // slot digits 1–9
            return seen.isEmpty() ? DEFAULT_SLOTS : seen.size();
        } catch (Throwable t) {
            return DEFAULT_SLOTS;
        }
    }

    private static void resolveSlots() {
        if (slotsResolved) return;
        slotsResolved = true;
        String[] classes = {
            "me.aleksilassila.litematica.printer.v1_21_4.config.PrinterConfig",
            "me.aleksilassila.litematica.printer.v1_21_5.config.PrinterConfig",
            "me.aleksilassila.litematica.printer.v1_21_3.config.PrinterConfig",
        };
        for (String cls : classes) {
            try {
                Field f = Class.forName(cls).getField("PRINTER_HOTBAR_SLOTS");
                Object cfg = f.get(null);
                hotbarSlotsConfig = cfg;
                getStringValue = cfg.getClass().getMethod("getStringValue");
                return;
            } catch (Throwable ignored) {
                // not this version — try the next
            }
        }
    }

    // ── Printer block-place range (how far the printer reaches to place) ────────────────────────
    private static boolean rangeResolved = false;
    private static Object  rangeConfig = null;
    private static Method  getDoubleValue = null;
    private static final double DEFAULT_RANGE = 4.5;

    /** The printer's place range ({@code PRINTING_RANGE}); defaults to 4.5 if unavailable. */
    public static double printerRange() {
        resolveRange();
        if (rangeConfig == null || getDoubleValue == null) return DEFAULT_RANGE;
        try {
            double d = (double) getDoubleValue.invoke(rangeConfig);
            return d > 0 ? d : DEFAULT_RANGE;
        } catch (Throwable t) {
            return DEFAULT_RANGE;
        }
    }

    private static void resolveRange() {
        if (rangeResolved) return;
        rangeResolved = true;
        String[] classes = {
            "me.aleksilassila.litematica.printer.v1_21_4.LitematicaMixinMod",
            "me.aleksilassila.litematica.printer.v1_21_5.LitematicaMixinMod",
            "me.aleksilassila.litematica.printer.v1_21_3.LitematicaMixinMod",
        };
        for (String cls : classes) {
            try {
                Field f = Class.forName(cls).getField("PRINTING_RANGE");
                Object cfg = f.get(null);
                rangeConfig = cfg;
                getDoubleValue = cfg.getClass().getMethod("getDoubleValue");
                return;
            } catch (Throwable ignored) {
                // not this version — try the next
            }
        }
    }

    // litematica-printer's PRINT_MODE lives on a version-stamped class. Try the versions we
    // know about; the project targets MC 1.21.4, so v1_21_4 is the live one.
    private static final String[] PRINTER_MOD_CLASSES = {
        "me.aleksilassila.litematica.printer.v1_21_4.LitematicaMixinMod",
        "me.aleksilassila.litematica.printer.v1_21_5.LitematicaMixinMod",
        "me.aleksilassila.litematica.printer.v1_21_3.LitematicaMixinMod",
    };

    private static void resolve() {
        if (resolved) return;
        resolved = true;

        Class<?> icb;
        try {
            icb = Class.forName("fi.dy.masa.malilib.config.IConfigBoolean");
            getBooleanValue = icb.getMethod("getBooleanValue");
            setBooleanValue = icb.getMethod("setBooleanValue", boolean.class);
        } catch (Throwable t) {
            return;   // malilib absent — Litematica isn't here at all
        }

        // Primary: the printer fork's PRINT_MODE ("printingMode") continuous-print toggle.
        for (String cls : PRINTER_MOD_CLASSES) {
            try {
                Field f = Class.forName(cls).getField("PRINT_MODE");
                Object cfg = f.get(null);
                if (icb.isInstance(cfg)) {
                    printerConfig = cfg;
                    System.out.println(TAG + " Litematica printer bound: " + cls + ".PRINT_MODE");
                    return;
                }
            } catch (Throwable ignored) {
                // not this version — try the next
            }
        }

        // Fallback: scan base Litematica's Configs$Generic for a boolean config named "printer".
        try {
            Class<?> generic = Class.forName("fi.dy.masa.litematica.config.Configs$Generic");
            Method getName = configNameMethod(icb);
            for (Field f : generic.getDeclaredFields()) {
                Object cfg = getStatic(f);
                if (cfg == null || !icb.isInstance(cfg)) continue;
                String fieldName = f.getName().toLowerCase();
                String cfgName = configName(getName, cfg);
                if (fieldName.contains("printer")
                        || (cfgName != null && cfgName.toLowerCase().contains("printer"))) {
                    printerConfig = cfg;
                    System.out.println(TAG + " Litematica printer config bound (fallback): "
                            + f.getName() + (cfgName != null ? " (\"" + cfgName + "\")" : ""));
                    return;
                }
            }
            System.out.println(TAG + " Litematica present but no printer toggle found — is the "
                    + "litematica-printer fork installed?");
        } catch (Throwable t) {
            // base Litematica absent or changed — soft-fail.
        }
    }

    private static Object getStatic(Field f) {
        try {
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** malilib's IConfigBase exposes getName(); resolve it once for matching by config name. */
    private static Method configNameMethod(Class<?> icb) {
        try {
            // IConfigBoolean extends IConfigBase, which declares getName().
            return icb.getMethod("getName");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String configName(Method getName, Object cfg) {
        if (getName == null) return null;
        try {
            Object n = getName.invoke(cfg);
            return n == null ? null : n.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
