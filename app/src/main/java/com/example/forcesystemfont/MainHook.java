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

    // Cache to avoid recreating typefaces
    private static final Map<String, Typeface> cache = new HashMap<>();

    private static Typeface getSystemTypeface(int weight, boolean italic) {
        String key = weight + "_" + italic;

        if (cache.containsKey(key)) {
            return cache.get(key);
        }

        String family;

        // Map weight → closest system font
        if (weight <= 200) {
            family = "sans-serif-thin";
        } else if (weight <= 300) {
            family = "sans-serif-light";
        } else if (weight <= 400) {
            family = "sans-serif";
        } else if (weight <= 500) {
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

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {

        // 🔥 Core hook (MAIN LOGIC)
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
                }
        );

        // ✅ Safe fallback: TextView.setTypeface
        XposedHelpers.findAndHookMethod(
                "android.widget.TextView",
                lpparam.classLoader,
                "setTypeface",
                Typeface.class,
                int.class,
                new XC_MethodHook() {

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {

                        int style = (int) param.args[1];

                        boolean italic = (style & Typeface.ITALIC) != 0;
                        int weight = (style & Typeface.BOLD) != 0 ? 700 : 400;

                        param.args[0] = getSystemTypeface(weight, italic);
                    }
                }
        );
    }
}
