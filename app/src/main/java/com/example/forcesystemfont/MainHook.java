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

    private static final String[] SYSTEM_FAMILIES = {
        "sans-serif", "sans-serif-thin", "sans-serif-light",
        "sans-serif-medium", "sans-serif-black", "serif",
        "monospace", "serif-monospace", "cursive", "casual"
    };

    private boolean isSystemFamily(String family) {
        if (family == null) return true;
        for (String s : SYSTEM_FAMILIES) {
            if (s.equals(family)) return true;
        }
        return false;
    }

    private boolean isMonospace(Typeface tf) {
        try {
            Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL);
            return tf.equals(mono);
        } catch (Throwable t) {
            return false;
        }
    }

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

        // Hook 1: Paint.setTypeface
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
                    if (isMonospace(tf)) return;
                    param.args[0] = mapWeight(tf.getWeight(), tf.isItalic());
                }
            }
        );

        // Hook 2: createFromAsset
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

        // Hook 3: createFromFile
        XposedHelpers.findAndHookMethod(
            "android.graphics.Typeface",
            lpparam.classLoader,
            "createFromFile",
            String.class,
            new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    return Typeface.DEFAULT;
                }
            }
        );

        // Hook 4: Typeface.create(String, int)
        XposedHelpers.findAndHookMethod(
            "android.graphics.Typeface",
            lpparam.classLoader,
            "create",
            String.class,
            int.class,
            new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    String family = (String) param.args[0];
                    int style = (int) param.args[1];
                    if (isSystemFamily(family)) {
                        return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                    }
                    return Typeface.defaultFromStyle(style);
                }
            }
        );

        // Hook 5: Typeface.create(Typeface, int)
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

        // Hook 6: Typeface.create(Typeface, int, boolean) API 28+
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

        // Hook 7: ResourcesCompat.getFont() — res/font/ AndroidX
        try {
            XposedHelpers.findAndHookMethod(
                "androidx.core.content.res.ResourcesCompat",
                lpparam.classLoader,
                "getFont",
                android.content.Context.class,
                int.class,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return Typeface.DEFAULT;
                    }
                }
            );
        } catch (Throwable ignored) {}

        // Hook 8: ResourcesImpl.loadFont() — res/font/ system level
        try {
            XposedHelpers.findAndHookMethod(
                "android.content.res.ResourcesImpl",
                lpparam.classLoader,
                "loadFont",
                android.content.Context.class,
                android.util.TypedValue.class,
                int.class,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return Typeface.DEFAULT;
                    }
                }
            );
        } catch (Throwable ignored) {}

        // Hook 9: createFromStream() — raw resource streams
        try {
            XposedHelpers.findAndHookMethod(
                "android.graphics.Typeface",
                lpparam.classLoader,
                "createFromStream",
                java.io.InputStream.class,
                String.class,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return Typeface.DEFAULT;
                    }
                }
            );
        } catch (Throwable ignored) {}

        // Hook 10: createFromFileDescriptor() — file descriptor fonts
        try {
            XposedHelpers.findAndHookMethod(
                "android.graphics.Typeface",
                lpparam.classLoader,
                "createFromFileDescriptor",
                android.os.ParcelFileDescriptor.class,
                new XC_MethodReplacement() {
                    @Override
                    protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                        return Typeface.DEFAULT;
                    }
                }
            );
        } catch (Throwable ignored) {}
    }
}
