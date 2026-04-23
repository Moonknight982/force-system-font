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
    
    // The ultra-fast memory tracker for Mono fonts. (Thread-safe and prevents memory leaks)
    private static final Map<Typeface, Boolean> monoTracker = Collections.synchronizedMap(new WeakHashMap<>());

    private static Typeface getSafeSystemTypeface(boolean isMono, int weight, boolean italic) {
        String key = isMono + "_" + weight + "_" + italic;
        if (cache.containsKey(key)) return cache.get(key);

        Typeface tf;
        inHook.set(true);
        try {
            if (isMono) {
                // SAFE MONO: Bypasses the API 28 crash trap while forcing the Italic/Bold flags!
                int style = Typeface.NORMAL;
                if (weight >= 600 && italic) style = Typeface.BOLD_ITALIC;
                else if (weight >= 600) style = Typeface.BOLD;
                else if (italic) style = Typeface.ITALIC;
                
                tf = Typeface.create(Typeface.MONOSPACE, style);
                monoTracker.put(tf, Boolean.TRUE); // Tag it so we remember it's Mono
            } else {
                // SYSTEM VARIABLE: Full weight and slant control for normal text
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

    // Ultra-fast check utilizing our memory tracker
    private static boolean checkIsMono(Typeface tf) {
        if (tf == null) return false;
        return tf == Typeface.MONOSPACE || monoTracker.containsKey(tf);
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
                        int style = (int) param.args[1];
                        
                        boolean isMono = family != null && family.toLowerCase().contains("mono");
                        boolean italic = (style & Typeface.ITALIC) != 0;
                        int weight = weightFromStyle(style);

                        if (family != null) {
                            String lFamily = family.toLowerCase();
                            if (lFamily.contains("medium"))  weight = 500;
                            else if (lFamily.contains("light")) weight = 300;
                            else if (lFamily.contains("thin"))  weight = 100;
                            else if (lFamily.contains("black")) weight = 900;
                        }

                        param.setResult(getSafeSystemTypeface(isMono, weight, italic));
                    }
                });

        // HOOK 2: Typeface Creation
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "create", Typeface.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        Typeface baseTf = (Typeface) param.args[0];
                        int style = (int) param.args[1];

                        boolean isMono = checkIsMono(baseTf);
                        int baseWeight = extractWeight(baseTf);
                        boolean baseItalic = extractItalic(baseTf);
                        
                        boolean requestedItalic = (style & Typeface.ITALIC) != 0;
                        boolean requestedBold = (style & Typeface.BOLD) != 0;

                        int finalWeight = requestedBold ? Math.max(baseWeight, 700) : baseWeight;
                        boolean finalItalic = baseItalic || requestedItalic;

                        param.setResult(getSafeSystemTypeface(isMono, finalWeight, finalItalic));
                    }
                });

        // HOOK 3: API 28+ Exact Weight Creation
        if (Build.VERSION.SDK_INT >= 28) {
            XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                    "create", Typeface.class, int.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (inHook.get()) return;
                            Typeface baseTf = (Typeface) param.args[0];
                            int weight = (int) param.args[1];
                            boolean italic = (boolean) param.args[2];
                            
                            boolean isMono = checkIsMono(baseTf);
                            param.setResult(getSafeSystemTypeface(isMono, weight, italic));
                        }
                    });
        }

        // HOOK 4: Asset Creation
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "createFromAsset",
                android.content.res.AssetManager.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        String path = (String) param.args[1];
                        
                        boolean isMono = path != null && path.toLowerCase().contains("mono");
                        int weight = inferWeightFromName(path);
                        boolean italic = path != null && (path.toLowerCase().contains("italic") || path.toLowerCase().contains("oblique"));
                        
                        param.setResult(getSafeSystemTypeface(isMono, weight, italic));
                    }
                });

        // HOOK 5: File Creation
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "createFromFile", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        String path = (String) param.args[0];
                        
                        boolean isMono = path != null && path.toLowerCase().contains("mono");
                        int weight = inferWeightFromName(path);
                        boolean italic = path != null && (path.toLowerCase().contains("italic") || path.toLowerCase().contains("oblique"));
                        
                        param.setResult(getSafeSystemTypeface(isMono, weight, italic));
                    }
                });

        // NOTE: Paint.setTypeface and TextView.setTypeface(Typeface) hooks have been permanently 
        // deleted here to completely stop the UI lag and restore app opening speeds.

        // HOOK 6: Dynamic TextView Styling (The span catcher)
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
                        
                        boolean isMono = checkIsMono(baseTf);
                        int style = (int) param.args[1];
                        int baseWeight = extractWeight(baseTf);
                        boolean baseItalic = extractItalic(baseTf);

                        boolean requestedItalic = (style & Typeface.ITALIC) != 0;
                        boolean requestedBold = (style & Typeface.BOLD) != 0;

                        int finalWeight = requestedBold ? Math.max(baseWeight, 700) : baseWeight;
                        boolean finalItalic = baseItalic || requestedItalic;

                        param.args[0] = getSafeSystemTypeface(isMono, finalWeight, finalItalic);
                        param.args[1] = finalItalic ? Typeface.ITALIC : Typeface.NORMAL;
                    }
                });
    }
}

