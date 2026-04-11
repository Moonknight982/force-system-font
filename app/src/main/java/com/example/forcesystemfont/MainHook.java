package com.example.forcesystemfont;

import android.graphics.Typeface;
import android.os.Build;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final Map<String, Typeface> cache = new HashMap<>();

    // Guards against infinite recursion when getSystemTypeface calls Typeface.create internally
    private static final ThreadLocal<Boolean> inHook = ThreadLocal.withInitial(() -> false);

    private static Typeface getSystemTypeface(int weight, boolean italic) {
        String key = weight + "_" + italic;
        if (cache.containsKey(key)) return cache.get(key);

        Typeface tf;
        inHook.set(true);
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                // API 28+: can request any exact weight directly — this is the right tool
                tf = Typeface.create(Typeface.DEFAULT, weight, italic);
            } else {
                // Pre-28: map to named font families (only a few exist)
                String family;
                int style = italic ? Typeface.ITALIC : Typeface.NORMAL;

                if (weight <= 200)      { family = "sans-serif-thin"; }
                else if (weight <= 300) { family = "sans-serif-light"; }
                else if (weight <= 450) { family = "sans-serif"; }
                else if (weight <= 550) { family = "sans-serif-medium"; }
                else {
                    // 600–900: no named semibold/extrabold family, use bold style
                    family = "sans-serif";
                    style = italic ? Typeface.BOLD_ITALIC : Typeface.BOLD;
                }
                tf = Typeface.create(family, style);
            }
        } finally {
            inHook.set(false);
        }

        cache.put(key, tf);
        return tf;
    }

    // Infer weight from a filename — covers Instagram, Google, most apps
    // e.g. "InstagramSans-Medium.ttf" → 500, "Roboto-Bold.ttf" → 700
    private static int inferWeightFromName(String name) {
        if (name == null) return 400;
        String l = name.toLowerCase();
        if (l.contains("black") || l.contains("extrabold") || l.contains("heavy")) return 900;
        if (l.contains("semibold") || l.contains("demibold") || l.contains("demi")) return 600;
        if (l.contains("bold"))   return 700;
        if (l.contains("medium")) return 500;
        if (l.contains("light"))  return 300;
        if (l.contains("thin") || l.contains("extralight") || l.contains("hairline")) return 100;
        return 400;
    }

    private static int weightFromStyle(int style) {
        return (style & Typeface.BOLD) != 0 ? 700 : 400;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {

        // ── HOOK 1 ──────────────────────────────────────────────────────────────
        // Typeface.create(String family, int style)
        // Old-school path: apps pass "sans-serif-medium", NORMAL → intercept here
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "create", String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return; // let our own internal call through
                        int style = (int) param.args[1];
                        boolean italic = (style & Typeface.ITALIC) != 0;
                        int weight = weightFromStyle(style);

                        // Also check the family name itself for weight hints
                        // e.g. app explicitly asked for "sans-serif-medium"
                        String family = (String) param.args[0];
                        if (family != null) {
                            if (family.contains("medium"))  weight = 500;
                            else if (family.contains("light")) weight = 300;
                            else if (family.contains("thin"))  weight = 100;
                            else if (family.contains("black")) weight = 900;
                        }

                        param.setResult(getSystemTypeface(weight, italic));
                    }
                });

        // ── HOOK 2 ──────────────────────────────────────────────────────────────
        // Typeface.create(Typeface family, int style)
        // Apps derive a bold/italic variant from an existing typeface object
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "create", Typeface.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        int style = (int) param.args[1];
                        boolean italic = (style & Typeface.ITALIC) != 0;
                        int weight = weightFromStyle(style);
                        param.setResult(getSystemTypeface(weight, italic));
                    }
                });

        // ── HOOK 3 ──────────────────────────────────────────────────────────────
        // Typeface.create(Typeface family, int weight, boolean italic)  [API 28+]
        // THIS is the Material Design path — Instagram, Google apps, anything
        // using MaterialComponents or Compose all go through here for medium/semibold
        if (Build.VERSION.SDK_INT >= 28) {
            XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                    "create", Typeface.class, int.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (inHook.get()) return;
                            int weight   = (int)     param.args[1];
                            boolean italic = (boolean) param.args[2];
                            param.setResult(getSystemTypeface(weight, italic));
                        }
                    });
        }

        // ── HOOK 4 ──────────────────────────────────────────────────────────────
        // Typeface.createFromAsset(AssetManager, String path)
        // Apps that bundle their own fonts (Instagram, Twitter, etc.) hit this.
        // We infer weight from the filename since we can't read the file's OS/2 table.
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "createFromAsset",
                android.content.res.AssetManager.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        String path = (String) param.args[1];
                        int weight = inferWeightFromName(path);
                        // Assume non-italic unless filename says so
                        boolean italic = path != null &&
                                (path.toLowerCase().contains("italic") ||
                                 path.toLowerCase().contains("oblique"));
                        param.setResult(getSystemTypeface(weight, italic));
                    }
                });

        // ── HOOK 5 ──────────────────────────────────────────────────────────────
        // Typeface.createFromFile(String path) — same idea as above
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "createFromFile", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        String path = (String) param.args[0];
                        int weight = inferWeightFromName(path);
                        boolean italic = path != null &&
                                (path.toLowerCase().contains("italic") ||
                                 path.toLowerCase().contains("oblique"));
                        param.setResult(getSystemTypeface(weight, italic));
                    }
                });

        // ── HOOK 6 ──────────────────────────────────────────────────────────────
        // Paint.setTypeface — last-resort safety net for anything that slipped through
        XposedHelpers.findAndHookMethod("android.graphics.Paint", lpparam.classLoader,
                "setTypeface", Typeface.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        Typeface tf = (Typeface) param.args[0];
                        if (tf == null) return;

                        int weight = 400;
                        boolean italic = false;
                        try {
                            if (Build.VERSION.SDK_INT >= 28) {
                                weight = tf.getWeight();
                                italic = tf.isItalic();
                            } else {
                                int style = tf.getStyle();
                                italic = (style & Typeface.ITALIC) != 0;
                                weight = (style & Typeface.BOLD) != 0 ? 700 : 400;
                            }
                        } catch (Throwable ignored) {}

                        param.args[0] = getSystemTypeface(weight, italic);
                    }
                });

        // ── HOOK 7 ──────────────────────────────────────────────────────────────
        // TextView.setTypeface(Typeface) — single-arg variant, was missing entirely
        XposedHelpers.findAndHookMethod("android.widget.TextView", lpparam.classLoader,
                "setTypeface", Typeface.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        Typeface tf = (Typeface) param.args[0];
                        if (tf == null) return;

                        int weight = 400;
                        boolean italic = false;
                        try {
                            if (Build.VERSION.SDK_INT >= 28) {
                                weight = tf.getWeight();
                                italic = tf.isItalic();
                            } else {
                                int style = tf.getStyle();
                                italic = (style & Typeface.ITALIC) != 0;
                                weight = (style & Typeface.BOLD) != 0 ? 700 : 400;
                            }
                        } catch (Throwable ignored) {}

                        param.args[0] = getSystemTypeface(weight, italic);
                    }
                });

        // ── HOOK 8 ──────────────────────────────────────────────────────────────
        // TextView.setTypeface(Typeface, int style)
        XposedHelpers.findAndHookMethod("android.widget.TextView", lpparam.classLoader,
                "setTypeface", Typeface.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        int style    = (int) param.args[1];
                        boolean italic = (style & Typeface.ITALIC) != 0;
                        int weight   = weightFromStyle(style);
                        param.args[0] = getSystemTypeface(weight, italic);
                        param.args[1] = italic ? Typeface.ITALIC : Typeface.NORMAL;
                    }
                });
    }
}
