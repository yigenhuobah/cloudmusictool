package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.dexkit.DexKitAnchors;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * UI-08 禁止自动播放：拦截启动和切歌时的各类自动播放逻辑。
 */
public final class AutoPlayHooks {

    private AutoPlayHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.AUTOPLAY;

        // 1. SettingActivity.V8()Z -> false
        try {
            Class<?> settingCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.music.biz.setting.activity.SettingActivity");
            if (settingCls != null) {
                Method mV8 = HookKit.findMethodQuiet(settingCls, "V8");
                if (mV8 != null) {
                    HookKit.install(module, f, "SettingActivity#V8", mV8,
                            HookKit.constBool(module, f, "SettingActivity.V8 -> false", false));
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " SettingActivity hook failed", t);
        }

        // 2. com.netease.cloudmusic.utils.jn: m0()Z -> false
        try {
            Class<?> jnCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.utils.jn",
                    DexKitAnchors.JN);
            if (jnCls != null) {
                Method mM0 = HookKit.findMethodQuiet(jnCls, "m0");
                if (mM0 != null) {
                    HookKit.install(module, f, "jn#m0", mM0,
                            HookKit.constBool(module, f, "jn.m0 -> false", false));
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " jn hook failed", t);
        }

        // 3. tt0.d: a()Z / d()Z -> false
        try {
            Class<?> tt0d = HookKit.findClass(module, f, cl, "tt0.d", DexKitAnchors.TT0_D);
            if (tt0d != null) {
                Method ma = HookKit.findMethodQuiet(tt0d, "a");
                if (ma != null) {
                    HookKit.install(module, f, "tt0.d#a", ma,
                            HookKit.constBool(module, f, "tt0.d.a -> false", false));
                }
                Method md = HookKit.findMethodQuiet(tt0d, "d");
                if (md != null) {
                    HookKit.install(module, f, "tt0.d#d", md,
                            HookKit.constBool(module, f, "tt0.d.d -> false", false));
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " tt0.d hook failed", t);
        }

        // 4. vt0.r: j()Z -> false
        try {
            Class<?> vt0r = HookKit.findClass(module, f, cl, "vt0.r", DexKitAnchors.VT0_R);
            if (vt0r != null) {
                Method mj = HookKit.findMethodQuiet(vt0r, "j");
                if (mj != null) {
                    HookKit.install(module, f, "vt0.r#j", mj,
                            HookKit.constBool(module, f, "vt0.r.j -> false", false));
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " vt0.r hook failed", t);
        }

        // 5. lv0.j.e() -> AutoPlayConfig(false, false)
        try {
            Class<?> lv0j = HookKit.findClass(module, f, cl, "lv0.j", DexKitAnchors.LV0_J);
            if (lv0j != null) {
                Method me = HookKit.findMethodQuiet(lv0j, "e");
                if (me == null) {
                    me = HookKit.findByNameQuiet(lv0j, "e");
                }
                if (me != null) {
                    HookKit.install(module, f, "lv0.j#e", me, chain -> {
                        Object res = chain.proceed();
                        try {
                            if (res != null) {
                                try {
                                    java.lang.reflect.Constructor<?> ctor =
                                            res.getClass().getDeclaredConstructor(boolean.class, boolean.class);
                                    ctor.setAccessible(true);
                                    Object newCfg = ctor.newInstance(false, false);
                                    HookKit.hit(module, f, "lv0.j.e -> AutoPlayConfig(false, false)");
                                    return newCfg;
                                } catch (Throwable t2) {
                                    HookKit.error(module, f.id + " construct AutoPlayConfig failed", t2);
                                }
                            }
                        } catch (Throwable t) {
                            HookKit.error(module, f.id + " intercept error lv0.j#e", t);
                        }
                        return res;
                    });
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " lv0.j hook failed", t);
        }
    }
}
