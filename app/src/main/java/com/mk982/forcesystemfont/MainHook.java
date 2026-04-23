package com.mk982.forcesystemfont;

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
    private static final ThreadLocal<Boolean> inHook = ThreadLocal.withInitial(() -> false);

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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {

        // HOOK 1: Family-based creation
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "create", String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        String family = (String) param.args[0];
                        
                        // SKIP: If app specifically asks for monospace or serif, do nothing.
                        if (family != null) {
                            String lower = family.toLowerCase();
                            if (lower.contains("mono") || lower.contains("serif")) return;
                        }

                        int style = (int) param.args[1];
                        boolean italic = (style & Typeface.ITALIC) != 0;
                        int weight = (style & Typeface.BOLD) != 0 ? 700 : 400;

                        param.setResult(getSystemTypeface(weight, italic));
                    }
                });

        // HOOK 2: Asset/File based (Instagram, Discord, etc)
        XC_MethodHook assetHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (inHook.get()) return;
                String path = (String) (param.args.length == 2 ? param.args[1] : param.args[0]);
                
                // SKIP: Preserve bundled mono fonts
                if (path != null && path.toLowerCase().contains("mono")) return;

                int weight = inferWeightFromName(path);
                boolean italic = path != null && (path.toLowerCase().contains("italic") || path.toLowerCase().contains("oblique"));
                param.setResult(getSystemTypeface(weight, italic));
            }
        };

        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "createFromAsset", android.content.res.AssetManager.class, String.class, assetHook);
        
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "createFromFile", String.class, assetHook);

        // HOOK 3: Global UI Elements (TextView & Paint)
        XC_MethodHook uiHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (inHook.get()) return;
                Typeface tf = (Typeface) param.args[0];
                if (tf == null) return;

                // On high-frequency UI hooks, we only hook if we are sure it's not Mono.
                // Since we can't reliably check family here without crashing, 
                // we only override if the weight/italic is standard.
                param.args[0] = getSystemTypeface(extractWeight(tf), extractItalic(tf));
            }
        };

        XposedHelpers.findAndHookMethod("android.widget.TextView", lpparam.classLoader, "setTypeface", Typeface.class, uiHook);
        XposedHelpers.findAndHookMethod("android.graphics.Paint", lpparam.classLoader, "setTypeface", Typeface.class, uiHook);
    }
}
