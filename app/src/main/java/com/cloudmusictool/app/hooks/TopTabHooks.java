package com.cloudmusictool.app.hooks;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.cloudmusictool.app.Config;
import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.HookState;
import com.cloudmusictool.app.dexkit.DexKitAnchors;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;

/**
 * UI-04 顶部频道管理。
 *
 * 9.6.08 的顶部 tab 由 ViewPager2 + FragmentStateAdapter 驱动：
 *   ArkFragmentV3.Hc -> BaseArkV3Fragment.Tc(list) / hc(list, pager, tabLayout)
 *   -> dr.h.T0(list) 写入适配器数据源 b
 *   -> MusicTabLayout.setupWithViewPager2 按 adapter.getItemCount() 建 tab
 *   -> dr.h.createFragment(position) 用 b.get(position) 建页面
 *
 * 因此真正的删除点是「数据源列表」：把 T0/hc/Tc/Lc 的入参替换成过滤后的新 ArrayList，
 * adapter 的 getItemCount 自然返回保留数，createFragment 的索引也天然是保留索引
 * （b 列表本身就是过滤后的列表，不需要额外的索引映射表）。
 * 另外在 MusicTabLayout.addTab 上兜底跳过隐藏项并移除预创建 TabView。
 */
public final class TopTabHooks {

    /**
     * 数据层是否真的发生过过滤。只有为 true 时 addTab 兜底才允许移除 tab，
     * 避免“keepSet 非空但没有任何标题匹配 → 数据层回退全量 → 兜底把 tab 删光”。
     */
    private static volatile boolean sDataLayerFiltered;
    private static final Set<String> sKeptTitles =
            Collections.synchronizedSet(new HashSet<>());
    private static final AtomicBoolean sEmptyConfigLogged = new AtomicBoolean();
    private static final AtomicBoolean sNoMatchLogged = new AtomicBoolean();

    private TopTabHooks() {
    }

    /**
     * UI-04 的类钩子不能直接挂在启动时的 ClassLoader 上（Tinker 运行时
     * ClassLoader 与启动阶段不同），统一交给 {@link RuntimeHookBootstrap}：
     * 这里只登记 installer 并确保 Activity#onCreate 引导钩子存在。
     */
    public static void install(XposedModule module, ClassLoader cl, Config config) {
        RuntimeHookBootstrap.register(Feature.TOP_CHANNELS,
                TopTabHooks::installRuntimeHooks, config);
        RuntimeHookBootstrap.ensureInstalled(module, cl);
    }

    private static void installRuntimeHooks(XposedModule module, ClassLoader cl, Config config) {
        installFeatureHooks(module, cl, Feature.TOP_CHANNELS, config);
        HookState.finish(Feature.TOP_CHANNELS);
    }

    private static void installFeatureHooks(XposedModule module, ClassLoader cl, Feature f, Config config) {
        // 1. HomeTopTabInfo 解锁删除与拖拽（频道管理页）
        try {
            Class<?> tabInfoCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.discovery.view.module.hometoptab.model.HomeTopTabInfo");
            if (tabInfoCls != null) {
                Method getCanDelete = HookKit.findMethodQuiet(tabInfoCls, "getCanDelete");
                if (getCanDelete != null) {
                    HookKit.install(module, f, "HomeTopTabInfo#getCanDelete", getCanDelete,
                            HookKit.constBool(module, f, "HomeTopTabInfo.getCanDelete -> true", true));
                }
                Method getCanDrag = HookKit.findMethodQuiet(tabInfoCls, "getCanDrag");
                if (getCanDrag != null) {
                    HookKit.install(module, f, "HomeTopTabInfo#getCanDrag", getCanDrag,
                            HookKit.constBool(module, f, "HomeTopTabInfo.getCanDrag -> true", true));
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " HomeTopTabInfo hook failed", t);
        }

        // 2. 旧数据链路（9.6.08 不调用，保留兼容）
        installLegacyDataLinks(module, cl, f, config);

        // 3. 真实数据/渲染链路
        installAdapterDataFilter(module, cl, f, config);
        installTabDataList(module, cl, f, config);
        installV2TabDataList(module, cl, f, config);
        installArkViewDataFilter(module, cl, f, config);
        installTabAddGuard(module, cl, f, config);
    }

    // ------------------------------------------------------------------
    // 旧链路（保留，命中则过滤）
    // ------------------------------------------------------------------

    private static void installLegacyDataLinks(XposedModule module, ClassLoader cl, Feature f, Config config) {
        String[] tabExtraClasses = {
                "com.netease.cloudmusic.discovery.view.module.hometoptab.model.TabExtra",
                "com.netease.cloudmusic.discovery.view.newframework.model.TabExtra"
        };
        for (String clsName : tabExtraClasses) {
            try {
                Class<?> tabExtraCls = HookKit.findClass(module, f, cl, clsName);
                if (tabExtraCls != null) {
                    Method getNowChannelItems = HookKit.findMethodQuiet(tabExtraCls, "getNowChannelItems");
                    if (getNowChannelItems != null) {
                        HookKit.install(module, f, tabExtraCls.getSimpleName() + "#getNowChannelItems",
                                getNowChannelItems, chain -> {
                                    Object res = chain.proceed();
                                    try {
                                        return filterChannelList(module, f, config, res,
                                                tabExtraCls.getSimpleName() + "#getNowChannelItems");
                                    } catch (Throwable t) {
                                        HookKit.error(module, f.id + " intercept error getNowChannelItems", t);
                                    }
                                    return res;
                                });
                    }
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " " + clsName + " hook failed", t);
            }
        }

        String[] dtoNames = {
                "com.netease.cloudmusic.discovery.view.module.hometoptab.model.UserSettingDTO",
                "com.netease.cloudmusic.discovery.view.module.hometoptab.v2.UserSettingDTO"
        };
        for (String dtoName : dtoNames) {
            try {
                Class<?> dtoCls = HookKit.findClass(module, f, cl, dtoName);
                if (dtoCls != null) {
                    Method getCurrentRes = HookKit.findMethodQuiet(dtoCls, "getCurrentRes");
                    if (getCurrentRes != null) {
                        HookKit.install(module, f, dtoCls.getSimpleName() + "#getCurrentRes", getCurrentRes,
                                chain -> {
                                    Object res = chain.proceed();
                                    try {
                                        return filterChannelList(module, f, config, res,
                                                dtoCls.getSimpleName() + "#getCurrentRes");
                                    } catch (Throwable t) {
                                        HookKit.error(module, f.id + " intercept error getCurrentRes", t);
                                    }
                                    return res;
                                });
                    }
                }
            } catch (Throwable t) {
                HookKit.error(module, f.id + " " + dtoName + " hook failed", t);
            }
        }
    }

    // ------------------------------------------------------------------
    // 真实适配器：dr.h.T0(List) 是数据源唯一写入口
    // ------------------------------------------------------------------

    private static void installAdapterDataFilter(XposedModule module, ClassLoader cl, Feature f, Config config) {
        try {
            Class<?> drh = HookKit.findClass(module, f, cl, "dr.h", DexKitAnchors.DR_H);
            if (drh == null) {
                return;
            }
            Method t0 = HookKit.findMethodQuiet(drh, "T0", List.class);
            if (t0 == null) {
                // 9.6.13: T0 -> O0（Profile 映射），兜底扫描也用映射后的名字。
                t0 = HookKit.findByNameAndArity(drh, HookKit.mappedMethodName(drh, "T0"), 1);
            }
            if (t0 != null) {
                HookKit.install(module, f, "dr.h#T0", t0, chain -> {
                    try {
                        Object[] newArgs = filterFirstListArg(module, f, config, chain.getArgs(), "dr.h#T0");
                        if (newArgs != null) {
                            return chain.proceed(newArgs);
                        }
                    } catch (Throwable t) {
                        HookKit.error(module, f.id + " intercept error dr.h#T0", t);
                    }
                    return chain.proceed();
                });
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " dr.h hook failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 数据/渲染入口：getTabDataList 返回新列表；Tc/Lc/hc 替换入参
    // ------------------------------------------------------------------

    public static void installTabDataList(XposedModule module, ClassLoader cl, Feature f, Config config) {
        try {
            Class<?> fragCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.discovery.view.newframework.BaseArkV3Fragment");
            if (fragCls == null) {
                return;
            }
            Method getTabDataList = HookKit.findMethodQuiet(fragCls, "getTabDataList");
            if (getTabDataList != null) {
                HookKit.install(module, f, "BaseArkV3Fragment#getTabDataList", getTabDataList, chain -> {
                    Object res = chain.proceed();
                    try {
                        if (res instanceof List) {
                            List<Object> out = filterList(module, f, config, (List<?>) res, "getTabDataList");
                            if (out != null) {
                                return out;
                            }
                        }
                    } catch (Throwable t) {
                        HookKit.error(module, f.id + " intercept error getTabDataList", t);
                    }
                    return res;
                });
            }

            String[] listMethods = {"Tc", "Lc", "hc"};
            for (String mName : listMethods) {
                // 9.6.13 的方法名已漂移（Tc->Pc / Lc->Hc / hc->dc），
                // 先经 Profile 把请求的旧名映射成实际名再扫描；标签保留旧名，
                // 与 Profile/HookState 的挂载点 id 保持一致。
                String actual = HookKit.mappedMethodName(fragCls, mName);
                for (Method m : fragCls.getDeclaredMethods()) {
                    if (!m.getName().equals(actual)) {
                        continue;
                    }
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == 0 || pts[0] != List.class) {
                        continue;
                    }
                    m.setAccessible(true);
                    final String label = mName + "(" + pts.length + ")";
                    HookKit.install(module, f, "BaseArkV3Fragment#" + label, m, chain -> {
                        try {
                            Object[] newArgs = filterFirstListArg(module, f, config,
                                    chain.getArgs(), "BaseArkV3Fragment#" + label);
                            if (newArgs != null) {
                                return chain.proceed(newArgs);
                            }
                        } catch (Throwable t) {
                            HookKit.error(module, f.id + " intercept error " + label, t);
                        }
                        return chain.proceed();
                    });
                }
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " BaseArkV3Fragment hook failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 9.6.13 旧框架首页：ArkFragmentV2 + DiscoveryTabInfo
    // ------------------------------------------------------------------

    /**
     * 9.6.13 还存在 ArkFragmentV2 + DiscoveryTabInfo 的旧框架链路
     * （AB 或其他构建可能使用）。本机真机首页实际走 V3，但 V2 的
     * getTabDataList()/getTabData() 返回 List&lt;DiscoveryTabInfo&gt;，
     * 元素继承 DiscoveryBaseTabInfo 的 getTitle/getCode，可直接复用
     * filterList，作为并行覆盖保留。
     */
    private static void installV2TabDataList(XposedModule module, ClassLoader cl, Feature f, Config config) {
        try {
            Class<?> fragCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.discovery.view.newframework.ArkFragmentV2");
            if (fragCls == null) {
                return;
            }
            String[] methods = {"getTabDataList", "getTabData"};
            for (String methodName : methods) {
                Method m = HookKit.findMethodQuiet(fragCls, methodName);
                if (m == null) {
                    continue;
                }
                final String label = "ArkFragmentV2#" + methodName;
                HookKit.install(module, f, label, m, chain -> {
                    Object res = chain.proceed();
                    try {
                        if (res instanceof List) {
                            List<Object> out = filterList(module, f, config, (List<?>) res, label);
                            if (out != null) {
                                return out;
                            }
                        }
                    } catch (Throwable t) {
                        HookKit.error(module, f.id + " intercept error " + label, t);
                    }
                    return res;
                });
            }
        } catch (Throwable t) {
            HookKit.error(module, f.id + " ArkFragmentV2 hook failed", t);
        }
    }

    // ------------------------------------------------------------------
    // 9.6 首页真实渲染链路：arkview.ArkFragment + HomeTopTabInfo
    // ------------------------------------------------------------------

    /**
     * arkview.ArkFragment 是另一套可选首页容器：
     * refreshData/refreshCGroupData 先把 HomeTopTabGetResult 里的
     * nowChannelItems 过滤成 List&lt;HomeTopTabInfo&gt;，再统一交给
     * refreshAdapterData(List) 建 ViewPager2 与 tab。
     *
     * 本机 9.6.13 真机首页走 ArkFragmentV3WithQuickListen/BaseArkV3Fragment，
     * 未命中这条链路；保留覆盖，防止 AB 或其他构建切换容器。
     */
    private static void installArkViewDataFilter(XposedModule module, ClassLoader cl, Feature f, Config config) {
        try {
            Class<?> fragCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.discovery.view.arkview.ArkFragment");
            if (fragCls == null) {
                return;
            }
            Method refresh = HookKit.findMethodQuiet(fragCls, "refreshAdapterData", List.class);
            if (refresh == null) {
                return;
            }
            HookKit.install(module, f, "arkview.ArkFragment#refreshAdapterData", refresh, chain -> {
                try {
                    Object[] newArgs = filterFirstListArg(module, f, config, chain.getArgs(),
                            "arkview.ArkFragment#refreshAdapterData");
                    if (newArgs != null) {
                        return chain.proceed(newArgs);
                    }
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " intercept error arkview.refreshAdapterData", t);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            HookKit.error(module, f.id + " arkview.ArkFragment hook failed", t);
        }
    }

    // ------------------------------------------------------------------
    // MusicTabLayout.addTab 兜底：跳过隐藏项并移除预创建 TabView
    // ------------------------------------------------------------------

    private static void installTabAddGuard(XposedModule module, ClassLoader cl, Feature f, Config config) {
        try {
            Class<?> layoutCls = HookKit.findClass(module, f, cl,
                    "com.netease.cloudmusic.theme.ui.tab.MusicTabLayout");
            if (layoutCls == null) {
                return;
            }
            // 只 hook 最底层实现，避免外层重载重复移除：
            // addTab(k) / addTab(k,Z) / addTab(k,I) 最终都会走到 addTab(k,I,Z)。
            Method target = null;
            for (Method m : layoutCls.getDeclaredMethods()) {
                if (!m.getName().equals("addTab")) {
                    continue;
                }
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 0 || !pts[0].getName().endsWith("MusicTabLayout$k")) {
                    // 跳过 MusicTabLayout$b 等非 $k 首参重载。
                    continue;
                }
                if (pts.length == 3 && pts[1] == int.class && pts[2] == boolean.class) {
                    target = m;
                    break;
                }
                if (target == null && pts.length == 2 && pts[1] == boolean.class) {
                    target = m;
                } else if (target == null && pts.length == 1) {
                    target = m;
                }
            }
            if (target == null) {
                return;
            }
            target.setAccessible(true);
            final String label = "addTab(" + target.getParameterTypes().length + ")";
            HookKit.install(module, f, "MusicTabLayout#" + label, target, chain -> {
                Object res = chain.proceed();
                try {
                    if (!sDataLayerFiltered) {
                        return res;
                    }
                    List<Object> args = chain.getArgs();
                    if (args == null || args.isEmpty() || args.get(0) == null) {
                        return res;
                    }
                    Object tab = args.get(0);
                    String title = tabTitle(tab);
                    if (title == null) {
                        return res;
                    }
                    boolean kept;
                    synchronized (sKeptTitles) {
                        kept = sKeptTitles.isEmpty() || sKeptTitles.contains(title);
                    }
                    if (kept) {
                        return res;
                    }
                    Object layout = chain.getThisObject();
                    // 任何情况下都不移除最后一个 tab。
                    if (getTabCount(layout) <= 1) {
                        return res;
                    }
                    // 先移除预创建 View，再从 tabs 列表移除，避免空槽位残留。
                    removeTabView(tab);
                    removeTab(layout, tab);
                    HookKit.hit(module, f, label + " removed pre-created '" + title + "'");
                } catch (Throwable t) {
                    HookKit.error(module, f.id + " intercept error MusicTabLayout#" + label, t);
                }
                return res;
            });
        } catch (Throwable t) {
            HookKit.error(module, f.id + " MusicTabLayout hook failed", t);
        }
    }

    private static int getTabCount(Object layout) {
        Object v = HookKit.invokeNoArg(layout, "getTabCount");
        return v instanceof Number ? ((Number) v).intValue() : 0;
    }

    /** 读取 MusicTabLayout$k 的标题（t()/o() 返回 CharSequence）。 */
    private static String tabTitle(Object tab) {
        if (tab == null) {
            return null;
        }
        Object v = HookKit.invokeNoArg(tab, "t");
        if (v == null) {
            v = HookKit.invokeNoArg(tab, "o");
        }
        if (v == null) {
            v = HookKit.invokeNoArg(tab, "getText");
        }
        return v == null ? null : v.toString().trim();
    }

    /** 调用 MusicTabLayout.removeTab(k)，移除已添加的 tab。 */
    private static void removeTab(Object layout, Object tab) {
        if (layout == null || tab == null) {
            return;
        }
        try {
            for (Method m : layout.getClass().getMethods()) {
                if (!m.getName().equals("removeTab") || m.getParameterCount() != 1) {
                    continue;
                }
                if (!m.getParameterTypes()[0].isInstance(tab)) {
                    continue;
                }
                m.setAccessible(true);
                m.invoke(layout, tab);
                return;
            }
        } catch (Throwable ignored) {
        }
    }

    /** 移除 k 关联的预创建 TabView，避免留下空槽位。 */
    private static void removeTabView(Object tab) {
        try {
            Field r = HookKit.findField(tab.getClass(), "r");
            if (r == null) {
                return;
            }
            r.setAccessible(true);
            Object view = r.get(tab);
            if (!(view instanceof View)) {
                return;
            }
            View v = (View) view;
            v.setVisibility(View.GONE);
            v.setClickable(false);
            v.setEnabled(false);
            ViewParent parent = v.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(v);
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // 过滤工具
    // ------------------------------------------------------------------

    /**
     * 过滤列表并返回新 ArrayList；配置为空 / 过滤后为空 / 无变化时返回 null（调用方保持原逻辑）。
     */
    private static List<Object> filterList(XposedModule module, Feature f, Config config,
                                           List<?> list, String source) {
        Set<String> keepSet = config.getFilterSet(Config.KEY_TOP_CHANNELS_KEEP);
        if (keepSet == null || keepSet.isEmpty()) {
            if (sEmptyConfigLogged.compareAndSet(false, true)) {
                HookKit.info(module, f.id + " top_channels_keep is empty; filtering skipped");
            }
            return null;
        }
        if (list == null || list.isEmpty()) {
            return null;
        }
        List<Object> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (HookKit.shouldKeepItem(item, keepSet)) {
                out.add(item);
            }
        }
        if (out.isEmpty()) {
            if (sNoMatchLogged.compareAndSet(false, true)) {
                HookKit.warn(module, f.id + " keepSet=" + keepSet
                        + " matched no tab title; sample=" + sampleTitles(list));
            }
            return null;
        }
        if (out.size() >= list.size()) {
            return null;
        }
        // 记录“数据层确实过滤过”以及保留标题；addTab 兜底只在此基础上工作。
        Set<String> kept = new HashSet<>();
        for (Object item : out) {
            String title = HookKit.firstStringGetter(item);
            if (title != null) {
                kept.add(title);
            }
        }
        synchronized (sKeptTitles) {
            sKeptTitles.clear();
            sKeptTitles.addAll(kept);
        }
        sDataLayerFiltered = true;
        HookKit.hit(module, f, source + " filtered from " + list.size() + " to " + out.size());
        return out;
    }

    private static String sampleTitles(List<?> list) {
        StringBuilder sb = new StringBuilder("[");
        int limit = Math.min(list.size(), 8);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(HookKit.firstStringGetter(list.get(i)));
        }
        return sb.append(']').toString();
    }

    /** 替换第一个 List 参数；无变化返回 null。 */
    private static Object[] filterFirstListArg(XposedModule module, Feature f, Config config,
                                               List<Object> args, String source) {
        if (args == null || args.isEmpty() || !(args.get(0) instanceof List)) {
            return null;
        }
        List<Object> out = filterList(module, f, config, (List<?>) args.get(0), source);
        if (out == null) {
            return null;
        }
        Object[] newArgs = args.toArray();
        newArgs[0] = out;
        return newArgs;
    }

    /** 旧链路的返回值过滤（返回新列表）。 */
    private static Object filterChannelList(XposedModule module, Feature f, Config config,
                                            Object res, String source) {
        if (!(res instanceof List)) {
            return res;
        }
        List<Object> out = filterList(module, f, config, (List<?>) res, source);
        return out != null ? out : res;
    }
}
