package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.HookState;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * AD-04 hook 体语义：
 * hideAllViews 是放行型入口，只记 entry 不记 hit，proceed 恰好一次、宿主异常原样抛回；
 * SearchAdItem*View#e 是替换型 hook，不执行宿主绑定（proceed 0 次）。
 */
public class SearchAdHooksTest {

    private final HookTestSupport.FakeModule module = new HookTestSupport.FakeModule();

    @Test
    public void hideAllViews_recordsEntryNotHitAndProceedsOnce() throws Throwable {
        JSONObject before = searchAdState();
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(new Object());

        Object result = SearchAdHooks.interceptHideAllViews(module, Feature.SEARCH_AD, chain);

        assertEquals("host-result", result);
        assertEquals("放行型 hook 必须 proceed 恰好一次", 1, chain.proceedCount);
        JSONObject after = searchAdState();
        assertEquals("入口调用应记 entry",
                before.optInt("entryCount") + 1, after.optInt("entryCount"));
        assertEquals("pass-through 不得记 hit",
                before.optInt("hitCount"), after.optInt("hitCount"));
        // hit 是全局 Feature 状态（同 JVM 内其它 hook 体测试可能已置位），只比较调用前后
        assertEquals("pass-through 不得把 Feature 标成 hit",
                before.optBoolean("hit"), after.optBoolean("hit"));
        for (String line : module.logs) {
            assertFalse("hideAllViews 不应打 hit 日志: " + line, line.contains(" hit "));
        }
    }

    @Test
    public void hideAllViews_hostExceptionPropagatesAfterSingleProceed() throws Throwable {
        RuntimeException boom = new RuntimeException("host hideAllViews failed");
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(new Object());
        chain.failure = boom;

        try {
            SearchAdHooks.interceptHideAllViews(module, Feature.SEARCH_AD, chain);
            fail("宿主 hideAllViews 的异常必须原样抛回");
        } catch (Throwable t) {
            assertSame("不得用 catch 里的返回值/新异常覆盖宿主异常", boom, t);
        }
        assertEquals(1, chain.proceedCount);
    }

    @Test
    public void adItem_suppressesBindWithoutProceed() {
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(new Object());

        Object result = SearchAdHooks.interceptAdItem(module, Feature.SEARCH_AD,
                "SearchAdItemLargeView#e", chain);

        assertNull("替换型 hook 返回 null", result);
        assertEquals("e 的宿主绑定体不得执行", 0, chain.proceedCount);
    }

    @Test
    public void adItem_thisObjectFailureStillSuppresses() {
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(new Object());
        chain.thisObjectFailure = new RuntimeException("no this object");

        Object result = SearchAdHooks.interceptAdItem(module, Feature.SEARCH_AD,
                "SearchAdItemMediumView#e", chain);

        assertNull(result);
        assertEquals(0, chain.proceedCount);
        assertTrue("隐藏失败只记录，不能回退去执行宿主绑定: " + module.logs,
                module.logs.toString().contains("intercept error"));
    }

    /** SEARCH_AD 的诊断快照；buildJson 是包私有入口，跨包测试用反射读取。 */
    private static JSONObject searchAdState() throws Exception {
        Method build = HookState.class.getDeclaredMethod("buildJson", int.class);
        build.setAccessible(true);
        String json = (String) build.invoke(null, 1 << 20);
        JSONObject features = new JSONObject(json).optJSONObject("features");
        JSONObject state = features == null ? null : features.optJSONObject("SEARCH_AD");
        return state == null ? new JSONObject() : state;
    }
}
