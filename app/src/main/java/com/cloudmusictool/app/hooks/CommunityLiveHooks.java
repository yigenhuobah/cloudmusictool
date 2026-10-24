package com.cloudmusictool.app.hooks;

import android.view.View;

import com.cloudmusictool.app.Feature;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import io.github.libxposed.api.XposedModule;

/**
 * UI-15 云村 / 直播推广屏蔽。
 */
public final class CommunityLiveHooks {

    private static final String LIVE_PROMOTION =
            "com.netease.play.livepage.promotion.LivePromotion";
    private static final String NOTES_PUBLISH_HINT =
            "com.netease.cloudmusic.music.biz.mlog.consume.social.homepage.ui.NotesPublishGuideHint";
    private static final String NOTES_SEARCH_HINT =
            "com.netease.cloudmusic.music.biz.mlog.consume.social.homepage.ui.NotesSearchGuideHint";
    private static final String COMMENT_LIVE_VIEW_HOLDER =
            "com.netease.cloudmusic.module.comment.CommentLiveViewHolder";

    private CommunityLiveHooks() {
    }

    public static void install(XposedModule module, ClassLoader cl, Feature feature) {
        if (feature == null) {
            HookKit.warn(module, "UI-15 install skipped: feature is null");
            return;
        }
        Feature f = feature;

        // 1. 直播间推广位：JSONObject / JSONArray 两个重载都返回空列表
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, LIVE_PROMOTION);
            if (cls != null) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (!"listFromJson".equals(m.getName())) {
                        continue;
                    }
                    if (!List.class.isAssignableFrom(m.getReturnType())) {
                        continue;
                    }
                    HookKit.install(module, f, "LivePromotion#listFromJson("
                            + m.getParameterTypes().length + ")", m, chain -> {
                        try {
                            HookKit.hit(module, f, "LivePromotion.listFromJson -> emptyList");
                            return new ArrayList<Object>();
                        } catch (Throwable t) {
                            HookKit.error(module, f.id
                                    + " intercept error LivePromotion#listFromJson", t);
                            return chain.proceed();
                        }
                    });
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " LivePromotion hook failed", t);
        }

        // 2. 云村发布 / 搜索引导气泡
        installMaybeDisplayHint(module, f, cl, NOTES_PUBLISH_HINT, "NotesPublishGuideHint");
        installMaybeDisplayHint(module, f, cl, NOTES_SEARCH_HINT, "NotesSearchGuideHint");

        // 3. 评论区直播推荐卡：不绑定并隐藏 itemView，避免留下可见空白卡
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, COMMENT_LIVE_VIEW_HOLDER);
            if (cls != null) {
                Method m = HookKit.findMethod(module, f, cls, "onBindViewHolder",
                        void.class, new Class<?>[]{Object.class, int.class, int.class});
                if (m != null && m.getReturnType() == void.class) {
                    HookKit.install(module, f, "CommentLiveViewHolder#onBindViewHolder", m, chain -> {
                        try {
                            HookKit.hit(module, f, "CommentLiveViewHolder.onBindViewHolder suppressed");
                            hideItemView(module, f, chain.getThisObject());
                            return null;
                        } catch (Throwable t) {
                            HookKit.error(module, f.id
                                    + " intercept error CommentLiveViewHolder#onBindViewHolder", t);
                            return chain.proceed();
                        }
                    });
                } else if (m != null) {
                    HookKit.warn(module, f.id
                            + " skip CommentLiveViewHolder#onBindViewHolder: unexpected return "
                            + m.getReturnType());
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " CommentLiveViewHolder hook failed", t);
        }
    }

    private static void installMaybeDisplayHint(XposedModule module, Feature f, ClassLoader cl,
                                                String className, String simpleName) {
        try {
            Class<?> cls = HookKit.findClass(module, f, cl, className);
            if (cls == null) {
                return;
            }
            // 定位/返回类型校验/安装/登记收敛到 HookKit.installVoidByName。
            HookKit.installVoidByName(module, f, cls, "maybeDisplayHint",
                    simpleName + "#maybeDisplayHint");
        } catch (Throwable t) {
            HookKit.error(module, f.id + " " + simpleName + " hook failed", t);
        }
    }

    private static void hideItemView(XposedModule module, Feature f, Object holder) {
        if (holder == null) {
            return;
        }
        Class<?> cls = holder.getClass();
        while (cls != null) {
            try {
                Field field = cls.getDeclaredField("itemView");
                field.setAccessible(true);
                Object view = field.get(holder);
                if (view instanceof View) {
                    ((View) view).setVisibility(View.GONE);
                }
                return;
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            } catch (Throwable t) {
                HookKit.warn(module, f.id + " hide itemView failed: " + t.getMessage());
                return;
            }
        }
    }
}
