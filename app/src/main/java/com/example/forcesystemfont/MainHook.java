package com.example.forcesystemfont;

import android.graphics.Typeface;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String[] ICON_FONT_PATTERNS = {
        "material", "icon", "awesome", "ionicon",
        "symbol", "glyph", "weather", "feather"
    };

    private boolean isIconFont(Typeface tf) {
        // Can't check name at this point so we skip null typefaces only
        return tf == null;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        XposedBridge.log("ForceSystemFont: hooked " + lpparam.packageName);

        XposedHelpers.findAndHookMethod(
            "android.graphics.Paint",
            lpparam.classLoader,
            "setTypeface",
            Typeface.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Typeface tf = (Typeface) param.args[0];
                    if (tf != null && tf != Typeface.DEFAULT
                            && tf != Typeface.DEFAULT_BOLD
                            && tf != Typeface.MONOSPACE
                            && tf != Typeface.SANS_SERIF
                            && tf != Typeface.SERIF) {
                        param.args[0] = Typeface.DEFAULT;
                    }
                }
            }
        );
    }
 }
