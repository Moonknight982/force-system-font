package com.example.forcesystemfont;

import android.graphics.Typeface;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    // Add package names of apps you want to force system font
    private static final String[] TARGET_PACKAGES = {
        "com.example.targetapp1",
        "com.example.targetapp2"
    };

    // Known icon font file name patterns — skip these
    private static final String[] ICON_FONT_PATTERNS = {
        "material", "icon", "awesome", "ionicon",
        "symbol", "glyph", "weather", "feather"
    };

    private boolean isTargetPackage(String packageName) {
        for (String pkg : TARGET_PACKAGES) {
            if (pkg.equals(packageName)) return true;
        }
        return false;
    }

    private boolean isIconFont(String path) {
        if (path == null) return false;
        String lower = path.toLowerCase();
        for (String pattern : ICON_FONT_PATTERNS) {
            if (lower.contains(pattern)) return true;
        }
        return false;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!isTargetPackage(lpparam.packageName)) return;

        // Hook createFromAsset
        XposedHelpers.findAndHookMethod(
            "android.graphics.Typeface",
            ClassLoader.getSystemClassLoader(),
            "createFromAsset",
            android.content.res.AssetManager.class,
            String.class,
            new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    String fontName = (String) param.args[1];
                    if (isIconFont(fontName)) {
                        return XC_MethodReplacement.invokeOriginalMethod(param.method, param.thisObject, param.args);
                    }
                    return Typeface.DEFAULT;
                }
            }
        );

        // Hook createFromFile
        XposedHelpers.findAndHookMethod(
            "android.graphics.Typeface",
            ClassLoader.getSystemClassLoader(),
            "createFromFile",
            String.class,
            new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) {
                    String path = (String) param.args[0];
                    if (isIconFont(path)) {
                        return XC_MethodReplacement.invokeOriginalMethod(param.method, param.thisObject, param.args);
                    }
                    return Typeface.DEFAULT;
                }
            }
        );
    }
}
