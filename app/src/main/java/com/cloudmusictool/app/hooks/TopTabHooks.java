package com.cloudmusictool.app.hooks;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import com.cloudmusictool.app.Config;
import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.HookState;
import com.cloudmusictool.app.dexkit.DexKitAnchors;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
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
    /**
     * 数据层本次真正删掉的频道标题。addTab 兜底只允许移除这些标题，不能按
     * 「不在保留清单里」一刀切：「我的音乐」等页面也共用 MusicTabLayout，
     * 误删它们的 tab 会让 TabLayout 子 View 数与 PagerAdapter 页数不一致，
     * populateFromPagerAdapter 里会抛 IndexOutOfBoundsException（issue #5）。
     */
    private static final Set<String> sRemovedTitles =
            Collections.synchronizedSet(new HashSet<>());

    /**
     * 首页顶部 tab 容器实例。MusicTabLayout 是全局共用类，「我的音乐」等页面
     * 也会用它装配自己的 tab；addTab 兜底必须只作用于首页这一个实例，
     * 否则会把其它页面的 tab 删掉，导致 TabLayout 与 PagerAdapter 数量不一致。
     */
    private static volatile WeakReference<Object> sHomeTabLayout =
            new WeakReference<>(null);
    private static final AtomicBoolean sEmptyConfigLogged = new AtomicBoolean();
    private static final AtomicBoolean sNoMatchLogged = new AtomicBoolean();
    /** tab 移除失败的 warning-once：removeTab / removeTabView 都是 void，失败必须留痕。 */
    private static final AtomicBoolean sRemoveFailedLogged = new AtomicBoolean();

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
                Method getCanDelete = HookKit.findMethodQuiet(module, f,
                        "HomeTopTabInfo#getCanDelete", tabInfoCls, "getCanDelete");
                if (getCanDelete != null) {
                    HookKit.install(module, f, "HomeTopTabInfo#getCanDelete", getCanDelete,
                            HookKit.constBool(module, f, "HomeTopTabInfo.getCanDelete -> true", true));
                }
                Method getCanDrag = HookKit.findMethodQuiet(module, f,
                        "HomeTopTabInfo#getCanDrag", tabInfoCls, "getCanDrag");
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
                    Method getNowChannelItems = HookKit.findMethodQuiet(module, f,
                            tabExtraCls.getSimpleName() + "#getNowChannelItems",
                            tabExtraCls, "getNowChannelItems");
                    if (getNowChannelItems != null) {
                        HookKit.install(module, f, tabExtraCls.getSimpleName() + "#getNowChannelItems",
                                getNowChannelItems, chain -> {
                                    Object res = chain.proceed();
                                    try {
                                        return filterChannelList(module, f,
                                                config.getFilterSet(Config.KEY_TOP_CHANNELS_KEEP), res,
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
                    Method getCurrentRes = HookKit.findMethodQuiet(module, f,
                            dtoCls.getSimpleName() + "#getCurrentRes", dtoCls, "getCurrentRes");
                    if (getCurrentRes != null) {
                        HookKit.install(module, f, dtoCls.getSimpleName() + "#getCurrentRes", getCurrentRes,
                                chain -> {
                                    Object res = chain.proceed();
                                    try {
                                        return filterChannelList(module, f,
                                                config.getFilterSet(Config.KEY_TOP_CHANNELS_KEEP), res,
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
            Method t0 = HookKit.findMethodQuiet(module, f, "dr.h#T0", drh, "T0", List.class);
            if (t0 == null) {
                // 9.6.13: T0 -> O0（Profile 映射），兜底扫描也用映射后的名字。
                t0 = HookKit.findByNameAndArity(module, f, "dr.h#T0", drh,
                        HookKit.mappedMethodName(drh, "T0"), 1);
            }
            if (t0 != null) {
                // proceed 只调用一次：先算过滤后的入参，再放行宿主方法。
                HookKit.install(module, f, "dr.h#T0", t0, chain -> interceptFilteredArgs(module, f,
                        config.getFilterSet(Config.KEY_TOP_CHANNELS_KEEP), chain, "dr.h#T0"));
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
            Method getTabDataList = HookKit.findMethodQuiet(module, f,
                    "BaseArkV3Fragment#getTabDataList", fragCls, "getTabDataList");
            if (getTabDataList != null) {
                HookKit.install(module, f, "BaseArkV3Fragment#getTabDataList", getTabDataList, chain -> {
                    Object res = chain.proceed();
                    try {
                        if (res instanceof List) {
                            List<Object> out = filterList(module, f,
                                    config.getFilterSet(Config.KEY_TOP_CHANNELS_KEEP), (List<?>) res,
                                    "getTabDataList");
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
                        // hc(list, pager, tabLayout) 是首页 tab 容器的唯一装配点：
                        // 记下这个 MusicTabLayout 实例，addTab 兜底只允许作用于它。
                        if ("hc".equals(mName)) {
                            rememberHomeTabLayout(chain.getArgs());
                        }
                        return interceptFilteredArgs(module, f,
                                config.getFilterSet(Config.KEY_TOP_CHANNELS_KEEP), chain,
                                "BaseArkV3Fragment#" + label);
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
                Method m = HookKit.findMethodQuiet(module, f, "ArkFragmentV2#" + methodName,
                        fragCls, methodName);
                if (m == null) {
                    continue;
                }
                final String label = "ArkFragmentV2#" + methodName;
                HookKit.install(module, f, label, m, chain -> {
                    Object res = chain.proceed();
                    try {
                        if (res instanceof List) {
                            List<Object> out = filterList(module, f,
                                    config.getFilterSet(Config.KEY_TOP_CHANNELS_KEEP), (List<?>) res, label);
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
            Method refresh = HookKit.findMethodQuiet(module, f,
                    "arkview.ArkFragment#refreshAdapterData", fragCls, "refreshAdapterData", List.class);
            if (refresh == null) {
                return;
            }
            HookKit.install(module, f, "arkview.ArkFragment#refreshAdapterData", refresh,
                    chain -> interceptFilteredArgs(module, f,
                            config.getFilterSet(Config.KEY_TOP_CHANNELS_KEEP), chain,
                            "arkview.ArkFragment#refreshAdapterData"));
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
                    if (!shouldRemoveTab(title)) {
                        return res;
                    }
                    Object layout = chain.getThisObject();
                    // 只处理首页 tab 容器：MusicTabLayout 也被「我的音乐」等页面复用。
                    if (!isHomeTabLayout(layout)) {
                        return res;
                    }
                    // 任何情况下都不移除最后一个 tab。
                    if (getTabCount(layout) <= 1) {
                        return res;
                    }
                    // 先移除预创建 View，再从 tabs 列表移除，避免空槽位残留。
                    boolean viewRemoved = removeTabView(tab);
                    boolean tabRemoved = removeTab(layout, tab);
                    if (tabRemoved) {
                        HookKit.hit(module, f, label + " removed pre-created '" + title
                                + "' (view=" + viewRemoved + ")");
                    } else if (sRemoveFailedLogged.compareAndSet(false, true)) {
                        // 两个移除都是 void 且可能静默失败；不能再记成「removed」，
                        // 否则空槽位/多出来的 tab 在诊断里无从发现。
                        HookKit.warn(module, f.id + " " + label + " could not remove tab '"
                                + title + "' (view=" + viewRemoved + ")");
                    }
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

    /** 调用 MusicTabLayout.removeTab(k)，返回是否真的调用到宿主的移除方法。 */
    private static boolean removeTab(Object layout, Object tab) {
        if (layout == null || tab == null) {
            return false;
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
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 移除 k 关联的预创建 TabView，返回是否找到了 TabView 并解除交互。 */
    private static boolean removeTabView(Object tab) {
        try {
            Field r = HookKit.findField(tab.getClass(), "r");
            if (r == null) {
                return false;
            }
            r.setAccessible(true);
            Object view = r.get(tab);
            if (!(view instanceof View)) {
                return false;
            }
            View v = (View) view;
            v.setVisibility(View.GONE);
            v.setClickable(false);
            v.setEnabled(false);
            ViewParent parent = v.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(v);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 过滤工具
    // ------------------------------------------------------------------

    /**
     * 过滤入参后只调用一次 proceed()：宿主方法的异常记录后原样抛回。
     *
     * 旧写法把 proceed 放在 try 里、catch 再补一次，宿主方法抛异常时会被执行两遍
     * （第二次还用未过滤的原始入参）。三个「替换入参」的挂载点共用此实现。
     */
    static Object interceptFilteredArgs(XposedModule module, Feature f, Set<String> keepSet,
                                        XposedInterface.Chain chain, String label) throws Throwable {
        Object[] newArgs = null;
        try {
            newArgs = filterFirstListArg(module, f, keepSet, chain.getArgs(), label);
        } catch (Throwable t) {
            HookKit.error(module, f.id + " intercept error " + label, t);
        }
        // 两个分支互斥：每次执行只会 proceed 一次。
        try {
            return newArgs != null ? chain.proceed(newArgs) : chain.proceed();
        } catch (Throwable t) {
            HookKit.error(module, f.id + " proceed error " + label, t);
            throw t;
        }
    }

    /**
     * 数据层过滤决策（包内可见，便于单测）。
     *
     * filtered == null 表示本次不改变数据层，调用方沿用原列表。只要本次没有真正删掉
     * 任何项（配置为空 / 空列表 / 无匹配 / 无变化），就必须清掉上一轮记录的删除标题与
     * sDataLayerFiltered：否则宿主回退全量数据后，addTab 兜底仍按旧标题删 tab，
     * tab 栏与 ViewPager2 的页数/下标不再一致。
     */
    static FilterDecision decideFilter(List<?> list, Set<String> keepSet) {
        boolean emptyConfig = keepSet == null || keepSet.isEmpty();
        if (emptyConfig || list == null || list.isEmpty()) {
            resetFilterState();
            return new FilterDecision(null, false, emptyConfig);
        }
        List<Object> out = new ArrayList<>(list.size());
        Set<String> removed = new HashSet<>();
        for (Object item : list) {
            if (HookKit.shouldKeepItem(item, keepSet)) {
                out.add(item);
            } else {
                String title = HookKit.firstStringGetter(item);
                if (title != null) {
                    removed.add(title);
                }
            }
        }
        if (out.isEmpty()) {
            resetFilterState();
            return new FilterDecision(null, true, false);
        }
        if (out.size() >= list.size()) {
            resetFilterState();
            return new FilterDecision(null, false, false);
        }
        // 记录“数据层确实过滤过”以及真正删掉的标题；addTab 兜底只在此基础上工作。
        synchronized (sRemovedTitles) {
            sRemovedTitles.clear();
            sRemovedTitles.addAll(removed);
        }
        sDataLayerFiltered = true;
        return new FilterDecision(out, false, false);
    }

    /** decideFilter 的结果：filtered == null 表示不改变数据层。 */
    static final class FilterDecision {
        final List<Object> filtered;
        final boolean noMatch;
        final boolean emptyConfig;

        FilterDecision(List<Object> filtered, boolean noMatch, boolean emptyConfig) {
            this.filtered = filtered;
            this.noMatch = noMatch;
            this.emptyConfig = emptyConfig;
        }
    }

    /** 数据层本次没有过滤：清掉上一轮的兜底状态，避免 addTab 按旧标题继续删 tab。 */
    private static void resetFilterState() {
        sDataLayerFiltered = false;
        synchronized (sRemovedTitles) {
            sRemovedTitles.clear();
        }
    }

    /**
     * addTab 兜底是否应该移除这个 tab：只认本次数据层真正删掉的标题。
     * 不能用「不在保留清单里」判断——其它页面（我的音乐等）的 tab 标题
     * 本来就不在清单里，误删会导致 TabLayout 与 PagerAdapter 数量不一致。
     */
    static boolean shouldRemoveTab(String title) {
        if (!sDataLayerFiltered || title == null) {
            return false;
        }
        synchronized (sRemovedTitles) {
            return sRemovedTitles.contains(title);
        }
    }

    /** 从 hc(list, pager, tabLayout) 的入参里记下首页 MusicTabLayout 实例。 */
    private static void rememberHomeTabLayout(List<Object> args) {
        if (args == null) {
            return;
        }
        for (Object arg : args) {
            if (arg != null && arg.getClass().getName().endsWith("MusicTabLayout")) {
                sHomeTabLayout = new WeakReference<>(arg);
                return;
            }
        }
    }

    /** addTab 兜底只允许作用于首页 tab 容器，其它页面的 MusicTabLayout 一律放行。 */
    static boolean isHomeTabLayout(Object layout) {
        return layout != null && sHomeTabLayout.get() == layout;
    }

    /**
     * 过滤列表并返回新 ArrayList；配置为空 / 过滤后为空 / 无变化时返回 null（调用方保持原逻辑）。
     */
    private static List<Object> filterList(XposedModule module, Feature f, Set<String> keepSet,
                                           List<?> list, String source) {
        FilterDecision decision = decideFilter(list, keepSet);
        if (decision.filtered == null) {
            if (decision.emptyConfig) {
                if (sEmptyConfigLogged.compareAndSet(false, true)) {
                    HookKit.info(module, f.id + " top_channels_keep is empty; filtering skipped");
                }
            } else if (decision.noMatch) {
                if (sNoMatchLogged.compareAndSet(false, true)) {
                    HookKit.warn(module, f.id + " keepSet=" + keepSet
                            + " matched no tab title; sample=" + sampleTitles(list));
                }
            }
            return null;
        }
        HookKit.hit(module, f, source + " filtered from " + list.size()
                + " to " + decision.filtered.size());
        return decision.filtered;
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
    private static Object[] filterFirstListArg(XposedModule module, Feature f, Set<String> keepSet,
                                               List<Object> args, String source) {
        if (args == null || args.isEmpty() || !(args.get(0) instanceof List)) {
            return null;
        }
        List<Object> out = filterList(module, f, keepSet, (List<?>) args.get(0), source);
        if (out == null) {
            return null;
        }
        Object[] newArgs = args.toArray();
        newArgs[0] = out;
        return newArgs;
    }

    /** 旧链路的返回值过滤（返回新列表）。 */
    private static Object filterChannelList(XposedModule module, Feature f, Set<String> keepSet,
                                            Object res, String source) {
        if (!(res instanceof List)) {
            return res;
        }
        List<Object> out = filterList(module, f, keepSet, (List<?>) res, source);
        return out != null ? out : res;
    }
}
