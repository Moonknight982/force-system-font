package com.example.forcesystemfont;

import android.graphics.Typeface;
import android.os.Build;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final Map<String, Typeface> cache = new HashMap<>();
    private static final ThreadLocal<Boolean> inHook = ThreadLocal.withInitial(() -> false);

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean isMonoName(String name) {
        if (name == null) return false;
        String l = name.toLowerCase();
        return l.contains("mono")       ||
               l.contains("courier")    ||
               l.contains("code")       ||
               l.contains("console")    ||
               l.contains("fixed")      ||
               l.contains("typewriter") ||
               l.contains("nerd");
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
        if (l.contains("light") && !l.contains("extra"))                             return 300;
        if (l.contains("extralight"))                                                 return 200;
        if (l.contains("thin") || l.contains("hairline"))                            return 100;
        return 400;
    }

    private static int weightFromStyle(int style) {
        return (style & Typeface.BOLD) != 0 ? 700 : 400;
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
        return tf;
    }

    private static Typeface getSystemTypeface(int weight, boolean italic) {
        String key = weight + "_" + italic;
        if (cache.containsKey(key)) return cache.get(key);

        Typeface tf;
        inHook.set(true);
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                // API 28+: exact weight — best path
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

        // ── HOOK 1 ────────────────────────────────────────────────────────────
        // Typeface.create(String family, int style)
        // Old-school path + apps that explicitly request named families
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "create", String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;

                        String family = (String) param.args[0];
                        int style     = (int)    param.args[1];
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
        // Typeface.create(Typeface family, int style)
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "create", Typeface.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        int style      = (int) param.args[1];
                        boolean italic = (style & Typeface.ITALIC) != 0;
                        int weight     = weightFromStyle(style);
                        param.setResult(getSystemTypeface(weight, italic));
                    }
                });

        // ── HOOK 3 ────────────────────────────────────────────────────────────
        // Typeface.create(Typeface family, int weight, boolean italic)  [API 28+]
        // THE Material Design / Instagram / Google Apps path
        if (Build.VERSION.SDK_INT >= 28) {
            XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                    "create", Typeface.class, int.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (inHook.get()) return;
                            int weight     = (int)     param.args[1];
                            boolean italic = (boolean) param.args[2];
                            param.setResult(getSystemTypeface(weight, italic));
                        }
                    });
        }

        // ── HOOK 4 ────────────────────────────────────────────────────────────
        // Typeface.createFromAsset(AssetManager, String path)
        // ⚠️ afterHookedMethod — lets <clinit> complete safely, then swaps result.
        // This fixes the SIGILL crash on Telegram forks and similar apps.
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "createFromAsset",
                android.content.res.AssetManager.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
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
        XposedHelpers.findAndHookMethod("android.graphics.Paint", lpparam.classLoader,
                "setTypeface", Typeface.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        Typeface tf = (Typeface) param.args[0];
                        if (tf == null) return;

                        int weight     = 400;
                        boolean italic = false;
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

                        param.args[0] = getSystemTypeface(weight, italic);
                    }
                });

        // ── HOOK 7 ────────────────────────────────────────────────────────────
        // TextView.setTypeface(Typeface)
        XposedHelpers.findAndHookMethod("android.widget.TextView", lpparam.classLoader,
                "setTypeface", Typeface.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        Typeface tf = (Typeface) param.args[0];
                        if (tf == null) return;

                        int weight     = 400;
                        boolean italic = false;
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

                        param.args[0] = getSystemTypeface(weight, italic);
                    }
                });

        // ── HOOK 8 ────────────────────────────────────────────────────────────
        // TextView.setTypeface(Typeface, int style)
        XposedHelpers.findAndHookMethod("android.widget.TextView", lpparam.classLoader,
                "setTypeface", Typeface.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        int style      = (int) param.args[1];
                        boolean italic = (style & Typeface.ITALIC) != 0;
                        int weight     = weightFromStyle(style);
                        param.args[0]  = getSystemTypeface(weight, italic);
                        param.args[1]  = italic ? Typeface.ITALIC : Typeface.NORMAL;
                    }
                });
    }
}
