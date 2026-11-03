package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.dexkit.DexKitAnchors;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * UI-09 关闭头像挂件：屏蔽个人主页与评论区头像动态挂件与装饰。
 */
public final class PendantHooks {

    private PendantHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.PENDANT;
        try {
            Class<?> jnCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.utils.jn",
                    DexKitAnchors.JN);
            if (jnCls != null) {
                Method mL = HookKit.findMethodQuiet(module, f, "jn#L", jnCls, "L");
                if (mL == null) {
                    mL = HookKit.findByNameQuiet(module, f, "jn#L", jnCls, "L");
                }
                if (mL != null) {
                    HookKit.install(module, f, "jn#L", mL,
                            HookKit.constBool(module, f, "jn.L -> false", false));
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " jn hook failed", t);
        }
    }
}
