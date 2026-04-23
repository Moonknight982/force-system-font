package com.mk982.forcesystemfont;

import android.graphics.Typeface;
import android.os.Build;
import android.widget.TextView;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final Map<String, Typeface> cache = new HashMap<>();
    private static final ThreadLocal<Boolean> inHook = ThreadLocal.withInitial(() -> false);
    
    // The "Ghost Tagger" - Safely tracks Mono fonts without causing lag
    private static final Map<Typeface, Boolean> monoTracker = Collections.synchronizedMap(new WeakHashMap<>());

    private static Typeface getSystemTypeface(int weight, boolean italic) {
        String key = weight + "_" + italic;
        if (cache.containsKey(key)) return cache.get(key);

        Typeface tf;
        inHook.set(true);
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                tf = Typeface.create(Typeface.DEFAULT, weight, italic);
            } else {
                String targetFamily = "sans-serif";
                int style = italic ? Typeface.ITALIC : Typeface.NORMAL;

                if (weight <= 200)      { targetFamily = "sans-serif-thin"; }
                else if (weight <= 300) { targetFamily = "sans-serif-light"; }
                else if (weight <= 550) { targetFamily = "sans-serif-medium"; }
                else if (weight >= 600) {
                    style = italic ? Typeface.BOLD_ITALIC : Typeface.BOLD;
                }
                tf = Typeface.create(targetFamily, style);
            }
        } finally {
            inHook.set(false);
        }

        cache.put(key, tf);
        return tf;
    }

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

    private static int extractWeight(Typeface tf) {
        if (tf == null) return 400;
        try {
            if (Build.VERSION.SDK_INT >= 28) return tf.getWeight();
            return (tf.getStyle() & Typeface.BOLD) != 0 ? 700 : 400;
        } catch (Throwable ignored) {
            return 400;
        }
    }

    private static boolean extractItalic(Typeface tf) {
        if (tf == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 28) return tf.isItalic();
            return (tf.getStyle() & Typeface.ITALIC) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int weightFromStyle(int style) {
        return (style & Typeface.BOLD) != 0 ? 700 : 400;
    }

    private static boolean isTaggedMono(Typeface tf) {
        if (tf == null) return false;
        return tf.equals(Typeface.MONOSPACE) || monoTracker.containsKey(tf);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {

        // HOOK 1: String Creation
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "create", String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        String family = (String) param.args[0];
                        
                        // Let Android build mono natively so italics survive
                        if (family != null && family.toLowerCase().contains("mono")) return;

                        int style = (int) param.args[1];
                        boolean italic = (style & Typeface.ITALIC) != 0;
                        int weight = weightFromStyle(style);

                        if (family != null) {
                            String lFamily = family.toLowerCase();
                            if (lFamily.contains("medium"))  weight = 500;
                            else if (lFamily.contains("light")) weight = 300;
                            else if (lFamily.contains("thin"))  weight = 100;
                            else if (lFamily.contains("black")) weight = 900;
                        }

                        param.setResult(getSystemTypeface(weight, italic));
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String family = (String) param.args[0];
                        if (family != null && family.toLowerCase().contains("mono")) {
                            Typeface tf = (Typeface) param.getResult();
                            if (tf != null) monoTracker.put(tf, true); // Slap the ghost tag on it
                        }
                    }
                });

        // HOOK 2: Asset Creation (Catches Discord/Reddit custom fonts)
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "createFromAsset",
                android.content.res.AssetManager.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        String path = (String) param.args[1];
                        
                        if (path != null && path.toLowerCase().contains("mono")) return;

                        int weight = inferWeightFromName(path);
                        boolean italic = path != null && (path.toLowerCase().contains("italic") || path.toLowerCase().contains("oblique"));
                        param.setResult(getSystemTypeface(weight, italic));
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String path = (String) param.args[1];
                        if (path != null && path.toLowerCase().contains("mono")) {
                            Typeface tf = (Typeface) param.getResult();
                            if (tf != null) monoTracker.put(tf, true); // Slap the ghost tag on it
                        }
                    }
                });

        // HOOK 3: File Creation
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "createFromFile", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        String path = (String) param.args[0];
                        
                        if (path != null && path.toLowerCase().contains("mono")) return;

                        int weight = inferWeightFromName(path);
                        boolean italic = path != null && (path.toLowerCase().contains("italic") || path.toLowerCase().contains("oblique"));
                        param.setResult(getSystemTypeface(weight, italic));
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String path = (String) param.args[0];
                        if (path != null && path.toLowerCase().contains("mono")) {
                            Typeface tf = (Typeface) param.getResult();
                            if (tf != null) monoTracker.put(tf, true); // Slap the ghost tag on it
                        }
                    }
                });

        // HOOK 4: The UI Guard (Brings the module back to life!)
        XposedHelpers.findAndHookMethod("android.widget.TextView", lpparam.classLoader,
                "setTypeface", Typeface.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        Typeface tf = (Typeface) param.args[0];
                        
                        // If it has our ghost tag, let it pass untouched
                        if (tf == null || isTaggedMono(tf)) return;
                        
                        param.args[0] = getSystemTypeface(extractWeight(tf), extractItalic(tf));
                    }
                });

        // HOOK 5: Dynamic TextView Styling (The span catcher)
        XposedHelpers.findAndHookMethod("android.widget.TextView", lpparam.classLoader,
                "setTypeface", Typeface.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        
                        Typeface baseTf = (Typeface) param.args[0];
                        if (baseTf == null) {
                            TextView tv = (TextView) param.thisObject;
                            baseTf = tv.getTypeface();
                        }
                        
                        // If the base text is already a tagged mono, don't wipe it out
                        if (isTaggedMono(baseTf)) return;

                        int style = (int) param.args[1];
                        int baseWeight = extractWeight(baseTf);
                        boolean baseItalic = extractItalic(baseTf);

                        boolean requestedItalic = (style & Typeface.ITALIC) != 0;
                        boolean requestedBold = (style & Typeface.BOLD) != 0;

                        int finalWeight = requestedBold ? Math.max(baseWeight, 700) : baseWeight;
                        boolean finalItalic = baseItalic || requestedItalic;

                        param.args[0] = getSystemTypeface(finalWeight, finalItalic);
                        param.args[1] = finalItalic ? Typeface.ITALIC : Typeface.NORMAL;
                    }
                });
    }
}
