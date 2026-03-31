package com.example.forcesystemfont;

import android.graphics.Typeface;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {

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

                    // Get numeric weight (API 28+) and italic
                    int weight = tf.getWeight();  // 100-900
                    boolean italic = tf.isItalic();

                    // Map weight to system Typeface
                    // Roboto supports: 100,300,400,500,700,900
                    if (weight <= 150) {
                        // Thin
                        param.args[0] = Typeface.create("sans-serif-thin", italic ? Typeface.ITALIC : Typeface.NORMAL);
                    } else if (weight <= 250) {
                        // Extra Light
                        param.args[0] = Typeface.create("sans-serif-thin", italic ? Typeface.ITALIC : Typeface.NORMAL);
                    } else if (weight <= 350) {
                        // Light
                        param.args[0] = Typeface.create("sans-serif-light", italic ? Typeface.ITALIC : Typeface.NORMAL);
                    } else if (weight <= 450) {
                        // Regular
                        param.args[0] = Typeface.create("sans-serif", italic ? Typeface.ITALIC : Typeface.NORMAL);
                    } else if (weight <= 550) {
                        // Medium
                        param.args[0] = Typeface.create("sans-serif-medium", italic ? Typeface.ITALIC : Typeface.NORMAL);
                    } else if (weight <= 650) {
                        // SemiBold — Roboto has no semibold, medium is closest
                        param.args[0] = Typeface.create("sans-serif-medium", italic ? Typeface.ITALIC : Typeface.NORMAL);
                    } else if (weight <= 750) {
                        // Bold
                        param.args[0] = Typeface.create("sans-serif", italic ? Typeface.BOLD_ITALIC : Typeface.BOLD);
                    } else {
                        // ExtraBold / Black
                        param.args[0] = Typeface.create("sans-serif-black", italic ? Typeface.ITALIC : Typeface.NORMAL);
                    }
                }
            }
        );
    }
 }
