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

    // Store the system's "True" font identities to prevent accidental overrides
    private static Typeface trueMono = null;
    private static Typeface trueSerif = null;
    private static boolean identitiesCaptured = false;

    // Capture the True Identities safely on the first hook run
    private static void captureIdentitiesIfNeeded() {
        if (!identitiesCaptured) {
            try {
                trueMono = Typeface.create("monospace", Typeface.NORMAL);
                trueSerif = Typeface.create("serif", Typeface.NORMAL);
            } catch (Throwable ignored) {
                // Fallback to constants if create() fails
                trueMono = Typeface.MONOSPACE;
                trueSerif = Typeface.SERIF;
            }
            identitiesCaptured = true;
        }
    }

    // The research-backed identity check
    private static boolean isNonSans(Typeface tf) {
        if (tf == null) return false;
        if (tf.equals(Typeface.MONOSPACE) || tf.equals(Typeface.SERIF)) return true;
        if (trueMono != null && tf.equals(trueMono)) return true;
        if (trueSerif != null && tf.equals(trueSerif)) return true;
        return false;
    }

    private static Typeface getSystemTypeface(int weight, boolean italic) {
        String key = "sans_" + weight + "_" + italic;
        if (cache.containsKey(key)) return cache.get(key);

        Typeface tf;
        inHook.set(true);
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                tf = Typeface.create(Typeface.DEFAULT, weight, italic);
            } else {
                String targetFamily = "sans-serif";
                int style = italic ? Typeface.ITALIC : Typeface.NORMAL;

                if (weight <= 200)      { targetFamily = "sans-serif-thin"; }
                else if (weight <= 300) { targetFamily = "sans-serif-light"; }
                else if (weight <= 550) { targetFamily = "sans-serif-medium"; }
                else if (weight >= 600) {
                    style = italic ? Typeface.BOLD_ITALIC : Typeface.BOLD;
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
                        captureIdentitiesIfNeeded();
                        
                        String family = (String) param.args[0];
                        
                        // SKIP: If the app explicitly asks for Mono or Serif by name, step aside.
                        if (family != null) {
                            String lFamily = family.toLowerCase();
                            if (lFamily.contains("mono") || lFamily.contains("serif")) return;
                        }

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

                        param.setResult(getSystemTypeface(weight, italic));
                    }
                });

        // HOOK 2: Typeface.create(Typeface family, int style)
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "create", Typeface.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        captureIdentitiesIfNeeded();
                        
                        Typeface baseTf = (Typeface) param.args[0];
                        
                        // SKIP: If the base font is already Mono/Serif, leave it alone!
                        if (isNonSans(baseTf)) return;

                        int style = (int) param.args[1];
                        int baseWeight = extractWeight(baseTf);
                        boolean baseItalic = extractItalic(baseTf);
                        
                        boolean requestedItalic = (style & Typeface.ITALIC) != 0;
                        boolean requestedBold = (style & Typeface.BOLD) != 0;

                        int finalWeight = requestedBold ? Math.max(baseWeight, 700) : baseWeight;
                        boolean finalItalic = baseItalic || requestedItalic;

                        param.setResult(getSystemTypeface(finalWeight, finalItalic));
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
                            captureIdentitiesIfNeeded();
                            
                            Typeface baseTf = (Typeface) param.args[0];
                            
                            // SKIP: If base is Mono/Serif, do not overwrite it.
                            if (isNonSans(baseTf)) return;

                            int weight = (int) param.args[1];
                            boolean italic = (boolean) param.args[2];
                            
                            param.setResult(getSystemTypeface(weight, italic));
                        }
                    });
        }

        // HOOK 4: Typeface.createFromAsset
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "createFromAsset",
                android.content.res.AssetManager.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        captureIdentitiesIfNeeded();
                        
                        String path = (String) param.args[1];
                        
                        // SKIP: Preserve bundled mono fonts (like Discord's code block fonts)
                        if (path != null && path.toLowerCase().contains("mono")) return;

                        int weight = inferWeightFromName(path);
                        boolean italic = path != null && (path.toLowerCase().contains("italic") || path.toLowerCase().contains("oblique"));
                        param.setResult(getSystemTypeface(weight, italic));
                    }
                });

        // HOOK 5: Typeface.createFromFile
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "createFromFile", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        captureIdentitiesIfNeeded();
                        
                        String path = (String) param.args[0];
                        if (path != null && path.toLowerCase().contains("mono")) return;

                        int weight = inferWeightFromName(path);
                        boolean italic = path != null && (path.toLowerCase().contains("italic") || path.toLowerCase().contains("oblique"));
                        param.setResult(getSystemTypeface(weight, italic));
                    }
                });

        // HOOK 6 & 7 & 8: Paint and TextView hooks
        // We safely check the existing typeface identity before overriding it.
        
        XC_MethodHook uiHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (inHook.get()) return;
                captureIdentitiesIfNeeded();
                
                Typeface tf = (Typeface) param.args[0];
                if (tf == null || isNonSans(tf)) return; // SKIP Mono/Serif

                param.args[0] = getSystemTypeface(extractWeight(tf), extractItalic(tf));
            }
        };

        XposedHelpers.findAndHookMethod("android.graphics.Paint", lpparam.classLoader, "setTypeface", Typeface.class, uiHook);
        XposedHelpers.findAndHookMethod("android.widget.TextView", lpparam.classLoader, "setTypeface", Typeface.class, uiHook);

        XposedHelpers.findAndHookMethod("android.widget.TextView", lpparam.classLoader,
                "setTypeface", Typeface.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        captureIdentitiesIfNeeded();
                        
                        Typeface baseTf = (Typeface) param.args[0];
                        if (baseTf == null) {
                            TextView tv = (TextView) param.thisObject;
                            baseTf = tv.getTypeface();
                        }
                        
                        // SKIP if existing text is already Mono/Serif
                        if (isNonSans(baseTf)) return;

                        int style = (int) param.args[1];
                        int baseWeight = extractWeight(baseTf);
                        boolean baseItalic = extractItalic(baseTf);

                        boolean requestedItalic = (style & Typeface.ITALIC) != 0;
                        boolean requestedBold = (style & Typeface.BOLD) != 0;

                        int finalWeight = requestedBold ? Math.max(baseWeight, 700) : baseWeight;
                        boolean finalItalic = baseItalic || requestedItalic;

                        param.args[0] = getSystemTypeface(finalWeight, finalItalic);
                        param.args[1] = finalItalic ? Typeface.ITALIC : Typeface.NORMAL;
                    }
                });
    }
}

