package com.mk982.forcesystemfont;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Build;
import android.util.Log;

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

    private static final String TAG = "ForceSystemFont";
    private static final Map<String, Typeface> cache = new HashMap<>();
    private static final ThreadLocal<Boolean> inHook = ThreadLocal.withInitial(() -> false);
    private static final Set<Typeface> monoTypefaces = Collections.newSetFromMap(new WeakHashMap<>());

    private static void log(String msg) {
        Log.d(TAG, msg);
    }

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

    private static boolean isMonoTypeface(Typeface tf) {
        if (tf == null) return false;
        if (Typeface.MONOSPACE.equals(tf)) return true;
        return monoTypefaces.contains(tf);
    }

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
                else if (weight >= 600) { style = italic ? Typeface.BOLD_ITALIC : Typeface.BOLD; }
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
        } catch (Throwable ignored) { return 400; }
    }

    private static boolean extractItalic(Typeface tf) {
        if (tf == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= 28) return tf.isItalic();
            return (tf.getStyle() & Typeface.ITALIC) != 0;
        } catch (Throwable ignored) { return false; }
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
                            log("[H1] PASSTHROUGH mono family='" + family + "'");
                            wasMono.set(true);
                            return;
                        }
                        wasMono.set(false);
                        int style = (int) param.args[1];
                        boolean italic = (style & Typeface.ITALIC) != 0;
                        int weight = weightFromStyle(style);
                        if (family != null) {
                            String lf = family.toLowerCase();
                            if (lf.contains("medium"))     weight = 500;
                            else if (lf.contains("light")) weight = 300;
                            else if (lf.contains("thin"))  weight = 100;
                            else if (lf.contains("black")) weight = 900;
                        }
                        log("[H1] REPLACE family='" + family + "' → weight=" + weight + " italic=" + italic);
                        param.setResult(getSystemTypeface(weight, italic));
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (wasMono.get()) {
                            Typeface result = (Typeface) param.getResult();
                            if (result != null) {
                                monoTypefaces.add(result);
                                log("[H1] registered mono tf=" + result + " setSize=" + monoTypefaces.size());
                            }
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
                            log("[H2] PASSTHROUGH mono baseTf=" + baseTf);
                            wasMono.set(true);
                            return;
                        }
                        wasMono.set(false);
                        int style = (int) param.args[1];
                        int baseWeight = extractWeight(baseTf);
                        boolean baseItalic = extractItalic(baseTf);
                        boolean reqItalic = (style & Typeface.ITALIC) != 0;
                        boolean reqBold   = (style & Typeface.BOLD)   != 0;
                        int finalWeight = reqBold ? Math.max(baseWeight, 700) : baseWeight;
                        boolean finalItalic = baseItalic || reqItalic;
                        log("[H2] REPLACE baseTf=" + baseTf + " → weight=" + finalWeight + " italic=" + finalItalic);
                        param.setResult(getSystemTypeface(finalWeight, finalItalic));
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (wasMono.get()) {
                            Typeface result = (Typeface) param.getResult();
                            if (result != null) {
                                monoTypefaces.add(result);
                                log("[H2] registered mono tf=" + result + " setSize=" + monoTypefaces.size());
                            }
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
                                log("[H3] PASSTHROUGH mono baseTf=" + baseTf);
                                wasMono.set(true);
                                return;
                            }
                            wasMono.set(false);
                            int weight = (int) param.args[1];
                            boolean italic = (boolean) param.args[2];
                            log("[H3] REPLACE baseTf=" + baseTf + " → weight=" + weight + " italic=" + italic);
                            param.setResult(getSystemTypeface(weight, italic));
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (wasMono.get()) {
                                Typeface result = (Typeface) param.getResult();
                                if (result != null) {
                                    monoTypefaces.add(result);
                                    log("[H3] registered mono tf=" + result + " setSize=" + monoTypefaces.size());
                                }
                                wasMono.set(false);
                            }
                        }
                    });
        }

        // HOOK 4: Typeface.createFromAsset(AssetManager, String path)
        XposedHelpers.findAndHookMethod("android.graphics.Typeface", lpparam.classLoader,
                "createFromAsset", android.content.res.AssetManager.class, String.class,
                new XC_MethodHook() {
                    private final ThreadLocal<Boolean> wasMono = ThreadLocal.withInitial(() -> false);

                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (inHook.get()) return;
                        String path = (String) param.args[1];
                        log("[H4] createFromAsset path='" + path + "' isMono=" + isMonoPath(path));
                        if (isMonoPath(path)) {
                            wasMono.set(true);
                            return;
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
                            if (result != null) {
                                monoTypefaces.add(result);
                                log("[H4] registered mono tf=" + result + " setSize=" + monoTypefaces.size());
                            }
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
                        log("[H5] createFromFile path='" + path + "' isMono=" + isMonoPath(path));
                        if (isMonoPath(path)) {
                            wasMono.set(true);
                            return;
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
                            if (result != null) {
                                monoTypefaces.add(result);
                                log("[H5] registered mono tf=" + result + " setSize=" + monoTypefaces.size());
                            }
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
                        boolean isMono = isMonoTypeface(tf);
                        log("[H6] Paint.setTypeface tf=" + tf + " isMono=" + isMono + " setSize=" + monoTypefaces.size());
                        if (isMono) return;
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
                        boolean isMono = isMonoTypeface(tf);
                        log("[H7] TextView.setTypeface tf=" + tf + " isMono=" + isMono + " setSize=" + monoTypefaces.size());
                        if (isMono) return;
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
                        boolean isMono = isMonoTypeface(baseTf);
                        log("[H8] TextView.setTypeface(tf,style) tf=" + baseTf + " isMono=" + isMono + " setSize=" + monoTypefaces.size());
                        if (isMono) return;
                        int style = (int) param.args[1];
                        int baseWeight = extractWeight(baseTf);
                        boolean baseItalic = extractItalic(baseTf);
                        boolean reqItalic = (style & Typeface.ITALIC) != 0;
                        boolean reqBold   = (style & Typeface.BOLD)   != 0;
                        int finalWeight = reqBold ? Math.max(baseWeight, 700) : baseWeight;
                        boolean finalItalic = baseItalic || reqItalic;
                        param.args[0] = getSystemTypeface(finalWeight, finalItalic);
                        param.args[1] = finalItalic ? Typeface.ITALIC : Typeface.NORMAL;
                    }
                });

        // HOOK 9: ResourcesCompat.getFont() — catches XML font resources
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
                                boolean isMono = isMonoFamily(resName) || isMonoPath(resName);
                                log("[H9] ResourcesCompat.getFont resName='" + resName + "' isMono=" + isMono + " tf=" + result);
                                if (isMono) {
                                    monoTypefaces.add(result);
                                    log("[H9] registered mono tf=" + result + " setSize=" + monoTypefaces.size());
                                }
                            } catch (Throwable e) {
                                log("[H9] error: " + e.getMessage());
                            }
                        }
                    });
        } catch (Throwable e) {
            log("[H9] hook failed (androidx absent?): " + e.getMessage());
        }

        // HOOK 10: Typeface.Builder — Compose's font loading path (API 29+)
        // Compose bypasses all createFromAsset/create paths and uses Typeface.Builder directly
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                // Builder(AssetManager, String path)
                XposedHelpers.findAndHookConstructor("android.graphics.Typeface.Builder", lpparam.classLoader,
                        android.content.res.AssetManager.class, String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                String path = (String) param.args[1];
                                log("[H10] Typeface.Builder(asset) path='" + path + "' isMono=" + isMonoPath(path));
                                if (isMonoPath(path)) {
                                    XposedHelpers.setAdditionalInstanceField(param.thisObject, "isMono", true);
                                }
                            }
                        });

                // Builder(Resources, int resId)
                XposedHelpers.findAndHookConstructor("android.graphics.Typeface.Builder", lpparam.classLoader,
                        android.content.res.Resources.class, int.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    android.content.res.Resources res = (android.content.res.Resources) param.args[0];
                                    int resId = (int) param.args[1];
                                    String resName = res.getResourceEntryName(resId);
                                    log("[H10] Typeface.Builder(res) resName='" + resName + "' isMono=" + (isMonoFamily(resName) || isMonoPath(resName)));
                                    if (isMonoFamily(resName) || isMonoPath(resName)) {
                                        XposedHelpers.setAdditionalInstanceField(param.thisObject, "isMono", true);
                                    }
                                } catch (Throwable e) {
                                    log("[H10] Builder(res) error: " + e.getMessage());
                                }
                            }
                        });

                // Builder(File)
                XposedHelpers.findAndHookConstructor("android.graphics.Typeface.Builder", lpparam.classLoader,
                        java.io.File.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                java.io.File file = (java.io.File) param.args[0];
                                String path = file != null ? file.getName() : null;
                                log("[H10] Typeface.Builder(file) path='" + path + "' isMono=" + isMonoPath(path));
                                if (isMonoPath(path)) {
                                    XposedHelpers.setAdditionalInstanceField(param.thisObject, "isMono", true);
                                }
                            }
                        });

                // build() — register result if builder was tagged as mono
                XposedHelpers.findAndHookMethod("android.graphics.Typeface.Builder", lpparam.classLoader,
                        "build",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                Boolean isMono = (Boolean) XposedHelpers.getAdditionalInstanceField(param.thisObject, "isMono");
                                if (Boolean.TRUE.equals(isMono)) {
                                    Typeface result = (Typeface) param.getResult();
                                    if (result != null) {
                                        monoTypefaces.add(result);
                                        log("[H10] build() registered mono tf=" + result + " setSize=" + monoTypefaces.size());
                                    }
                                }
                            }
                        });

            } catch (Throwable e) {
                log("[H10] Typeface.Builder hook failed: " + e.getMessage());
            }
        }
    }
}
