package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * UI-04 顶部频道：
 *
 * 1) 替换入参的挂载点（dr.h#T0 / Tc·Lc·hc / refreshAdapterData）只 proceed 一次，
 *    宿主方法抛异常时原样抛回，不再用第二次 proceed 覆盖；
 * 2) 数据层回退全量时（配置为空 / 无匹配 / 无变化）必须清掉上一轮的删除标题，
 *    否则 addTab 兜底会按旧标题删 tab，tab 栏与 ViewPager2 页数不一致；
 * 3) removeTab / removeTabView 必须报告是否真的移除，日志不能再无条件记成 removed。
 */
public class TopTabHooksTest {

    private final HookTestSupport.FakeModule module = new HookTestSupport.FakeModule();

    @Before
    public void resetStaticFilterState() {
        // sDataLayerFiltered / sKeptTitles 是进程级静态状态：每个用例先走一次复位入口。
        TopTabHooks.decideFilter(null, Collections.<String>emptySet());
    }

    // ---------------------------------------------------------------- id 23

    @Test
    public void filteredArgs_proceedsOnceWithFilteredList() throws Throwable {
        Item home = new Item("home");
        Item mine = new Item("mine");
        Item fm = new Item("fm");
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(null);
        chain.args.add(new ArrayList<>(Arrays.asList(home, mine, fm)));

        Object out = TopTabHooks.interceptFilteredArgs(module, Feature.TOP_CHANNELS,
                set("mine"), chain, "dr.h#T0");

        assertEquals("host-result", out);
        assertEquals(1, chain.proceedCount);
        List<?> passed = (List<?>) chain.lastArgs[0];
        assertEquals(1, passed.size());
        assertSame(mine, passed.get(0));
    }

    @Test
    public void filteredArgs_nothingRemovedProceedsOnceWithOriginalArgs() throws Throwable {
        List<Object> original = new ArrayList<>(Arrays.asList(new Item("home"), new Item("mine")));
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(null);
        chain.args.add(original);

        TopTabHooks.interceptFilteredArgs(module, Feature.TOP_CHANNELS, set("home", "mine"),
                chain, "dr.h#T0");

        assertEquals(1, chain.proceedCount);
        assertSame("没有变化时必须沿用原入参", original, chain.lastArgs[0]);
    }

    @Test
    public void filteredArgs_hostExceptionPropagatesAfterSingleProceed() {
        RuntimeException boom = new RuntimeException("host T0 failed");
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(null);
        chain.args.add(new ArrayList<>(Collections.singletonList(new Item("home"))));
        chain.failure = boom;

        try {
            TopTabHooks.interceptFilteredArgs(module, Feature.TOP_CHANNELS, set("home"),
                    chain, "dr.h#T0");
            fail("宿主 T0 的异常必须原样抛回");
        } catch (Throwable t) {
            assertSame("第二次 proceed 会覆盖首个异常", boom, t);
        }
        assertEquals(1, chain.proceedCount);
    }

    @Test
    public void filteredArgs_filterFailureStillProceedsExactlyOnce() throws Throwable {
        List<Object> exploding = explodingList();
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(null);
        chain.args.add(exploding);

        assertEquals("host-result", TopTabHooks.interceptFilteredArgs(module,
                Feature.TOP_CHANNELS, set("mine"), chain, "dr.h#T0"));
        assertEquals("过滤异常后只能补一次 proceed", 1, chain.proceedCount);
        assertSame(exploding, chain.lastArgs[0]);
    }

    // ---------------------------------------------------------------- id 24

    @Test
    public void decideFilter_recordsRemovedTitlesWhenItemsRemoved() throws Exception {
        TopTabHooks.FilterDecision decision = TopTabHooks.decideFilter(
                Arrays.asList(new Item("home"), new Item("mine")), set("mine"));

        assertEquals(1, decision.filtered.size());
        assertTrue(dataLayerFiltered());
        assertEquals(Collections.singleton("home"), removedTitles());
        assertTrue(TopTabHooks.shouldRemoveTab("home"));
        assertFalse("保留的标题不能被 addTab 兜底移除", TopTabHooks.shouldRemoveTab("mine"));
        assertFalse("其它页面的 tab（如我的音乐「声音」）必须放行",
                TopTabHooks.shouldRemoveTab("声音"));
        assertFalse(TopTabHooks.shouldRemoveTab(null));
    }

    @Test
    public void decideFilter_noMatchAfterSuccessfulFilterClearsStaleTitles() throws Exception {
        TopTabHooks.decideFilter(Arrays.asList(new Item("home"), new Item("mine")), set("mine"));
        assertTrue(dataLayerFiltered());

        // 宿主频道管理页删掉保留频道后：数据层回退全量，兜底不能再按旧标题删 tab。
        TopTabHooks.FilterDecision decision = TopTabHooks.decideFilter(
                Arrays.asList(new Item("home"), new Item("fm")), set("mine"));

        assertNull(decision.filtered);
        assertTrue(decision.noMatch);
        assertFalse("数据层已回退全量，addTab 兜底必须失效", dataLayerFiltered());
        assertTrue("上一轮记录的删除标题必须清空", removedTitles().isEmpty());
        assertFalse(TopTabHooks.shouldRemoveTab("home"));
    }

    @Test
    public void decideFilter_emptyConfigClearsStaleTitles() throws Exception {
        TopTabHooks.decideFilter(Arrays.asList(new Item("home"), new Item("mine")), set("mine"));

        TopTabHooks.FilterDecision decision = TopTabHooks.decideFilter(
                Arrays.asList(new Item("home"), new Item("mine")), Collections.<String>emptySet());

        assertNull(decision.filtered);
        assertTrue(decision.emptyConfig);
        assertFalse(dataLayerFiltered());
        assertTrue(removedTitles().isEmpty());
    }

    @Test
    public void decideFilter_noChangeClearsStaleTitles() throws Exception {
        TopTabHooks.decideFilter(Arrays.asList(new Item("home"), new Item("mine")), set("mine"));

        TopTabHooks.FilterDecision decision = TopTabHooks.decideFilter(
                Arrays.asList(new Item("home"), new Item("mine")), set("home", "mine"));

        assertNull(decision.filtered);
        assertFalse("没有删掉任何项时数据层不算过滤过", dataLayerFiltered());
        assertTrue(removedTitles().isEmpty());
    }

    @Test
    public void homeTabLayoutScope_onlyRememberedInstanceIsEligible() throws Exception {
        Object home = new FakeMusicTabLayout();
        rememberHomeTabLayout(Collections.<Object>singletonList(home));

        assertTrue(TopTabHooks.isHomeTabLayout(home));
        assertFalse("其它页面的 MusicTabLayout 不能被当成首页",
                TopTabHooks.isHomeTabLayout(new FakeMusicTabLayout()));
        assertFalse(TopTabHooks.isHomeTabLayout(null));
    }

    // ---------------------------------------------------------------- id 50

    @Test
    public void removeTab_reportsWhetherHostMethodWasInvoked() throws Exception {
        FakeLayout layout = new FakeLayout();
        Item tab = new Item("mine");

        assertTrue("调用到宿主 removeTab 才算移除成功", removeTab(layout, tab));
        assertSame(tab, layout.removed);
        assertFalse("没有 removeTab 时不能报告成功", removeTab(new Object(), tab));
        assertFalse(removeTab(null, tab));
    }

    @Test
    public void removeTabView_reportsFalseWhenPreCreatedFieldMissing() throws Exception {
        assertFalse("字段 r 缺失时不能报告 removed", removeTabView(new Item("mine")));
    }

    // ---------------------------------------------------------------- 工具

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    /** 迭代即抛异常的 List，用来驱动 interceptFilteredArgs 的过滤失败分支。 */
    private static List<Object> explodingList() {
        return new AbstractList<Object>() {
            @Override
            public Object get(int index) {
                throw new IllegalStateException("list unreadable");
            }

            @Override
            public int size() {
                return 1;
            }

            @Override
            public java.util.Iterator<Object> iterator() {
                throw new IllegalStateException("list unreadable");
            }
        };
    }

    private static boolean dataLayerFiltered() throws Exception {
        Field field = TopTabHooks.class.getDeclaredField("sDataLayerFiltered");
        field.setAccessible(true);
        return field.getBoolean(null);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> removedTitles() throws Exception {
        Field field = TopTabHooks.class.getDeclaredField("sRemovedTitles");
        field.setAccessible(true);
        Set<String> titles = (Set<String>) field.get(null);
        synchronized (titles) {
            return new HashSet<>(titles);
        }
    }

    private static boolean removeTab(Object layout, Object tab) throws Exception {
        Method method = TopTabHooks.class.getDeclaredMethod(
                "removeTab", Object.class, Object.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, layout, tab);
    }

    private static void rememberHomeTabLayout(List<Object> args) throws Exception {
        Method method = TopTabHooks.class.getDeclaredMethod("rememberHomeTabLayout", List.class);
        method.setAccessible(true);
        method.invoke(null, args);
    }

    private static boolean removeTabView(Object tab) throws Exception {
        Method method = TopTabHooks.class.getDeclaredMethod("removeTabView", Object.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, tab);
    }

    /** 模拟 MusicTabLayout：只有一个 public removeTab(k) 重载。 */
    public static final class FakeLayout {
        Object removed;

        public void removeTab(Object tab) {
            removed = tab;
        }
    }

    /** 模拟顶部频道项：shouldKeepItem / firstStringGetter 都读 getTitle。 */
    public static final class Item {
        private final String title;

        Item(String title) {
            this.title = title;
        }

        public String getTitle() {
            return title;
        }
    }

    /** 类名必须以 MusicTabLayout 结尾：rememberHomeTabLayout 按类名识别首页容器。 */
    public static final class FakeMusicTabLayout {
    }
}
