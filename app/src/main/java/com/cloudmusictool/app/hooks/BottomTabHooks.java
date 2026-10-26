package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Config;
import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.HookState;
import com.cloudmusictool.app.dexkit.DexKitAnchors;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.libxposed.api.XposedModule;

/**
 * UI-05 底部导航精简：屏蔽 FM/动态底栏开关，并按保留列表过滤底栏项。
 *
 * 9.6.13 的底栏数据链：
 * <pre>
 *   MainPageTabApiResult
 *     -> tf0.o$a.U(apiResult, fromWhere)   // 数据更新 / 登录刷新
 *     -> tf0.o$a.y(List&lt;BottomTabInfoVO&gt;, Z) // 唯一的 state 列表构建点
 *   MainActivity.Wc -> tf0.o.f2(Z) -> tf0.o$a.z(Z) -> tf0.h.h() -> y(...)  // 主题刷新
 * </pre>
 * 因此真正能删 tab 的入口是 {@code tf0.o$a.y(List, Z)}：在 proceed 前替换
 * 第一个参数，state 与 drawable 列表会一起缩短，不会出现图标错位。
 *
 * 旧的 {@code rh0.c.c}（9.6.13 = rf0.c.c）返回 {@code List<Drawable>}，是
 * drawable 工厂而不是 tab 列表工厂；过滤它只会让 {@code o$a$k} 按完整 state
 * 的下标取错图标，不能隐藏 tab。本类不再 hook 它，只保留 Profile 映射。
 *
 * 9.6.13 运行时使用 Tinker DelegateLastClassLoader，启动阶段的 ClassLoader
 * 与运行时不同，所有类/方法 hook 统一交给 {@link RuntimeHookBootstrap}，
 * 在首个 Activity#onCreate 用运行时 ClassLoader 安装。
 */
public final class BottomTabHooks {

    private BottomTabHooks() {
    }

    /** 只登记 installer 并确保 Activity#onCreate 引导钩子存在。 */
    public static void install(XposedModule module, ClassLoader cl, Config config) {
        RuntimeHookBootstrap.register(Feature.BOTTOM_NAV,
                BottomTabHooks::installRuntimeHooks, config);
        RuntimeHookBootstrap.ensureInstalled(module, cl);
    }

    private static void installRuntimeHooks(XposedModule module, ClassLoader cl, Config config) {
        Feature f = Feature.BOTTOM_NAV;

        // 1. SettingActivity.T8 / U8 (showFMAtBottomTab / showNoteAtBottomTab)
        //    9.6.13 由 Profile 映射到 P8 / O8。
        try {
            Class<?> settingCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.music.biz.setting.activity.SettingActivity");
            if (settingCls != null) {
                Method mT8 = HookKit.findMethodQuiet(settingCls, "T8");
                if (mT8 != null) {
                    HookKit.install(module, f, "SettingActivity#T8", mT8,
                            HookKit.constBool(module, f,
                                    "SettingActivity.T8 (showFMAtBottomTab) -> false", false));
                }
                Method mU8 = HookKit.findMethodQuiet(settingCls, "U8");
                if (mU8 != null) {
                    HookKit.install(module, f, "SettingActivity#U8", mU8,
                            HookKit.constBool(module, f,
                                    "SettingActivity.U8 (showNoteAtBottomTab) -> false", false));
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " SettingActivity hook failed", t);
        }

        // 2. 9.6.13 唯一的 tab 列表构建点：tf0.o$a.y(List<BottomTabInfoVO>, Z)。
        //    首次数据更新（U）与主题刷新（z）都经过这里；在 proceed 前过滤入参，
        //    state 和 drawable 列表一起变短。9.6.08 没有这个类，查找失败只记日志。
        try {
            Class<?> oaCls = HookKit.findClass(module, f, cl, "tf0.o$a");
            if (oaCls != null) {
                Method my = HookKit.findMethod(module, f, oaCls, "y", void.class,
                        new Class<?>[]{List.class, boolean.class});
                if (my != null) {
                    HookKit.install(module, f, "tf0.o$a#y", my, chain -> {
                        HookState.entry(f);
                        try {
                            List<Object> args = chain.getArgs();
                            if (args != null && !args.isEmpty()
                                    && args.get(0) instanceof List) {
                                List<?> original = (List<?>) args.get(0);
                                List<?> filtered = applyBottomTabFilter(module, f, config,
                                        original, "tf0.o$a#y");
                                if (filtered != original) {
                                    Object[] newArgs = args.toArray();
                                    newArgs[0] = filtered;
                                    return chain.proceed(newArgs);
                                }
                            }
                        } catch (Throwable t) {
                            HookKit.error(module, f.id + " intercept error tf0.o$a#y", t);
                        }
                        return chain.proceed();
                    });
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " tf0.o$a#y hook failed", t);
        }

        // 3. 预加载 / 缓存兜底：tf0.h.h() 返回 List<BottomTabInfoVO>
        //    （9.6.08 为 th0.h.h）。主题刷新路径 z() 会直接把它交给 y()，
        //    这里先过滤可以保证缓存列表也不会把隐藏 tab 带回来。
        try {
            Class<?> th0h = HookKit.findClass(module, f, cl, "th0.h", DexKitAnchors.TH0_H);
            if (th0h != null) {
                Method mh = HookKit.findMethodQuiet(th0h, "h");
                if (mh != null) {
                    HookKit.install(module, f, "th0.h#h", mh, chain -> {
                        HookState.entry(f);
                        Object res = chain.proceed();
                        if (res instanceof List) {
                            try {
                                return applyBottomTabFilter(module, f, config,
                                        (List<?>) res, "th0.h#h");
                            } catch (Throwable t) {
                                HookKit.error(module, f.id + " intercept error th0.h#h", t);
                            }
                        }
                        return res;
                    });
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " th0.h hook failed", t);
        }

        // 注意：rf0.c.c / rh0.c.c 不再 hook。它返回 List<Drawable>，过滤会让
        // o$a$k 按完整 state 的下标取到错位图标，不能隐藏 tab。

        HookState.finish(f);
    }

    /** 带日志/命中记账的过滤入口；列表未被缩短时原样返回。 */
    private static List<?> applyBottomTabFilter(XposedModule module, Feature f, Config config,
                                                List<?> list, String source) {
        Set<String> keepSet = config.getFilterSet(Config.KEY_BOTTOM_NAV_KEEP);
        List<?> filtered = filterBottomTabs(list, keepSet);
        if (filtered == list) {
            return list;
        }
        if (filtered.size() == 1 && !tabMatches(filtered.get(0), keepSet)) {
            HookKit.warn(module, f.id + " " + source
                    + ": no tab matched bottom_nav_keep; kept first tab to avoid empty bottom bar");
        }
        HookKit.hit(module, f, source + " filtered from " + list.size()
                + " to " + filtered.size() + " tabs");
        return filtered;
    }

    /**
     * 纯过滤函数：保留 tabCode 或 tabTitle 命中 keepSet 的元素。
     *
     * - keepSet 为 null/空：不过滤，返回原列表（同一实例）；
     * - 没有任何元素被删除：返回原列表（同一实例）；
     * - 全部被删除：保留原列表第一个非 null 元素，避免底栏消失。
     *
     * BottomTabInfoVO 用 getTabCode()/getTabTitle()；tf0.u（NavigationTabUiState）
     * 没有这两个 getter，回退到 g()=tabCode、i()=tabTitle。
     */
    static List<?> filterBottomTabs(List<?> list, Set<String> keepSet) {
        if (list == null || keepSet == null || keepSet.isEmpty()) {
            return list;
        }
        List<Object> out = new ArrayList<>(list.size());
        boolean removed = false;
        for (Object item : list) {
            if (tabMatches(item, keepSet)) {
                out.add(item);
            } else {
                removed = true;
            }
        }
        if (!removed) {
            return list;
        }
        if (out.isEmpty()) {
            for (Object item : list) {
                if (item != null) {
                    out.add(item);
                    break;
                }
            }
            if (out.isEmpty()) {
                return list;
            }
        }
        return out;
    }

    /** tabCode 或 tabTitle 任一命中保留清单即保留。 */
    private static boolean tabMatches(Object item, Set<String> keepSet) {
        if (item == null || keepSet == null || keepSet.isEmpty()) {
            return false;
        }
        return contains(keepSet, tabCode(item)) || contains(keepSet, tabTitle(item));
    }

    private static boolean contains(Set<String> keepSet, String value) {
        return value != null && keepSet.contains(value);
    }

    private static String tabCode(Object item) {
        String code = stringValue(item, "getTabCode");
        // tf0.u 没有 getTabCode()，g() 才是 tabCode。
        return code != null ? code : stringValue(item, "g");
    }

    private static String tabTitle(Object item) {
        String title = stringValue(item, "getTabTitle");
        // tf0.u 没有 getTabTitle()，i() 才是 tabTitle。
        return title != null ? title : stringValue(item, "i");
    }

    private static String stringValue(Object item, String method) {
        Object value = HookKit.invokeNoArg(item, method);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
