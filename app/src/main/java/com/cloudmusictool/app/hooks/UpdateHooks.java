package com.cloudmusictool.app.hooks;

import android.content.Context;

import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.dexkit.DexKitAnchors;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * UI-01 更新弹窗与强制更新拦截。
 */
public final class UpdateHooks {

    private UpdateHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.UPDATE_DIALOG;
        try {
            hookCMUpdateConfig(module, cl, f);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " CMUpdateConfig hook failed", t);
        }

        try {
            hookUpdateD(module, cl, f);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " appupdate.d hook failed", t);
        }

        try {
            hookUpdateInfo(module, cl, f);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " UpdateInfo hook failed", t);
        }

        try {
            hookFw0E(module, cl, f);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " fw0.e hook failed", t);
        }
    }

    private static void hookCMUpdateConfig(XposedModule module, ClassLoader cl, Feature f) {
        Class<?> configCls = HookKit.findClass(module, f, cl, "com.netease.cloudmusic.appupdate.CMUpdateConfig");
        if (configCls == null) {
            return;
        }
        Method hasNewVersion = HookKit.findMethod(module, f, configCls, "hasNewVersion");
        HookKit.install(module, f, "CMUpdateConfig#hasNewVersion", hasNewVersion,
                HookKit.constBool(module, f, "CMUpdateConfig.hasNewVersion -> false", false));
    }

    private static void hookUpdateD(XposedModule module, ClassLoader cl, Feature f) {
        Class<?> dCls = HookKit.findClass(module, f, cl, "com.netease.cloudmusic.appupdate.d",
                DexKitAnchors.APPUPDATE_D);
        if (dCls == null) {
            return;
        }
        Method mB = HookKit.findMethod(module, f, dCls, "B");
        HookKit.install(module, f, "appupdate.d#B", mB,
                HookKit.constBool(module, f, "appupdate.d.B -> false", false));

        Method mI = HookKit.findMethod(module, f, dCls, "I");
        HookKit.install(module, f, "appupdate.d#I", mI,
                HookKit.constBool(module, f, "appupdate.d.I -> false", false));
    }

    private static void hookUpdateInfo(XposedModule module, ClassLoader cl, Feature f) {
        Class<?> updateInfoCls = HookKit.findClass(module, f, cl, "com.netease.cloudmusic.appupdate.meta.UpdateInfo");
        if (updateInfoCls == null) {
            return;
        }
        Method getNeedPopUp = HookKit.findMethod(module, f, updateInfoCls, "getNeedPopUp");
        HookKit.install(module, f, "UpdateInfo#getNeedPopUp", getNeedPopUp,
                HookKit.constBool(module, f, "UpdateInfo.getNeedPopUp -> Boolean.FALSE", false));

        Method getForceUpdate = HookKit.findMethod(module, f, updateInfoCls, "getForceUpdate");
        HookKit.install(module, f, "UpdateInfo#getForceUpdate", getForceUpdate,
                HookKit.constBool(module, f, "UpdateInfo.getForceUpdate -> Boolean.FALSE", false));
    }

    private static void hookFw0E(XposedModule module, ClassLoader cl, Feature f) {
        Class<?> eCls = HookKit.findClass(module, f, cl, "fw0.e", DexKitAnchors.FW0_E);
        if (eCls == null) {
            return;
        }
        Method q = HookKit.findMethod(module, f, eCls, "q", Context.class, boolean.class, boolean.class);
        if (q == null) {
            q = HookKit.findByNameQuiet(eCls, "q");
        }
        HookKit.install(module, f, "fw0.e#q", q, HookKit.skipVoid(module, f, "fw0.e.q"));
    }
}
