package com.mk982.forcesystemfont;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final Map<String, Typeface> cache = new HashMap<>();
    private static final ThreadLocal<Boolean> inHook = ThreadLocal.withInitial(() -> false);
    // Tracks derived mono typefaces (bold mono, italic mono, etc.) so downstream hooks don't replace them
    private static final Set<Typeface> monoTypefaces = Collections.newSetFromMap(new WeakHashMap<>());

    // Check if a family name string is monospace
    private static boolean isMonoFamily(String family) {
        if (family == null) return false;
        String l = family.toLowerCase();
        return l.equals("monospace")
            || l.equals("sans-serif-monospace")
            || l.equals("serif-monospace")
            || l.contains("mono")
            || l.contains("courier")
            || l.contains("inconsolata")
            || l.contains("consolas")
            || l.contains("source code")
            || l.contains("fira code")
            || l.contains("jetbrains")
            || l.contains("hack")
            || l.contains("cascadia");
    }

    // Check if a Typeface instance is monospace
    private static boolean isMonoTypeface(Typeface tf) {
        if (tf == null) return false;
        // Direct reference check against system mono constant
        if (Typeface.MONOSPACE.equals(tf)) return true;
        // Check if it's a derived mono typeface (bold mono, italic mono, bolditalic mono)
        return monoTypefaces.contains(tf);
    }

    // Check if a file/asset path looks like a monospace font
    private static boolean isMonoPath(String path) {
        if (path == null) return false;
        String l = path.toLowerCase();
        return l.contains("mono")
            || l.contains("courier")
            || l.contains("inconsolata")
            || l.contains("consolas")
            || l.contains("sourcecode")
            || l.contains("source_code")
            || l.contains("firacode")
            || l.contains("fira_code")
            || l.contains("jetbrains")
            || l.contains("cascadia")
            || l.contains("hack");
    }

    private static Typeface getSystemTypeface(int weight, boolean italic) {
        String key = weight + "_" + italic;
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
                    private final ThreadLocal<Boolean> wasMono = ThreadLocal.withInitial(() -> false);

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        String family = (String) param.args[0];
                        if (isMonoFamily(family)) {
                            wasMono.set(true);
                            return; // passthrough all mono
                        }
                        wasMono.set(false);
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

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (wasMono.get()) {
                            Typeface result = (Typeface) param.getResult();
                            if (result != null) monoTypefaces.add(result);
                            wasMono.set(false);
                        }
                    }
                });

        // HOOK 2: Typeface.create(Typeface family, int style)
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "create", Typeface.class, int.class,
                new XC_MethodHook() {
                    private final ThreadLocal<Boolean> wasMono = ThreadLocal.withInitial(() -> false);

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        Typeface baseTf = (Typeface) param.args[0];
                        if (isMonoTypeface(baseTf)) {
                            wasMono.set(true);
                            return; // passthrough all mono styles (bold, italic, bolditalic)
                        }
                        wasMono.set(false);
                        int style = (int) param.args[1];

                        int baseWeight = extractWeight(baseTf);
                        boolean baseItalic = extractItalic(baseTf);

                        boolean requestedItalic = (style & Typeface.ITALIC) != 0;
                        boolean requestedBold = (style & Typeface.BOLD) != 0;

                        int finalWeight = requestedBold ? Math.max(baseWeight, 700) : baseWeight;
                        boolean finalItalic = baseItalic || requestedItalic;

                        param.setResult(getSystemTypeface(finalWeight, finalItalic));
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (wasMono.get()) {
                            Typeface result = (Typeface) param.getResult();
                            if (result != null) monoTypefaces.add(result);
                            wasMono.set(false);
                        }
                    }
                });

        // HOOK 3: Typeface.create(Typeface family, int weight, boolean italic) [API 28+]
        if (Build.VERSION.SDK_INT >= 28) {
            XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                    "create", Typeface.class, int.class, boolean.class,
                    new XC_MethodHook() {
                        private final ThreadLocal<Boolean> wasMono = ThreadLocal.withInitial(() -> false);

                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (inHook.get()) return;
                            Typeface baseTf = (Typeface) param.args[0];
                            if (isMonoTypeface(baseTf)) {
                                wasMono.set(true);
                                return; // passthrough mono bold, italic, bolditalic
                            }
                            wasMono.set(false);
                            int weight = (int) param.args[1];
                            boolean italic = (boolean) param.args[2];
                            param.setResult(getSystemTypeface(weight, italic));
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (wasMono.get()) {
                                Typeface result = (Typeface) param.getResult();
                                if (result != null) monoTypefaces.add(result);
                                wasMono.set(false);
                            }
                        }
                    });
        }

        // HOOK 4: Typeface.createFromAsset(AssetManager, String path)
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "createFromAsset",
                android.content.res.AssetManager.class, String.class,
                new XC_MethodHook() {
                    private final ThreadLocal<Boolean> wasMono = ThreadLocal.withInitial(() -> false);

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        String path = (String) param.args[1];
                        if (isMonoPath(path)) {
                            wasMono.set(true);
                            return; // passthrough all mono asset fonts
                        }
                        wasMono.set(false);
                        int weight = inferWeightFromName(path);
                        boolean italic = path != null && (path.toLowerCase().contains("italic") || path.toLowerCase().contains("oblique"));
                        param.setResult(getSystemTypeface(weight, italic));
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (wasMono.get()) {
                            Typeface result = (Typeface) param.getResult();
                            if (result != null) monoTypefaces.add(result);
                            wasMono.set(false);
                        }
                    }
                });

        // HOOK 5: Typeface.createFromFile(String path)
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "createFromFile", String.class,
                new XC_MethodHook() {
                    private final ThreadLocal<Boolean> wasMono = ThreadLocal.withInitial(() -> false);

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        String path = (String) param.args[0];
                        if (isMonoPath(path)) {
                            wasMono.set(true);
                            return; // passthrough all mono file fonts
                        }
                        wasMono.set(false);
                        int weight = inferWeightFromName(path);
                        boolean italic = path != null && (path.toLowerCase().contains("italic") || path.toLowerCase().contains("oblique"));
                        param.setResult(getSystemTypeface(weight, italic));
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (wasMono.get()) {
                            Typeface result = (Typeface) param.getResult();
                            if (result != null) monoTypefaces.add(result);
                            wasMono.set(false);
                        }
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
                        if (isMonoTypeface(tf)) return; // passthrough mono in Paint too

                        param.args[0] = getSystemTypeface(extractWeight(tf), extractItalic(tf));
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
                        if (isMonoTypeface(tf)) return; // passthrough mono in TextView

                        param.args[0] = getSystemTypeface(extractWeight(tf), extractItalic(tf));
                    }
                });

        // HOOK 8: TextView.setTypeface(Typeface, int style)
        XposedHelpers.findAndHookMethod("android.widget.TextView", lpparam.classLoader,
                "setTypeface", Typeface.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        Typeface baseTf = (Typeface) param.args[0];
                        if (isMonoTypeface(baseTf)) return; // passthrough mono bolditalic etc in TextView
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
        // HOOK 9: ResourcesCompat.getFont() — catches XML font resources (@font/jetbrains_mono etc.)
        // Apps using font XML declarations never go through createFromAsset, so hooks 4/5 miss them entirely
        try {
            XposedHelpers.findAndHookMethod("androidx.core.content.res.ResourcesCompat", lpparam.classLoader,
                    "getFont", Context.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Typeface result = (Typeface) param.getResult();
                            if (result == null) return;
                            try {
                                Context ctx = (Context) param.args[0];
                                int resId = (int) param.args[1];
                                String resName = ctx.getResources().getResourceEntryName(resId);
                                if (isMonoFamily(resName) || isMonoPath(resName)) {
                                    monoTypefaces.add(result);
                                    // Also register any typeface this one was derived from
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
        } catch (Throwable ignored) {
            // androidx not present in this app, skip silently
        }
    }
}
