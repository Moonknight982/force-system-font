package com.mk982.forcesystemfont;

import android.graphics.Typeface;
import android.os.Build;
import android.widget.TextView;

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
                // Directly create with specific weight on modern Android
                tf = Typeface.create(Typeface.DEFAULT, weight, italic);
            } else {
                // Legacy mapping for older Android versions
                String family = "sans-serif";
                int style = italic ? Typeface.ITALIC : Typeface.NORMAL;
                if (weight <= 200) family = "sans-serif-thin";
                else if (weight <= 300) family = "sans-serif-light";
                else if (weight <= 550) family = "sans-serif-medium";
                else if (weight >= 600) style = italic ? Typeface.BOLD_ITALIC : Typeface.BOLD;
                tf = Typeface.create(family, style);
            }
        } finally {
            inHook.set(false);
        }

        cache.put(key, tf);
        return tf;
    }

    private static int extractWeight(Typeface tf) {
        if (tf == null) return 400;
        if (Build.VERSION.SDK_INT >= 28) return tf.getWeight();
        return (tf.getStyle() & Typeface.BOLD) != 0 ? 700 : 400;
    }

    private static boolean extractItalic(Typeface tf) {
        if (tf == null) return false;
        if (Build.VERSION.SDK_INT >= 28) return tf.isItalic();
        return (tf.getStyle() & Typeface.ITALIC) != 0;
    }

    private static int inferWeight(String path) {
        if (path == null) return 400;
        String l = path.toLowerCase();
        if (l.contains("black") || l.contains("heavy")) return 900;
        if (l.contains("extrabold")) return 800;
        if (l.contains("bold")) return 700;
        if (l.contains("semibold") || l.contains("demi")) return 600;
        if (l.contains("medium")) return 500;
        if (l.contains("light")) return 300;
        if (l.contains("thin") || l.contains("hairline")) return 100;
        return 400;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {

        // --- 1. THE MODERN BUILDER HOOK (Catches Medium weight custom fonts) ---
        XposedHelpers.findAndHookMethod("android.graphics.Typeface$Builder", lpparam.classLoader,
                "build", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Typeface original = (Typeface) param.getResult();
                        if (original == null || inHook.get()) return;
                        param.setResult(getSystemTypeface(extractWeight(original), extractItalic(original)));
                    }
                });

        // --- 2. TYPEFACE.CREATE OVERLOADS (API 34+ Support) ---
        XC_MethodHook createHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (inHook.get()) return;
                int weight = 400;
                boolean italic = false;

                if (param.args[1] instanceof Integer && param.args[2] instanceof Boolean) {
                    weight = (int) param.args[1];
                    italic = (boolean) param.args[2];
                }
                param.setResult(getSystemTypeface(weight, italic));
            }
        };

        if (Build.VERSION.SDK_INT >= 28) {
            XposedHelpers.findAndHookMethod(Typeface.class, "create", Typeface.class, int.class, boolean.class, createHook);
        }
        if (Build.VERSION.SDK_INT >= 34) {
            XposedHelpers.findAndHookMethod(Typeface.class, "create", String.class, int.class, boolean.class, createHook);
        }

        // --- 3. LEGACY CREATE HOOKS ---
        XposedHelpers.findAndHookMethod(Typeface.class, "create", String.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (inHook.get()) return;
                String family = (String) param.args[0];
                int style = (int) param.args[1];
                int weight = (style & Typeface.BOLD) != 0 ? 700 : inferWeight(family);
                param.setResult(getSystemTypeface(weight, (style & Typeface.ITALIC) != 0));
            }
        });

        // --- 4. FILE & ASSET HOOKS ---
        XC_MethodHook pathHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (inHook.get()) return;
                String path = (param.args.length > 1) ? (String) param.args[1] : (String) param.args[0];
                param.setResult(getSystemTypeface(inferWeight(path), path != null && path.toLowerCase().contains("italic")));
            }
        };
        XposedHelpers.findAndHookMethod(Typeface.class, "createFromAsset", android.content.res.AssetManager.class, String.class, pathHook);
        XposedHelpers.findAndHookMethod(Typeface.class, "createFromFile", String.class, pathHook);

        // --- 5. TEXTVIEW SAFETY NET ---
        XposedHelpers.findAndHookMethod(TextView.class, "setTypeface", Typeface.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (inHook.get()) return;
                TextView tv = (TextView) param.thisObject;
                Typeface tf = (Typeface) param.args[0];
                int style = (int) param.args[1];

                int weight = extractWeight(tf);
                if (Build.VERSION.SDK_INT >= 28 && tv.getTextFontWeight() != -1) {
                    weight = tv.getTextFontWeight(); // Respect XML-defined weights like 500
                }
                if ((style & Typeface.BOLD) != 0) weight = Math.max(weight, 700);

                param.args[0] = getSystemTypeface(weight, (style & Typeface.ITALIC) != 0 || extractItalic(tf));
                param.args[1] = extractItalic((Typeface) param.args[0]) ? Typeface.ITALIC : Typeface.NORMAL;
            }
        });
    }
}
