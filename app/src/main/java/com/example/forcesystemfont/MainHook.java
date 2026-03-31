package com.example.forcesystemfont;

import android.graphics.Typeface;
import android.os.Build;
import android.widget.TextView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    // Cached fonts (performance)
    private static Typeface SANS, SANS_BOLD, SANS_ITALIC, SANS_BOLD_ITALIC;
    private static Typeface SANS_LIGHT, SANS_MEDIUM, SANS_BLACK, SANS_THIN;

    private void initFonts() {
        if (SANS != null) return;

        SANS = Typeface.create("sans-serif", Typeface.NORMAL);
        SANS_BOLD = Typeface.create("sans-serif-bold", Typeface.NORMAL);
        SANS_ITALIC = Typeface.create("sans-serif", Typeface.ITALIC);
        SANS_BOLD_ITALIC = Typeface.create("sans-serif", Typeface.BOLD_ITALIC);

        SANS_LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
        SANS_MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        SANS_BLACK = Typeface.create("sans-serif-black", Typeface.NORMAL);
        SANS_THIN = Typeface.create("sans-serif-thin", Typeface.NORMAL);
    }

    private void log(String msg) {
        XposedBridge.log("[ForceSystemFont] " + msg);
    }

    private Typeface mapTypeface(Typeface tf) {
        if (tf == null) return null;

        int weight = 400;
        boolean italic = false;

        if (Build.VERSION.SDK_INT >= 28) {
            try {
                weight = tf.getWeight();
                italic = tf.isItalic();
            } catch (Throwable ignored) {}
        }

        if (weight <= 200) return italic ? SANS_ITALIC : SANS_THIN;
        if (weight <= 350) return italic ? SANS_ITALIC : SANS_LIGHT;
        if (weight <= 450) return italic ? SANS_ITALIC : SANS;
        if (weight <= 650) return italic ? SANS_ITALIC : SANS_MEDIUM;
        if (weight <= 800) return italic ? SANS_BOLD_ITALIC : SANS_BOLD;

        return italic ? SANS_ITALIC : SANS_BLACK;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {

        initFonts();
        log("Loaded in: " + lpparam.packageName);

        // =========================
        // 1. Paint.setTypeface
        // =========================
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

                        Typeface mapped = mapTypeface(tf);
                        if (mapped != null) {
                            param.args[0] = mapped;
                            log("Paint.setTypeface replaced");
                        }
                    }
                }
        );

        // =========================
        // 2. Typeface.create
        // =========================
        try {
            XposedHelpers.findAndHookMethod(
                    Typeface.class,
                    "create",
                    Typeface.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Typeface result = (Typeface) param.getResult();
                            param.setResult(mapTypeface(result));
                            log("Typeface.create intercepted");
                        }
                    }
            );
        } catch (Throwable t) {
            log("create hook failed");
        }

        // =========================
        // 3. createFromAsset
        // =========================
        try {
            XposedHelpers.findAndHookMethod(
                    Typeface.class,
                    "createFromAsset",
                    android.content.res.AssetManager.class,
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(SANS);
                            log("createFromAsset blocked");
                        }
                    }
            );
        } catch (Throwable t) {
            log("createFromAsset hook failed");
        }

        // =========================
        // 4. createFromFile
        // =========================
        try {
            XposedHelpers.findAndHookMethod(
                    Typeface.class,
                    "createFromFile",
                    String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(SANS);
                            log("createFromFile blocked");
                        }
                    }
            );
        } catch (Throwable t) {
            log("createFromFile hook failed");
        }

        // =========================
        // 5. Typeface.Builder
        // =========================
        try {
            XposedHelpers.findAndHookMethod(
                    "android.graphics.Typeface$Builder",
                    lpparam.classLoader,
                    "build",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            param.setResult(SANS);
                            log("Typeface.Builder blocked");
                        }
                    }
            );
        } catch (Throwable t) {
            log("Builder hook failed");
        }

        // =========================
        // 6. TextView fallback
        // =========================
        try {
            XposedHelpers.findAndHookMethod(
                    TextView.class,
                    "setTypeface",
                    Typeface.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.args[0] = SANS;
                            log("TextView forced");
                        }
                    }
            );
        } catch (Throwable t) {
            log("TextView hook failed");
        }

        // =========================
        // 7. Resources.getFont (res/font FIX 🔥)
        // =========================
        try {
            XposedHelpers.findAndHookMethod(
                    "android.content.res.Resources",
                    lpparam.classLoader,
                    "getFont",
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Typeface original = (Typeface) param.getResult();
                            param.setResult(mapTypeface(original));
                            log("Resources.getFont intercepted");
                        }
                    }
            );
        } catch (Throwable t) {
            log("Resources.getFont hook failed");
        }

        // =========================
        // 8. ResourcesCompat.getFont (Jetpack)
        // =========================
        try {
            XposedHelpers.findAndHookMethod(
                    "androidx.core.content.res.ResourcesCompat",
                    lpparam.classLoader,
                    "getFont",
                    android.content.Context.class,
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Typeface original = (Typeface) param.getResult();
                            param.setResult(mapTypeface(original));
                            log("ResourcesCompat.getFont intercepted");
                        }
                    }
            );
        } catch (Throwable t) {
            log("ResourcesCompat hook failed (app may not use it)");
        }
    }
}
