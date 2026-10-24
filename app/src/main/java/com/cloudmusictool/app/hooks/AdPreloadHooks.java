package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import io.github.libxposed.api.XposedModule;

/**
 * AD-05 冷启动广告预加载裁剪。
 * 只跳过“纯预热 / 推广”类启动任务，不触碰核心启动任务。
 */
public final class AdPreloadHooks {

    private static final String[] TARGETS = {
            "com.netease.cloudmusic.process.task.main.create.AdColdBootEntranceTask",
            "com.netease.cloudmusic.process.task.main.createproxy.PodcastRewardAdTask",
            "com.netease.cloudmusic.process.task.main.create.APreloadRecommendViewTask"
    };

    private AdPreloadHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl, Feature feature) {
        if (feature == null) {
            HookKit.warn(module, "AD-05 install skipped: feature is null");
            return;
        }
        Feature f = feature;

        for (String className : TARGETS) {
            try {
                String simpleName = className.substring(className.lastIndexOf('.') + 1);
                Class<?> cls = HookKit.findClass(module, f, cl, className);
                if (cls == null) {
                    HookKit.warn(module, f.id + " skip " + simpleName + ": class not present in this build");
                    continue;
                }
                // 定位/返回类型校验/安装/登记收敛到 HookKit.installVoidByName。
                HookKit.installVoidByName(module, f, cls, "init", simpleName + "#init");
            } catch (Throwable t) {
                HookKit.error(module, f.id + " " + className + " hook failed", t);
            }
        }
    }
}
