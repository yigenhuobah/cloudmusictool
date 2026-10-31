package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * AD-07 CommentAdapter#y1 的 hook 体语义：
 * 清理广告字段后 proceed 恰好一次；宿主 y1 抛异常时原样抛回，
 * 不允许 catch 里补第二次 proceed（那会让同一次 bind 执行两遍）。
 */
public class CommentAdHooksTest {

    private final HookTestSupport.FakeModule module = new HookTestSupport.FakeModule();

    @Test
    public void y1_clearsAdFieldsAndProceedsOnce() throws Throwable {
        CommentListEntry entry = new CommentListEntry();
        entry.ad = "ad";
        entry.commentGeneralBanner = "banner";
        entry.vipRcmdList = "vip";
        entry.liveRcmdList = "live";
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(null);
        chain.args.add(entry);

        Object result = CommentAdHooks.interceptEntry(module, Feature.COMMENT_AD, chain);

        assertEquals("host-result", result);
        assertEquals(1, chain.proceedCount);
        assertNull(entry.ad);
        assertNull(entry.commentGeneralBanner);
        assertNull(entry.vipRcmdList);
        assertNull(entry.liveRcmdList);
    }

    @Test
    public void y1_hostExceptionPropagatesAfterSingleProceed() {
        RuntimeException boom = new RuntimeException("host y1 failed");
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(null);
        chain.args.add(new CommentListEntry());
        chain.failure = boom;

        try {
            CommentAdHooks.interceptEntry(module, Feature.COMMENT_AD, chain);
            fail("宿主 y1 的异常必须原样抛回");
        } catch (Throwable t) {
            assertSame("不能用第二次 proceed 的结果/异常覆盖首个异常", boom, t);
        }
        assertEquals("catch 里不得补 proceed", 1, chain.proceedCount);
    }

    /** 与 CommentAdapter#y1 的入参同构：被清空的是这四个字段。 */
    public static final class CommentListEntry {
        Object ad;
        Object commentGeneralBanner;
        Object vipRcmdList;
        Object liveRcmdList;
    }
}
