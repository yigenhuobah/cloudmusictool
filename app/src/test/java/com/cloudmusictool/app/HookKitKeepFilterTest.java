package com.cloudmusictool.app;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * HookKit.shouldKeepTab / shouldKeepItem / firstStringGetter 的 JVM 单测。
 *
 * 三者是 UI-05 底栏（BottomTabHooks.filterBottomTabs）与 UI-04 顶部频道
 * （TopTabHooks.filterList）保留清单是否真实生效的唯一判定，纯反射逻辑，
 * 定义 POJO 即可测。HookKit 是 com.cloudmusictool.app.hooks 包私有 final
 * class，本测试按任务约定放在 com.cloudmusictool.app 包，用反射调用，
 * 不为测试放宽生产代码可见性。
 */
public class HookKitKeepFilterTest {

    private static boolean shouldKeepTab(Object item, Set<String> keepSet) throws Exception {
        Method m = hookKitMethod("shouldKeepTab", Object.class, Set.class);
        return (Boolean) m.invoke(null, item, keepSet);
    }

    private static boolean shouldKeepItem(Object item, Set<String> keepSet) throws Exception {
        Method m = hookKitMethod("shouldKeepItem", Object.class, Set.class);
        return (Boolean) m.invoke(null, item, keepSet);
    }

    private static String firstStringGetter(Object item) throws Exception {
        Method m = hookKitMethod("firstStringGetter", Object.class);
        return (String) m.invoke(null, item);
    }

    private static Method hookKitMethod(String name, Class<?>... params) throws Exception {
        Class<?> cls = Class.forName("com.cloudmusictool.app.hooks.HookKit");
        Method m = cls.getDeclaredMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    private static Set<String> keep(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    /** shouldKeepItem 的 getter 顺序：getTitle -> getChannelCode -> getCode -> getName。 */
    public static final class ItemPojo {
        private final String title;
        private final String channelCode;
        private final String code;
        private final String name;

        public ItemPojo(String title, String channelCode, String code, String name) {
            this.title = title;
            this.channelCode = channelCode;
            this.code = code;
            this.name = name;
        }

        public String getTitle() {
            return title;
        }

        public String getChannelCode() {
            return channelCode;
        }

        public String getCode() {
            return code;
        }

        public String getName() {
            return name;
        }
    }

    /** shouldKeepTab 的 getter 顺序：getTitle -> getTabName -> getName -> getCode -> getTabTitle -> getTabCode。 */
    public static final class TabPojo {
        private final String title;
        private final String tabCode;

        public TabPojo(String title, String tabCode) {
            this.title = title;
            this.tabCode = tabCode;
        }

        public String getTitle() {
            return title;
        }

        public String getTabCode() {
            return tabCode;
        }
    }

    /** firstStringGetter 的顺序：getTitle -> getName -> getTabName。 */
    public static final class FirstPojo {
        private final String title;
        private final String name;
        private final String tabName;

        public FirstPojo(String title, String name, String tabName) {
            this.title = title;
            this.name = name;
            this.tabName = tabName;
        }

        public String getTitle() {
            return title;
        }

        public String getName() {
            return name;
        }

        public String getTabName() {
            return tabName;
        }
    }

    @Test
    public void item_titleTrimmedBeforeMatch() throws Exception {
        // 变异判别输入：删除 trim 后 " 推荐 " 不再命中保留清单，本用例变红。
        assertTrue(shouldKeepItem(new ItemPojo(" 推荐 ", null, null, null), keep("推荐")));
    }

    @Test
    public void item_getterFallback() throws Exception {
        assertTrue(shouldKeepItem(new ItemPojo(null, "favor", null, null), keep("favor")));
        assertTrue(shouldKeepItem(new ItemPojo(null, null, "tab_code", null), keep("tab_code")));
        assertTrue(shouldKeepItem(new ItemPojo(null, null, null, "搜索"), keep("搜索")));
        // 空 getter 值不算命中，继续回退到下一个 getter。
        assertTrue(shouldKeepItem(new ItemPojo("", null, null, "搜索"), keep("搜索")));
    }

    @Test
    public void item_noMatch_false() throws Exception {
        assertFalse(shouldKeepItem(new ItemPojo("广告", null, null, null), keep("推荐")));
        assertFalse(shouldKeepItem(new ItemPojo(null, null, null, "直播"), keep("推荐")));
    }

    @Test
    public void item_setEntryNotTrimmed() throws Exception {
        // getter 值被 trim，但保留清单条目按原文精确匹配。
        assertFalse(shouldKeepItem(new ItemPojo(" 推荐 ", null, null, null), keep("推荐 ")));
    }

    @Test
    public void item_nullItemOrNullOrEmptyEntries_false() throws Exception {
        ItemPojo pojo = new ItemPojo("推荐", null, null, null);
        assertFalse(shouldKeepItem(null, keep("推荐")));
        assertFalse(shouldKeepItem(pojo, null));
        assertFalse(shouldKeepItem(pojo, Collections.<String>emptySet()));
    }

    @Test
    public void tab_usesTabGetters() throws Exception {
        assertTrue(shouldKeepTab(new TabPojo("首页", "tab-home"), keep("首页")));
        // title 不命中继续走后续 getter，直到 getTabCode。
        assertTrue(shouldKeepTab(new TabPojo("首页", "my"), keep("my")));
        assertFalse(shouldKeepTab(new TabPojo("广告", "ad"), keep("推荐")));
    }

    @Test
    public void tab_nullItemOrNullOrEmptyEntries_false() throws Exception {
        TabPojo pojo = new TabPojo("首页", "tab-home");
        assertFalse(shouldKeepTab(null, keep("首页")));
        assertFalse(shouldKeepTab(pojo, null));
        assertFalse(shouldKeepTab(pojo, Collections.<String>emptySet()));
    }

    @Test
    public void firstStringGetter_priorityAndTrim() throws Exception {
        // 变异判别：优先级 getTitle -> getName -> getTabName，返回值同样 trim。
        assertEquals("A", firstStringGetter(new FirstPojo(" A ", " B ", " C ")));
        assertEquals("B", firstStringGetter(new FirstPojo(null, " B ", " C ")));
        assertEquals("C", firstStringGetter(new FirstPojo(null, null, " C ")));
        assertNull(firstStringGetter(new FirstPojo(null, null, null)));
        assertNull(firstStringGetter(null));
    }
}
