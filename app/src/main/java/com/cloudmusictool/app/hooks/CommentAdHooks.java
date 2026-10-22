package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * AD-07 评论区广告与推广组件。
 */
public final class CommentAdHooks {

    private CommentAdHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl) {
        Feature f = Feature.COMMENT_AD;

        try {
            Class<?> adapter = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.adapter.CommentAdapter");
            if (adapter != null) {
                Class<?> entryCls = null;
                try {
                    entryCls = Class.forName("com.netease.cloudmusic.meta.virtual.CommentListEntry", false, cl);
                } catch (Throwable ignored) {
                }

                Method y1 = entryCls != null
                        ? HookKit.findMethod(module, f, adapter, "y1", entryCls)
                        : HookKit.findByNameAndArity(adapter, "y1", 1);
                HookKit.install(module, f, "CommentAdapter#y1", y1, chain -> {
                    try {
                        Object entry = chain.getArg(0);
                        clearField(entry, "ad");
                        clearField(entry, "commentGeneralBanner");
                        clearField(entry, "vipRcmdList");
                        clearField(entry, "liveRcmdList");
                        HookKit.hit(module, f, "CommentAdapter.y1: cleared ad fields");
                        return chain.proceed();
                    } catch (Throwable t) {
                        HookKit.error(module, f.id + " y1 intercept error", t);
                        return chain.proceed();
                    }
                });
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " CommentAdapter hook failed", t);
        }

        try {
            Class<?> special = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.meta.virtual.SpecialComment");
            if (special != null) {
                HookKit.hookNull(module, f, special, "getGeneralBanner");
                HookKit.hookNull(module, f, special, "getLiveRcmdList");
                HookKit.hookNull(module, f, special, "getVipRcmdList");
                HookKit.hookNull(module, f, special, "getConcertInfo");
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " SpecialComment hook failed", t);
        }

        try {
            Class<?> comment = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.meta.Comment");
            if (comment != null) {
                Method isTagValid = HookKit.findMethod(module, f, comment, "isTagValid");
                HookKit.install(module, f, "Comment#isTagValid", isTagValid,
                        HookKit.constBool(module, f, "Comment.isTagValid -> false", false));

                Method bottomTags = HookKit.findMethod(module, f, comment, "getBottomTags");
                HookKit.install(module, f, "Comment#getBottomTags", bottomTags, chain -> {
                    try {
                        HookKit.hit(module, f, "Comment.getBottomTags -> null");
                        return null;
                    } catch (Throwable t) {
                        return chain.proceed();
                    }
                });
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " Comment hook failed", t);
        }

        try {
            Class<?> vipAdapter = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.adapter.CommentVipRcmdAdapter");
            if (vipAdapter != null) {
                Method getItemCount = HookKit.findMethod(module, f, vipAdapter, "getItemCount");
                HookKit.install(module, f, "CommentVipRcmdAdapter#getItemCount", getItemCount, chain -> {
                    try {
                        HookKit.hit(module, f, "CommentVipRcmdAdapter.getItemCount -> 0");
                        return Integer.valueOf(0);
                    } catch (Throwable t) {
                        return chain.proceed();
                    }
                });
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " CommentVipRcmdAdapter hook failed", t);
        }
    }

    private static void clearField(Object target, String name) {
        try {
            Field field = HookKit.findField(target.getClass(), name);
            if (field != null) {
                field.setAccessible(true);
                field.set(target, null);
            }
        } catch (Throwable ignored) {
        }
    }
}
