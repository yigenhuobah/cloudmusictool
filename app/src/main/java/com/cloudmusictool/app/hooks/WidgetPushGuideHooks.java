package com.cloudmusictool.app.hooks;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * UI-13 小组件 / 推送引导屏蔽。
 * 说明：两个推送引导 Fragment 继承自宿主自定义的 DialogFragmentBase。
 * 已核对宿主实现：PushGuideCenterDialogFragment#onActivityCreated 对 getDialog() 做空判断，
 * DialogFragmentBase#onStart 在 mDialog == null 时直接返回，因此 onCreateView 返回 null
 * 不会触发 NPE；仍额外挂 onActivityCreated 主动 dismiss，确保不出现空 Dialog。
 * 注意：UI-13 尚未在真机上自然触发验证（服务端未下发推送引导）。
 */
public final class WidgetPushGuideHooks {

    private static final String WIDGET_GUIDE_HELPER =
            "com.netease.cloudmusic.music.biz.appwidgets.view.WidgetAddGuideDialogHelper$a";
    private static final String PUSH_CENTER_FRAGMENT =
            "com.netease.cloudmusic.main.hint.push.PushGuideCenterDialogFragment";
    private static final String PUSH_BOTTOM_SHEET_FRAGMENT =
            "com.netease.cloudmusic.main.hint.push.PushGuideBottomSheetFragment";

    private WidgetPushGuideHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl, Feature feature) {
        if (feature == null) {
            HookKit.warn(module, "UI-13 install skipped: feature is null");
            return;
        }
        Feature f = feature;

        // 1. 小组件添加引导 AB 实验门
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, WIDGET_GUIDE_HELPER);
            if (cls != null) {
                // 定位/返回类型校验/安装/登记收敛到 HookKit.installConstFalse。
                HookKit.installConstFalse(module, f, cls, "a", "WidgetAddGuideDialogHelper$a#a");
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " WidgetAddGuideDialogHelper$a hook failed", t);
        }

        // 2. 推送引导居中弹窗 / 底部弹窗
        installPushGuide(module, f, cl, PUSH_CENTER_FRAGMENT, "PushGuideCenterDialogFragment");
        installPushGuide(module, f, cl, PUSH_BOTTOM_SHEET_FRAGMENT, "PushGuideBottomSheetFragment");
    }

    private static void installPushGuide(XposedModule module, Feature f, ClassLoader cl,
                                         String className, String simpleName) {
        Class<?> cls;
        try {
            cls = HookKit.findClass(module, f, cl, className);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " " + simpleName + " class lookup failed", t);
            return;
        }
        if (cls == null) {
            return;
        }

        // 2.1 onCreateView 返回 null，阻止布局构建（宿主 DialogFragmentBase 对 null dialog 安全）
        try {
            Method onCreateView = HookKit.findMethod(module, f, cls, "onCreateView",
                    View.class, new Class<?>[]{LayoutInflater.class, ViewGroup.class, Bundle.class});
            if (onCreateView != null) {
                if (onCreateView.getReturnType() == View.class) {
                    HookKit.install(module, f, simpleName + "#onCreateView", onCreateView, chain -> {
                        try {
                            HookKit.hit(module, f, simpleName + ".onCreateView -> null");
                            return null;
                        } catch (Throwable t) {
                            HookKit.error(module, f.id + " intercept error "
                                    + simpleName + "#onCreateView", t);
                            return chain.proceed();
                        }
                    });
                } else {
                    HookKit.warn(module, f.id + " skip " + simpleName
                            + "#onCreateView: unexpected return " + onCreateView.getReturnType());
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " " + simpleName + "#onCreateView hook failed", t);
        }

        // 2.2 onCreateView 为 null 时宿主仍可能展示空 Dialog，补一次 dismiss。
        try {
            Method onActivityCreated = HookKit.findMethod(module, f, cls, "onActivityCreated",
                    void.class, new Class<?>[]{Bundle.class});
            if (onActivityCreated != null && onActivityCreated.getReturnType() == void.class) {
                HookKit.install(module, f, simpleName + "#onActivityCreated", onActivityCreated, chain -> {
                    Object result;
                    try {
                        result = chain.proceed();
                    } catch (Throwable t) {
                        // 不吞宿主异常：记录后原样抛出，保持宿主崩溃/降级语义。
                        HookKit.error(module, f.id + " host error in "
                                + simpleName + "#onActivityCreated", t);
                        throw t;
                    }
                    dismissQuietly(module, f, simpleName, chain.getThisObject());
                    return result;
                });
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " " + simpleName + "#onActivityCreated hook failed", t);
        }
    }

    private static void dismissQuietly(XposedModule module, Feature f, String simpleName,
                                       Object fragment) {
        if (fragment == null) {
            return;
        }
        try {
            Method dismiss = fragment.getClass().getMethod("dismissAllowingStateLoss");
            dismiss.setAccessible(true);
            dismiss.invoke(fragment);
            HookKit.hit(module, f, simpleName + ".dismissAllowingStateLoss");
        } catch (Throwable t) {
            HookKit.warn(module, f.id + " dismiss failed " + simpleName + ": " + t.getMessage());
        }
    }
}
