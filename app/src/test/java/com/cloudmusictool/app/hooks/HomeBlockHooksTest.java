package com.cloudmusictool.app.hooks;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
    public void matchesHide_nativeBlockMergesNativeDataEvenWithPositionCode() throws Exception {
        // 外层容器 positionCode 与模块 code 不同：原生数据里的 code 也必须参与匹配。
        JSONObject nativeData = new JSONObject().put("blockCode", "PAGE_RECOMMEND_NOTE");
        FakeNativeBlock block = new FakeNativeBlock("PAGE_CONTAINER", nativeData, null);
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

    @Test
    public void matchesHide_scansSerializableMapAndCollectionNodes() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("code", "PAGE_RECOMMEND_RADAR");
        assertTrue(HomeBlockHooks.matchesHide(
                new FakeDslBlock(new FakeDslModel(map, null, null)),
                set("PAGE_RECOMMEND_RADAR")));

        assertTrue(HomeBlockHooks.matchesHide(
                new FakeDslBlock(new FakeDslModel(
                        new ArrayList<>(Collections.singletonList("PAGE_RECOMMEND_NOTE")),
                        null, null)),
                set("PAGE_RECOMMEND_NOTE")));
    }

    @Test
    public void matchesHide_nonSerializableNodeIsNotStringified() {
        // DslBlock 的 originData/extData 不是 JSON/Map/Collection 时直接跳过；
        // 若还按旧实现 String.valueOf(node)，ToStringBomb 会抛 AssertionError。
        FakeDslModel model = new FakeDslModel(
                new ToStringBomb(), new ToStringBomb(), "plain-text");
        assertFalse(HomeBlockHooks.matchesHide(
                new FakeDslBlock(model), set("PAGE_RECOMMEND_HIDE")));
        assertNull(HomeBlockHooks.serializableText(
                new ToStringBomb(), HomeBlockHooks.MAX_JSON_CHARS));
    }

    @Test
    public void matchesHide_oversizedNodeIsStillSkipped() {
        char[] chars = new char[HomeBlockHooks.MAX_JSON_CHARS + 1];
        Arrays.fill(chars, 'a');
        String prefix = "PAGE_RECOMMEND_OVERSIZE";
        prefix.getChars(0, prefix.length(), chars, 0);
        String huge = new String(chars);

        assertNull("超过 16MB 上限的节点必须跳过",
                HomeBlockHooks.serializableText(huge, HomeBlockHooks.MAX_JSON_CHARS));
        assertFalse(HomeBlockHooks.matchesHide(
                new FakeDslBlock(new FakeDslModel(huge, null, null)),
                set("PAGE_RECOMMEND_OVERSIZE")));
    }

    @Test
    public void tokenCache_hitsByBlockIdentity() {
        CountingBlock block = new CountingBlock("PAGE_RECOMMEND_GREETING");
        Set<String> hide = set("PAGE_RECOMMEND_GREETING");
        assertTrue(HomeBlockHooks.matchesHide(block, hide));
        assertEquals(1, block.calls);
        assertTrue(HomeBlockHooks.matchesHide(block, hide));
        assertEquals("同一 block 第二次匹配应命中 token 缓存，不再反射扫描", 1, block.calls);
        assertTrue(HomeBlockHooks.matchesHide(
                new CountingBlock("PAGE_RECOMMEND_GREETING"), hide));
    }

    /** 记录 positionCode 反射次数，用于验证 token 缓存命中。 */
    public static final class CountingBlock {
        private final String positionCode;
        int calls;

        CountingBlock(String positionCode) {
            this.positionCode = positionCode;
        }

        public String getPositionCode() {
            calls++;
            return positionCode;
        }
    }

    /** 非序列化宿主对象：一旦被 String.valueOf 就失败。 */
    public static final class ToStringBomb {
        @Override
        public String toString() {
            throw new AssertionError("non-serializable node must not be stringified");
        }
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
