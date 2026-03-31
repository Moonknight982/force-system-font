package com.example.forcesystemfont;

import android.graphics.Typeface;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String[] ICON_FONT_PATTERNS = {
        "material", "icon", "awesome", "ionicon",
        "symbol", "glyph", "weather", "feather"
    };

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

        XposedHelpers.findAndHookMethod(
            "android.graphics.Typeface",
            ClassLoader.getSystemClassLoader(),
            "createFromAsset",
            android.content.res.AssetManager.class,
            String.class,
            new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    String fontName = (String) param.args[1];
                    if (isIconFont(fontName)) {
                        return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                    }
                    return Typeface.DEFAULT;
                }
            }
        );

        XposedHelpers.findAndHookMethod(
            "android.graphics.Typeface",
            ClassLoader.getSystemClassLoader(),
            "createFromFile",
            String.class,
            new XC_MethodReplacement() {
                @Override
                protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                    String path = (String) param.args[0];
                    if (isIconFont(path)) {
                        return XposedBridge.invokeOriginalMethod(param.method, param.thisObject, param.args);
                    }
                    return Typeface.DEFAULT;
                }
            }
        );
    }
}
