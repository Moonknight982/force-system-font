package com.example.forcesystemfont;

import android.graphics.Typeface;
import android.os.Build;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    // Cache to avoid recreating typefaces
    private static final Map<String, Typeface> cache = new HashMap<>();

    // Guards against infinite recursion when our hooks call Typeface methods internally
    private static final ThreadLocal<Boolean> inHook = ThreadLocal.withInitial(() -> false);

    // Tracks every monospace typeface we've created so we can recognize derived ones
    private static final Set<Typeface> monoTypefaces =
            Collections.newSetFromMap(new java.util.WeakHashMap<>());

    // Apps that bundle their own hook framework (LSPlant, AliyunHook, etc.)
    // Hooks 1-5 (Typeface creation) are skipped for these to avoid class-init
    // collision crashes. Hooks 6-8 (Paint/TextView.setTypeface) still apply.
    private static final Set<String> SKIP_CREATION_HOOKS = new HashSet<>(
            java.util.Arrays.asList(
                    "com.exteragram.messenger",
                    "org.telegram.messenger",
                    "org.thunderdog.challegram",
                    "com.nagram.messenger",
                    "com.ayugram.messenger"
            )
    );

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean isMonoName(String name) {
        if (name == null) return false;
        String l = name.toLowerCase();
        return l.contains("mono")
                || l.contains("courier")
                || l.contains("code")
                || l.contains("console")
                || l.contains("fixed")
                || l.contains("typewriter")
                || l.contains("nerd");
    }

    private static boolean inferItalic(String name) {
        if (name == null) return false;
        String l = name.toLowerCase();
        return l.contains("italic") || l.contains("oblique");
    }

    private static int inferWeightFromName(String name) {
        if (name == null) return 400;
        String l = name.toLowerCase();
        if (l.contains("black") || l.contains("extrabold") || l.contains("heavy")) return 900;
        if (l.contains("semibold") || l.contains("demibold") || l.contains("demi")) return 600;
        if (l.contains("bold"))                                                       return 700;
        if (l.contains("medium"))                                                     return 500;
        if (l.contains("extralight"))                                                 return 200;
        if (l.contains("light"))                                                      return 300;
        if (l.contains("thin") || l.contains("hairline"))                            return 100;
        return 400;
    }

    private static int weightFromStyle(int style) {
        return (style & Typeface.BOLD) != 0 ? 700 : 400;
    }

    // Returns true if the given typeface is or was derived from a monospace typeface
    private static boolean isMono(Typeface tf) {
        if (tf == null) return false;
        return tf == Typeface.MONOSPACE || monoTypefaces.contains(tf);
    }

    // ── Typeface builders ─────────────────────────────────────────────────────

    private static Typeface getMonoTypeface(boolean italic) {
        String key = "mono_" + italic;
        if (cache.containsKey(key)) return cache.get(key);

        Typeface tf;
        inHook.set(true);
        try {
            tf = Typeface.create("monospace", italic ? Typeface.ITALIC : Typeface.NORMAL);
        } finally {
            inHook.set(false);
        }

        cache.put(key, tf);
        monoTypefaces.add(tf); // register so derived typefaces are also recognized
        return tf;
    }

    private static Typeface getSystemTypeface(int weight, boolean italic) {
        String key = weight + "_" + italic;
        if (cache.containsKey(key)) return cache.get(key);

        Typeface tf;
        inHook.set(true);
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                // API 28+: request exact weight — best path
                tf = Typeface.create(Typeface.DEFAULT, weight, italic);
            } else {
                // Pre-28: map to named families
                String family;
                int style = italic ? Typeface.ITALIC : Typeface.NORMAL;

                if (weight <= 200)      { family = "sans-serif-thin"; }
                else if (weight <= 300) { family = "sans-serif-light"; }
                else if (weight <= 450) { family = "sans-serif"; }
                else if (weight <= 550) { family = "sans-serif-medium"; }
                else {
                    family = "sans-serif";
                    style  = italic ? Typeface.BOLD_ITALIC : Typeface.BOLD;
                }
                tf = Typeface.create(family, style);
            }
        } finally {
            inHook.set(false);
        }

        cache.put(key, tf);
        return tf;
    }

    // ── Main hook entry ───────────────────────────────────────────────────────

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {

        final boolean skipCreation = SKIP_CREATION_HOOKS.contains(lpparam.packageName);

        // ── HOOK 1 ────────────────────────────────────────────────────────────
        // Typeface.create(String family, int style)
        // Old-school path + apps that explicitly request named families
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "create", String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        if (skipCreation) return;

                        String family  = (String) param.args[0];
                        int style      = (int)    param.args[1];
                        boolean italic = (style & Typeface.ITALIC) != 0;

                        // Mono check first
                        if (isMonoName(family)) {
                            param.setResult(getMonoTypeface(italic));
                            return;
                        }

                        int weight = weightFromStyle(style);

                        // Pull weight hint from family name if present
                        if (family != null) {
                            if      (family.contains("medium"))  weight = 500;
                            else if (family.contains("light"))   weight = 300;
                            else if (family.contains("thin"))    weight = 100;
                            else if (family.contains("black"))   weight = 900;
                        }

                        param.setResult(getSystemTypeface(weight, italic));
                    }
                });

        // ── HOOK 2 ────────────────────────────────────────────────────────────
        // Typeface.create(Typeface base, int style)
        // Apps derive a bold/italic variant from an existing typeface object
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "create", Typeface.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        if (skipCreation) return;

                        Typeface base  = (Typeface) param.args[0];
                        int style      = (int)      param.args[1];
                        boolean italic = (style & Typeface.ITALIC) != 0;

                        // Preserve mono if base was mono
                        if (isMono(base)) {
                            Typeface result = getMonoTypeface(italic);
                            monoTypefaces.add(result);
                            param.setResult(result);
                            return;
                        }

                        int weight = weightFromStyle(style);
                        param.setResult(getSystemTypeface(weight, italic));
                    }
                });

        // ── HOOK 3 ────────────────────────────────────────────────────────────
        // Typeface.create(Typeface base, int weight, boolean italic)  [API 28+]
        // THE Material Design / Instagram / Google Apps path
        if (Build.VERSION.SDK_INT >= 28) {
            XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                    "create", Typeface.class, int.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (inHook.get()) return;
                            if (skipCreation) return;

                            Typeface base  = (Typeface)  param.args[0];
                            int weight     = (int)        param.args[1];
                            boolean italic = (boolean)    param.args[2];

                            // Preserve mono if base was mono
                            if (isMono(base)) {
                                Typeface result = getMonoTypeface(italic);
                                monoTypefaces.add(result);
                                param.setResult(result);
                                return;
                            }

                            param.setResult(getSystemTypeface(weight, italic));
                        }
                    });
        }

        // ── HOOK 4 ────────────────────────────────────────────────────────────
        // Typeface.createFromAsset(AssetManager, String path)
        // ⚠️ afterHookedMethod — lets <clinit> complete safely, then swaps result.
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "createFromAsset",
                android.content.res.AssetManager.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        if (skipCreation) return;

                        String path    = (String) param.args[1];
                        boolean italic = inferItalic(path);

                        if (isMonoName(path)) {
                            param.setResult(getMonoTypeface(italic));
                            return;
                        }

                        param.setResult(getSystemTypeface(inferWeightFromName(path), italic));
                    }
                });

        // ── HOOK 5 ────────────────────────────────────────────────────────────
        // Typeface.createFromFile(String path)
        // ⚠️ afterHookedMethod — same reason as Hook 4
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "createFromFile", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        if (skipCreation) return;

                        String path    = (String) param.args[0];
                        boolean italic = inferItalic(path);

                        if (isMonoName(path)) {
                            param.setResult(getMonoTypeface(italic));
                            return;
                        }

                        param.setResult(getSystemTypeface(inferWeightFromName(path), italic));
                    }
                });

        // ── HOOK 6 ────────────────────────────────────────────────────────────
        // Paint.setTypeface — last-resort safety net
        // NOTE: NOT skipped for Telegram forks — safe at render time
        XposedHelpers.findAndHookMethod("android.graphics.Paint", lpparam.classLoader,
                "setTypeface", Typeface.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        Typeface tf = (Typeface) param.args[0];
                        if (tf == null) return;

                        boolean italic = false;
                        int weight     = 400;
                        try {
                            if (Build.VERSION.SDK_INT >= 28) {
                                weight = tf.getWeight();
                                italic = tf.isItalic();
                            } else {
                                int style = tf.getStyle();
                                italic    = (style & Typeface.ITALIC) != 0;
                                weight    = (style & Typeface.BOLD)   != 0 ? 700 : 400;
                            }
                        } catch (Throwable ignored) {}

                        if (isMono(tf)) {
                            param.args[0] = getMonoTypeface(italic);
                        } else {
                            param.args[0] = getSystemTypeface(weight, italic);
                        }
                    }
                });

        // ── HOOK 7 ────────────────────────────────────────────────────────────
        // TextView.setTypeface(Typeface)
        // NOTE: NOT skipped for Telegram forks
        XposedHelpers.findAndHookMethod("android.widget.TextView", lpparam.classLoader,
                "setTypeface", Typeface.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        Typeface tf = (Typeface) param.args[0];
                        if (tf == null) return;

                        boolean italic = false;
                        int weight     = 400;
                        try {
                            if (Build.VERSION.SDK_INT >= 28) {
                                weight = tf.getWeight();
                                italic = tf.isItalic();
                            } else {
                                int style = tf.getStyle();
                                italic    = (style & Typeface.ITALIC) != 0;
                                weight    = (style & Typeface.BOLD)   != 0 ? 700 : 400;
                            }
                        } catch (Throwable ignored) {}

                        if (isMono(tf)) {
                            param.args[0] = getMonoTypeface(italic);
                        } else {
                            param.args[0] = getSystemTypeface(weight, italic);
                        }
                    }
                });

        // ── HOOK 8 ────────────────────────────────────────────────────────────
        // TextView.setTypeface(Typeface, int style)
        // NOTE: NOT skipped for Telegram forks
        XposedHelpers.findAndHookMethod("android.widget.TextView", lpparam.classLoader,
                "setTypeface", Typeface.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        Typeface tf = (Typeface) param.args[0];
                        int style   = (int)      param.args[1];
                        boolean italic = (style & Typeface.ITALIC) != 0;
                        int weight     = weightFromStyle(style);

                        if (isMono(tf)) {
                            param.args[0] = getMonoTypeface(italic);
                        } else {
                            param.args[0] = getSystemTypeface(weight, italic);
                        }
                        param.args[1] = italic ? Typeface.ITALIC : Typeface.NORMAL;
                    }
                });
    }
}
    
