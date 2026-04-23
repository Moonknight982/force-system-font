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

    private static Typeface getSystemTypeface(String familyHint, int weight, boolean italic) {
        String baseFamily = "sans-serif";
        Typeface baseTypeface = Typeface.DEFAULT;

        if (familyHint != null) {
            String lowerHint = familyHint.toLowerCase();
            if (lowerHint.contains("mono")) {
                baseFamily = "monospace";
                baseTypeface = Typeface.MONOSPACE;
            } else if (lowerHint.contains("serif") && !lowerHint.contains("sans")) {
                baseFamily = "serif";
                baseTypeface = Typeface.SERIF;
            }
        }

        String key = baseFamily + "_" + weight + "_" + italic;
        if (cache.containsKey(key)) return cache.get(key);

        Typeface tf;
        inHook.set(true);
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                tf = Typeface.create(baseTypeface, weight, italic);
            } else {
                String targetFamily = baseFamily;
                int style = italic ? Typeface.ITALIC : Typeface.NORMAL;

                if (baseFamily.equals("sans-serif")) {
                    if (weight <= 200)      { targetFamily = "sans-serif-thin"; }
                    else if (weight <= 300) { targetFamily = "sans-serif-light"; }
                    else if (weight <= 550) { targetFamily = "sans-serif-medium"; }
                    else if (weight >= 600) {
                        style = italic ? Typeface.BOLD_ITALIC : Typeface.BOLD;
                    }
                } else {
                    if (weight >= 600) {
                        style = italic ? Typeface.BOLD_ITALIC : Typeface.BOLD;
                    }
                }
                tf = Typeface.create(targetFamily, style);
            }
        } finally {
            inHook.set(false);
        }

        cache.put(key, tf);
        return tf;
    }

    private static int inferWeightFromName(String name) {
        if (name == null) return 400;
        String l = name.toLowerCase();
        if (l.contains("black") || l.contains("extrabold") || l.contains("heavy")) return 900;
        if (l.contains("semibold") || l.contains("demibold") || l.contains("demi")) return 600;
        if (l.contains("bold"))   return 700;
        if (l.contains("medium")) return 500;
        if (l.contains("light"))  return 300;
        if (l.contains("thin") || l.contains("extralight") || l.contains("hairline")) return 100;
        return 400;
    }

    private static int extractWeight(Typeface tf) {
        if (tf == null) return 400;
        try {
            if (Build.VERSION.SDK_INT >= 28) return tf.getWeight();
            return (tf.getStyle() & Typeface.BOLD) != 0 ? 700 : 400;
        } catch (Throwable ignored) {
            return 400;
        }
    }

    private static boolean extractItalic(Typeface tf) {
        if (tf == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 28) return tf.isItalic();
            return (tf.getStyle() & Typeface.ITALIC) != 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int weightFromStyle(int style) {
        return (style & Typeface.BOLD) != 0 ? 700 : 400;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {

        // HOOK 1: Typeface.create(String family, int style)
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "create", String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        String family = (String) param.args[0];
                        int style = (int) param.args[1];
                        
                        boolean italic = (style & Typeface.ITALIC) != 0;
                        int weight = weightFromStyle(style);

                        if (family != null) {
                            String lFamily = family.toLowerCase();
                            if (lFamily.contains("medium"))  weight = 500;
                            else if (lFamily.contains("light")) weight = 300;
                            else if (lFamily.contains("thin"))  weight = 100;
                            else if (lFamily.contains("black")) weight = 900;
                        }

                        param.setResult(getSystemTypeface(family, weight, italic));
                    }
                });

        // HOOK 2: Typeface.create(Typeface family, int style)
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "create", Typeface.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        Typeface baseTf = (Typeface) param.args[0];
                        int style = (int) param.args[1];

                        int baseWeight = extractWeight(baseTf);
                        boolean baseItalic = extractItalic(baseTf);
                        
                        boolean requestedItalic = (style & Typeface.ITALIC) != 0;
                        boolean requestedBold = (style & Typeface.BOLD) != 0;

                        int finalWeight = requestedBold ? Math.max(baseWeight, 700) : baseWeight;
                        boolean finalItalic = baseItalic || requestedItalic;

                        // Safe identity check without heavy string operations
                        String familyHint = null;
                        if (baseTf != null) {
                            if (baseTf.equals(Typeface.MONOSPACE)) familyHint = "mono";
                            else if (baseTf.equals(Typeface.SERIF)) familyHint = "serif";
                        }

                        param.setResult(getSystemTypeface(familyHint, finalWeight, finalItalic));
                    }
                });

        // HOOK 3: Typeface.create(Typeface family, int weight, boolean italic) [API 28+]
        if (Build.VERSION.SDK_INT >= 28) {
            XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                    "create", Typeface.class, int.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (inHook.get()) return;
                            Typeface baseTf = (Typeface) param.args[0];
                            int weight = (int) param.args[1];
                            boolean italic = (boolean) param.args[2];
                            
                            String familyHint = null;
                            if (baseTf != null) {
                                if (baseTf.equals(Typeface.MONOSPACE)) familyHint = "mono";
                                else if (baseTf.equals(Typeface.SERIF)) familyHint = "serif";
                            }
                            
                            param.setResult(getSystemTypeface(familyHint, weight, italic));
                        }
                    });
        }

        // HOOK 4: Typeface.createFromAsset(AssetManager, String path)
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "createFromAsset",
                android.content.res.AssetManager.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        String path = (String) param.args[1];
                        int weight = inferWeightFromName(path);
                        boolean italic = path != null && (path.toLowerCase().contains("italic") || path.toLowerCase().contains("oblique"));
                        param.setResult(getSystemTypeface(path, weight, italic));
                    }
                });

        // HOOK 5: Typeface.createFromFile(String path)
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "createFromFile", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        String path = (String) param.args[0];
                        int weight = inferWeightFromName(path);
                        boolean italic = path != null && (path.toLowerCase().contains("italic") || path.toLowerCase().contains("oblique"));
                        param.setResult(getSystemTypeface(path, weight, italic));
                    }
                });

        // HOOK 6: Paint.setTypeface(Typeface)
        XposedHelpers.findAndHookMethod("android.graphics.Paint", lpparam.classLoader,
                "setTypeface", Typeface.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        Typeface tf = (Typeface) param.args[0];
                        if (tf == null) return;

                        param.args[0] = getSystemTypeface(null, extractWeight(tf), extractItalic(tf));
                    }
                });

        // HOOK 7: TextView.setTypeface(Typeface)
        XposedHelpers.findAndHookMethod("android.widget.TextView", lpparam.classLoader,
                "setTypeface", Typeface.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        Typeface tf = (Typeface) param.args[0];
                        if (tf == null) return;

                        param.args[0] = getSystemTypeface(null, extractWeight(tf), extractItalic(tf));
                    }
                });

        // HOOK 8: TextView.setTypeface(Typeface, int style)
        XposedHelpers.findAndHookMethod("android.widget.TextView", lpparam.classLoader,
                "setTypeface", Typeface.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        int style = (int) param.args[1];
                        Typeface baseTf = (Typeface) param.args[0];

                        // Safe grab for existing text styles (prevents wiping)
                        if (baseTf == null) {
                            TextView tv = (TextView) param.thisObject;
                            baseTf = tv.getTypeface();
                        }

                        int baseWeight = extractWeight(baseTf);
                        boolean baseItalic = extractItalic(baseTf);

                        boolean requestedItalic = (style & Typeface.ITALIC) != 0;
                        boolean requestedBold = (style & Typeface.BOLD) != 0;

                        int finalWeight = requestedBold ? Math.max(baseWeight, 700) : baseWeight;
                        boolean finalItalic = baseItalic || requestedItalic;

                        param.args[0] = getSystemTypeface(null, finalWeight, finalItalic);
                        param.args[1] = finalItalic ? Typeface.ITALIC : Typeface.NORMAL;
                    }
                });
    }
}
