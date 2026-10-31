package com.cloudmusictool.app.hooks;

import android.view.ViewGroup;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * AD-08 播放页广告与商业挂件。
 */
public final class PlayerAdHooks {

    private PlayerAdHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.PLAYER_AD;

        Class<?> adCls = null;
        try {
            adCls = Class.forName("com.netease.cloudmusic.meta.Ad", false, cl);
        } catch (Throwable ignored) {
        }

        try {
            Class<?> delegate = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.module.ad.playerjar.PlayerAdJarDelegateImpl");
            if (delegate != null) {
                // 9.6.13: k -> j（Profile 映射）；adCls 缺失时的兜底扫描也用映射后的名字。
                Method k = adCls != null
                        ? HookKit.findMethod(module, f, delegate, "k", adCls, ViewGroup.class, ViewGroup.class)
                        : findAdMethod(module, f, delegate, "k", void.class, null, 3,
                                "PlayerAdJarDelegateImpl#k");
                HookKit.install(module, f, "PlayerAdJarDelegateImpl#k", k,
                        HookKit.skipVoid(module, f, "PlayerAdJarDelegateImpl.k"));

                Method q = adCls != null
                        ? HookKit.findMethod(module, f, delegate, "q", adCls)
                        : findAdMethod(module, f, delegate, "q", void.class, null, 1,
                                "PlayerAdJarDelegateImpl#q");
                HookKit.install(module, f, "PlayerAdJarDelegateImpl#q", q,
                        HookKit.skipVoid(module, f, "PlayerAdJarDelegateImpl.q"));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " PlayerAdJarDelegateImpl hook failed", t);
        }

        try {
            Class<?> gift = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.module.player.vinylfakelayer.musicgift.meta.MusicGiftModel");
            if (gift != null) {
                Method isValid = HookKit.findMethod(module, f, gift, "isValid");
                HookKit.install(module, f, "MusicGiftModel#isValid", isValid,
                        HookKit.constBool(module, f, "MusicGiftModel.isValid -> false", false));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " MusicGiftModel hook failed", t);
        }

        try {
            Class<?> helper = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.ui.ad.player.AdPlayerFullScreenHelper");
            if (helper != null) {
                Method check = adCls != null
                        ? HookKit.findMethod(module, f, helper, "checkNeedExpose", adCls)
                        : findAdMethod(module, f, helper, "checkNeedExpose", boolean.class, null, 1,
                                "AdPlayerFullScreenHelper#checkNeedExpose");
                HookKit.install(module, f, "AdPlayerFullScreenHelper#checkNeedExpose", check,
                        HookKit.constBool(module, f, "AdPlayerFullScreenHelper.checkNeedExpose -> false", false));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " AdPlayerFullScreenHelper hook failed", t);
        }
    }

    /**
     * adCls 缺失时的兜底定位：先按 Profile 映射后的实际方法名做参数个数匹配，再交给
     * HookKit.findMethod 按 Profile 精确坐标解析——后者失败会统一登记挂载点缺失，
     * 不再静默失效（findByNameAndArity 失败无日志、install(null) 直接 return false）。
     *
     * 命中后校验返回类型：同名同参数个数的无关方法不会再被整体 no-op；校验失败同样
     * 交给 Profile 路径兜底（仍失败则诊断里可见）。
     */
    static Method findAdMethod(XposedModule module, Feature f, Class<?> cls, String name,
                               Class<?> returnType, Class<?>[] leading, int arity, String label) {
        String actual = HookKit.mappedMethodName(cls, name);
        Method m = leading != null
                ? HookKit.findByShape(cls, actual, leading)
                : HookKit.findByNameAndArity(cls, actual, arity);
        if (m != null && m.getParameterTypes().length != arity) {
            m = null;
        }
        if (m != null && returnType != null && m.getReturnType() != returnType) {
            HookKit.warn(module, f.id + " " + label + " return type mismatch: "
                    + m.getReturnType().getName() + " (expected " + returnType.getName()
                    + "); try profile coordinates");
            m = null;
        }
        if (m == null) {
            m = HookKit.findMethod(module, f, cls, name, returnType,
                    (Class<?>[]) null, (String[]) null);
        }
        return m;
    }
}
