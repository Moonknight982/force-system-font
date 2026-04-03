package com.example.forcesystemfont;

import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Build;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final Map<String, Typeface> cache = new HashMap<>();

    private static Typeface getSystemTypeface(int weight, boolean italic) {
        String key = weight + "_" + italic;

        if (cache.containsKey(key)) {
            return cache.get(key);
        }

        String family;

        // Improved mapping
        if (weight <= 200) {
            family = "sans-serif-thin";
        } else if (weight <= 300) {
            family = "sans-serif-light";
        } else if (weight <= 400) {
            family = "sans-serif";
        } else if (weight <= 500) {
            family = "sans-serif-medium";
        } else if (weight <= 600) {
            family = "sans-serif-medium";
        } else if (weight <= 700) {
            family = "sans-serif";
        } else {
            family = "sans-serif-black";
        }

        int style;
        if (weight >= 700) {
            style = italic ? Typeface.BOLD_ITALIC : Typeface.BOLD;
        } else {
            style = italic ? Typeface.ITALIC : Typeface.NORMAL;
        }

        Typeface tf = Typeface.create(family, style);
        cache.put(key, tf);
        return tf;
    }

    private static Typeface resolveReplacement(Typeface original) {
        if (original == null) return null;

        int weight = 400;
        boolean italic = false;

        try {
            if (Build.VERSION.SDK_INT >= 28) {
                weight = original.getWeight();
                italic = original.isItalic();
            } else {
                int style = original.getStyle();
                italic = (style & Typeface.ITALIC) != 0;
                weight = (style & Typeface.BOLD) != 0 ? 700 : 400;
            }
        } catch (Throwable ignored) {}

        return getSystemTypeface(weight, italic);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {

        // 🔥 1. Hook Paint (core rendering layer)
        XposedHelpers.findAndHookMethod(
                "android.graphics.Paint",
                lpparam.classLoader,
                "setTypeface",
                Typeface.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Typeface tf = (Typeface) param.args[0];
                        if (tf == null) return;

                        param.args[0] = resolveReplacement(tf);
                    }
                }
        );

        // 🔥 2. Hook TextView (UI fallback)
        XposedHelpers.findAndHookMethod(
                "android.widget.TextView",
                lpparam.classLoader,
                "setTypeface",
                Typeface.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Typeface tf = (Typeface) param.args[0];
                        if (tf == null) return;

                        param.args[0] = resolveReplacement(tf);
                    }
                }
        );

        // 🔥 3. Hook Typeface.create (VERY IMPORTANT)
        XposedHelpers.findAndHookMethod(
                "android.graphics.Typeface",
                lpparam.classLoader,
                "create",
                Typeface.class,
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Typeface result = (Typeface) param.getResult();
                        if (result == null) return;

                        param.setResult(resolveReplacement(result));
                    }
                }
        );

        // 🔥 4. Hook createFromAsset
        XposedHelpers.findAndHookMethod(
                "android.graphics.Typeface",
                lpparam.classLoader,
                "createFromAsset",
                android.content.res.AssetManager.class,
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Typeface result = (Typeface) param.getResult();
                        if (result == null) return;

                        param.setResult(resolveReplacement(result));
                    }
                }
        );

        // 🔥 5. Hook createFromFile
        XposedHelpers.findAndHookMethod(
                "android.graphics.Typeface",
                lpparam.classLoader,
                "createFromFile",
                String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Typeface result = (Typeface) param.getResult();
                        if (result == null) return;

                        param.setResult(resolveReplacement(result));
                    }
                }
        );

        // 🔥 6. Hook Resources.getFont (XML fonts)
        XposedHelpers.findAndHookMethod(
                Resources.class,
                "getFont",
                int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Typeface result = (Typeface) param.getResult();
                        if (result == null) return;

                        param.setResult(resolveReplacement(result));
                    }
                }
        );

        // 🔥 7. Hook Typeface.Builder (modern apps like Instagram)
        try {
            XposedHelpers.findAndHookMethod(
                    "android.graphics.Typeface$Builder",
                    lpparam.classLoader,
                    "build",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Typeface result = (Typeface) param.getResult();
                            if (result == null) return;

                            param.setResult(resolveReplacement(result));
                        }
                    }
            );
        } catch (Throwable ignored) {}

    }
}
