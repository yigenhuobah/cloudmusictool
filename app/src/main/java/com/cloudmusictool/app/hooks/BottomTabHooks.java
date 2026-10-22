package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Config;
import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.dexkit.DexKitAnchors;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * UI-05 底部导航精简：屏蔽 FM/动态底栏开关，并按保留列表过滤底栏项。
 */
public final class BottomTabHooks {

    private BottomTabHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl, Config config) {
        Feature f = Feature.BOTTOM_NAV;

        // 1. SettingActivity.T8 / U8 (showFMAtBottomTab / showNoteAtBottomTab)
        try {
            Class<?> settingCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.music.biz.setting.activity.SettingActivity");
            if (settingCls != null) {
                Method mT8 = HookKit.findMethodQuiet(settingCls, "T8");
                if (mT8 != null) {
                    HookKit.install(module, f, "SettingActivity#T8", mT8,
                            HookKit.constBool(module, f, "SettingActivity.T8 (showFMAtBottomTab) -> false", false));
                }
                Method mU8 = HookKit.findMethodQuiet(settingCls, "U8");
                if (mU8 != null) {
                    HookKit.install(module, f, "SettingActivity#U8", mU8,
                            HookKit.constBool(module, f, "SettingActivity.U8 (showNoteAtBottomTab) -> false", false));
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " SettingActivity hook failed", t);
        }

        // 2. th0.h.h()Ljava/util/List;
        try {
            Class<?> th0h = HookKit.findClass(module, f, cl, "th0.h", DexKitAnchors.TH0_H);
            if (th0h != null) {
                Method mh = HookKit.findMethodQuiet(th0h, "h");
                if (mh != null) {
                    HookKit.install(module, f, "th0.h#h", mh, chain -> {
                        Object res = chain.proceed();
                        try {
                            return filterBottomTabs(module, f, config, res, "th0.h#h");
                        } catch (Throwable t) {
                            HookKit.error(module, f.id + " intercept error th0.h#h", t);
                        }
                        return res;
                    });
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " th0.h hook failed", t);
        }

        // 3. rh0.c.c(...)
        try {
            Class<?> rh0c = HookKit.findClass(module, f, cl, "rh0.c", DexKitAnchors.RH0_C);
            if (rh0c != null) {
                Method mc = HookKit.findByNameAndArity(rh0c, "c", 3);
                if (mc == null) {
                    mc = HookKit.findByNameQuiet(rh0c, "c");
                }
                if (mc != null) {
                    HookKit.install(module, f, "rh0.c#c", mc, chain -> {
                        Object res = chain.proceed();
                        try {
                            return filterBottomTabs(module, f, config, res, "rh0.c#c");
                        } catch (Throwable t) {
                            HookKit.error(module, f.id + " intercept error rh0.c#c", t);
                        }
                        return res;
                    });
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " rh0.c hook failed", t);
        }
    }

    private static Object filterBottomTabs(XposedModule module, Feature f, Config config, Object res, String source) {
        if (!(res instanceof List)) {
            return res;
        }
        Set<String> keepSet = config.getFilterSet(Config.KEY_BOTTOM_NAV_KEEP);
        if (keepSet == null || keepSet.isEmpty()) {
            return res;
        }
        List<?> list = (List<?>) res;
        List<Object> out = new ArrayList<>();
        for (Object item : list) {
            if (HookKit.shouldKeepTab(item, keepSet)) {
                out.add(item);
            }
        }
        if (out.isEmpty()) {
            return res;
        }
        HookKit.hit(module, f, source + " filtered from " + list.size() + " to " + out.size() + " tabs");
        return out;
    }
}
