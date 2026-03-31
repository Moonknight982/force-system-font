package com.example.forcesystemfont;

import android.content.res.AssetManager;
import android.graphics.Typeface;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private Typeface mapWeight(int weight, boolean italic) {
        int style = italic ? Typeface.ITALIC : Typeface.NORMAL;
        if (weight <= 150) return Typeface.create("sans-serif-thin", style);
        if (weight <= 350) return Typeface.create("sans-serif-light", style);
        if (weight <= 450) return Typeface.create("sans-serif", style);
        if (weight <= 600) return Typeface.create("sans-serif-medium", style);
        if (weight <= 750) return Typeface.create("sans-serif", italic ? Typeface.BOLD_ITALIC : Typeface.BOLD);
        return Typeface.create("sans-serif-black", style);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {

        // Hook 1: Paint.setTypeface — catches most native views
        XposedHelpers.findAndHookMethod(
            "android.graphics.Paint",
            lpparam.classLoader,
            "setTypeface",
            Typeface.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Typeface tf = (Typeface) param.args[0];
                    if (tf == null) return;
                    param.args[0] = mapWeight(tf.getWeight(), tf.isItalic());
                }
            }
        );

        // Hook 2: Typeface.createFromAsset — catches assets/fonts/
        XposedHelpers.findAndHookMethod(
            "android.graphics.Typeface",
            lpparam.classLoader,
            "createFromAsset",
            AssetManager.class,
            String.class,
            new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    return Typeface.DEFAULT;
                }
            }
        );

        // Hook 3: Typeface.create(String, int) — catches named font families
        XposedHelpers.findAndHookMethod(
            "android.graphics.Typeface",
            lpparam.classLoader,
            "create",
            String.class,
            int.class,
            new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    int style = (int) param.args[1];
                    // Allow system font families through
                    String family = (String) param.args[0];
                    if (family == null
                        || family.equals("sans-serif")
                        || family.equals("sans-serif-thin")
                        || family.equals("sans-serif-light")
                        || family.equals("sans-serif-medium")
                        || family.equals("sans-serif-black")
                        || family.equals("serif")
                        || family.equals("monospace")) {
                        return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                    }
                    return Typeface.defaultFromStyle(style);
                }
            }
        );

        // Hook 4: Typeface.create(Typeface, int) — catches style variants of custom fonts
        XposedHelpers.findAndHookMethod(
            "android.graphics.Typeface",
            lpparam.classLoader,
            "create",
            Typeface.class,
            int.class,
            new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    int style = (int) param.args[1];
                    return Typeface.defaultFromStyle(style);
                }
            }
        );

        // Hook 5: Typeface.create(Typeface, int, boolean) — API 28+ weight+italic variant
        try {
            XposedHelpers.findAndHookMethod(
                "android.graphics.Typeface",
                lpparam.classLoader,
                "create",
                Typeface.class,
                int.class,
                boolean.class,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        int weight = (int) param.args[1];
                        boolean italic = (boolean) param.args[2];
                        return mapWeight(weight, italic);
                    }
                }
            );
        } catch (Throwable ignored) {}

    }
}
