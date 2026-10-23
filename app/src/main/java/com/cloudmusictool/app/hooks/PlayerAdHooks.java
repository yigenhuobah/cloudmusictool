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
                String kName = HookKit.mappedMethodName(delegate, "k");
                Method k = adCls != null
                        ? HookKit.findMethod(module, f, delegate, "k", adCls, ViewGroup.class, ViewGroup.class)
                        : HookKit.findByNameAndArity(delegate, kName, 3);
                HookKit.install(module, f, "PlayerAdJarDelegateImpl#k", k,
                        HookKit.skipVoid(module, f, "PlayerAdJarDelegateImpl.k"));

                Method q = adCls != null
                        ? HookKit.findMethod(module, f, delegate, "q", adCls)
                        : HookKit.findByNameAndArity(delegate, "q", 1);
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
                        : HookKit.findByNameAndArity(helper, "checkNeedExpose", 1);
                HookKit.install(module, f, "AdPlayerFullScreenHelper#checkNeedExpose", check,
                        HookKit.constBool(module, f, "AdPlayerFullScreenHelper.checkNeedExpose -> false", false));
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " AdPlayerFullScreenHelper hook failed", t);
        }
    }
}
