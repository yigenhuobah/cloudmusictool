package com.cloudmusictool.app.hooks;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * UI-03 匹配纯函数测试：不依赖 Android / Xposed 运行时。
 * 覆盖 9.6.13 的 positionCode 主匹配、NativeBlock JSON 兜底、DslBlock 正则兜底、
 * 别名映射与未命中路径。
 */
public class HomeBlockHooksTest {

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    @Test
    public void matchesHide_byPositionCode() {
        FakeBlock block = new FakeBlock("PAGE_RECOMMEND_GREETING");
        assertTrue(HomeBlockHooks.matchesHide(block, set("PAGE_RECOMMEND_GREETING")));
    }

    @Test
    public void matchesHide_nativeBlockNativeDataFallback() throws Exception {
        JSONObject nativeData = new JSONObject().put("positionCode", "PAGE_RECOMMEND_NOTE");
        FakeNativeBlock block = new FakeNativeBlock(null, nativeData, null);
        assertTrue(HomeBlockHooks.matchesHide(block, set("PAGE_RECOMMEND_NOTE")));
    }

    @Test
    public void matchesHide_nativeBlockOriginServerDataFallback() throws Exception {
        JSONObject originServerData = new JSONObject().put("blockCode", "PAGE_RECOMMEND_GREETING");
        FakeNativeBlock block = new FakeNativeBlock(null, null, originServerData);
        assertTrue(HomeBlockHooks.matchesHide(block, set("PAGE_RECOMMEND_GREETING")));
    }

    @Test
    public void matchesHide_dslBlockRegexFallback() throws Exception {
        JSONObject originData = new JSONObject().put("extra", "prefix-PAGE_RECOMMEND_RADAR-suffix");
        FakeDslBlock block = new FakeDslBlock(new FakeDslModel(originData, null, null));
        assertTrue(HomeBlockHooks.matchesHide(block, set("PAGE_RECOMMEND_RADAR")));
    }

    @Test
    public void matchesHide_noMatchReturnsFalse() {
        assertFalse(HomeBlockHooks.matchesHide(
                new FakeBlock("PAGE_RECOMMEND_KEEP"), set("PAGE_RECOMMEND_HIDE")));
        assertFalse(HomeBlockHooks.matchesHide(new FakeBlock(null), set("PAGE_RECOMMEND_HIDE")));
        assertFalse(HomeBlockHooks.matchesHide(null, set("PAGE_RECOMMEND_HIDE")));
        assertFalse(HomeBlockHooks.matchesHide(
                new FakeBlock("PAGE_RECOMMEND_KEEP"), Collections.emptySet()));
    }

    @Test
    public void matchesHide_aliasRankToToplist() {
        assertTrue(HomeBlockHooks.matchesHide(
                new FakeBlock("PAGE_BLOCK_TOPLIST"), set("PAGE_RECOMMEND_RANK")));
    }

    @Test
    public void matchesHide_aliasBannerAndPodcastRenames() {
        assertTrue(HomeBlockHooks.matchesHide(
                new FakeBlock("PAGE_RECOMMEND_BANNER_0"), set("PAGE_RECOMMEND_BANNER_1")));
        assertTrue(HomeBlockHooks.matchesHide(
                new FakeBlock("PAGE_RECOMMEND_BANNER_0"), set("PAGE_RECOMMEND_BANNER_6")));
        assertTrue(HomeBlockHooks.matchesHide(
                new FakeBlock("PAGE_RECOMMEND_PODCAST_NARROW_SCENE"),
                set("PAGE_RECOMMEND_PODCAST_SUB_UPDATE")));
    }

    /** 9.6.13 的 DslBlock / NativeBlock / RnBlock 都继承 Block，positionCode 是主载体。 */
    public static final class FakeBlock {
        private final String positionCode;

        FakeBlock(String positionCode) {
            this.positionCode = positionCode;
        }

        public String getPositionCode() {
            return positionCode;
        }
    }

    public static final class FakeNativeBlock {
        private final String positionCode;
        private final JSONObject nativeData;
        private final JSONObject originServerData;

        FakeNativeBlock(String positionCode, JSONObject nativeData, JSONObject originServerData) {
            this.positionCode = positionCode;
            this.nativeData = nativeData;
            this.originServerData = originServerData;
        }

        public String getPositionCode() {
            return positionCode;
        }

        public JSONObject getNativeData() {
            return nativeData;
        }

        public JSONObject getOriginServerData() {
            return originServerData;
        }
    }

    public static final class FakeDslBlock {
        private final Object model;

        FakeDslBlock(Object model) {
            this.model = model;
        }

        public Object getDslWrapperModel() {
            return model;
        }
    }

    public static final class FakeDslModel {
        private final Object originData;
        private final Object extData;
        private final String dslContent;

        FakeDslModel(Object originData, Object extData, String dslContent) {
            this.originData = originData;
            this.extData = extData;
            this.dslContent = dslContent;
        }

        public Object getOriginData() {
            return originData;
        }

        public Object getExtData() {
            return extData;
        }

        public String getDslContent() {
            return dslContent;
        }
    }
}
