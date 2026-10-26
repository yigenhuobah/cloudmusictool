package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.profile.Profile9608;
import com.cloudmusictool.app.profile.Profile9613;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * UI-05 底栏保留清单纯过滤函数测试：不依赖 Android / Xposed 运行时。
 *
 * 9.6.13 的 tab 列表元素是 BottomTabInfoVO（getTabCode/getTabTitle）；
 * NavigationTabUiState（tf0.u）没有这两个 getter，只有 g()=tabCode、i()=tabTitle。
 */
public class BottomTabHooksTest {

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    @Test
    public void filter_byTabCode() {
        FakeTab keep = new FakeTab("mine", "我的");
        FakeTab hide = new FakeTab("fm", "播客");
        List<?> out = BottomTabHooks.filterBottomTabs(
                Arrays.asList(keep, hide), set("mine"));
        assertEquals(Collections.singletonList(keep), out);
    }

    @Test
    public void filter_byChineseTitle() {
        FakeTab keep = new FakeTab("mine", "我的");
        FakeTab hide = new FakeTab("fm", "播客");
        List<?> out = BottomTabHooks.filterBottomTabs(
                Arrays.asList(hide, keep), set("我的"));
        assertEquals(Collections.singletonList(keep), out);
    }

    @Test
    public void filter_emptyKeepSet_returnsSameList() {
        List<?> list = Arrays.asList(new FakeTab("mine", "我的"), new FakeTab("fm", "播客"));
        assertSame(list, BottomTabHooks.filterBottomTabs(list, Collections.<String>emptySet()));
        assertSame(list, BottomTabHooks.filterBottomTabs(list, null));
    }

    @Test
    public void filter_nothingRemoved_returnsSameList() {
        List<?> list = Arrays.asList(new FakeTab("mine", "我的"), new FakeTab("fm", "播客"));
        assertSame(list, BottomTabHooks.filterBottomTabs(list, set("mine", "播客")));
    }

    @Test
    public void filter_allRemoved_failsOpenOriginalList() {
        List<?> list = Arrays.asList(new FakeTab("mine", "我的"), new FakeTab("fm", "播客"));
        assertSame("keep 集全不匹配时必须原样返回，不能只保留第一个 tab",
                list, BottomTabHooks.filterBottomTabs(list, set("不存在")));
    }

    @Test
    public void tabListBuildClass_gatedTo9613Profile() {
        // 9.6.13 专属 tf0.o$a；9.6.08 不得返回查找名，避免 HookKit 登记
        // 「类未找到 tf0.o$a」造成诊断误报。
        assertEquals("tf0.o$a", BottomTabHooks.tabListBuildClass(Profile9613.PROFILE));
        assertNull(BottomTabHooks.tabListBuildClass(Profile9608.PROFILE));
        assertNull(BottomTabHooks.tabListBuildClass(null));
    }

    @Test
    public void filter_tf0u_usesGAndIFallback() {
        // 模拟 tf0.u：没有 getTabCode/getTabTitle，只有 g()=tabCode、i()=tabTitle。
        UiStatePojo mine = new UiStatePojo("mine", "我的");
        UiStatePojo fm = new UiStatePojo("fm", "播客");

        assertEquals(Collections.singletonList(mine), BottomTabHooks.filterBottomTabs(
                Arrays.asList(mine, fm), set("mine")));
        assertEquals(Collections.singletonList(fm), BottomTabHooks.filterBottomTabs(
                Arrays.asList(mine, fm), set("播客")));
    }

    @Test
    public void filter_valuesAreTrimmedBeforeMatch() {
        FakeTab tab = new FakeTab(" mine ", " 我的 ");
        List<?> out = BottomTabHooks.filterBottomTabs(
                Arrays.asList(tab, new FakeTab("fm", "播客")), set("mine"));
        assertEquals(Collections.singletonList(tab), out);
    }

    @Test
    public void filter_nullItemsAreDropped() {
        FakeTab keep = new FakeTab("mine", "我的");
        List<Object> list = new ArrayList<>();
        list.add(null);
        list.add(keep);
        List<?> out = BottomTabHooks.filterBottomTabs(list, set("mine"));
        assertEquals(Collections.singletonList(keep), out);
        assertTrue(out.size() == 1);
    }

    /** 模拟 BottomTabInfoVO。 */
    public static final class FakeTab {
        private final String tabCode;
        private final String tabTitle;

        FakeTab(String tabCode, String tabTitle) {
            this.tabCode = tabCode;
            this.tabTitle = tabTitle;
        }

        public String getTabCode() {
            return tabCode;
        }

        public String getTabTitle() {
            return tabTitle;
        }
    }

    /** 模拟 tf0.u（NavigationTabUiState）：g()=tabCode、i()=tabTitle。 */
    public static final class UiStatePojo {
        private final String code;
        private final String title;

        UiStatePojo(String code, String title) {
            this.code = code;
            this.title = title;
        }

        public String g() {
            return code;
        }

        public String i() {
            return title;
        }
    }
}
