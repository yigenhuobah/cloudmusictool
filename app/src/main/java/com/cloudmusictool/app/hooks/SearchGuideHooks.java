package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import io.github.libxposed.api.XposedModule;

/**
 * UI-11 关闭搜索框热词轮播引导。
 */
public final class SearchGuideHooks {

    private static final String SEARCH_GUIDE = "com.netease.cloudmusic.meta.SearchGuide";

    private SearchGuideHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl, Feature feature) {
        if (feature == null) {
            HookKit.warn(module, "UI-11 install skipped: feature is null");
            return;
        }
        Feature f = feature;

        try {
            Class<?> cls = HookKit.findClass(module, f, cl, SEARCH_GUIDE);
            if (cls == null) {
                return;
            }
            // 定位/返回类型校验/安装/登记收敛到 HookKit.installConstFalse。
            HookKit.installConstFalse(module, f, cls, "isValid", "SearchGuide#isValid");
        } catch (Throwable t) {
            HookKit.error(module, f.id + " SearchGuide hook failed", t);
        }
    }
}
