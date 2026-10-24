package com.cloudmusictool.app.hooks;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * JsonFilter 零命中快路径(P2-6)的行为与量级 sanity。
 * 行为断言为主:零删除路径不得重建数组、不得重序列化嵌套字符串;
 * 计时仅为宽松 sanity,阈值远高于实测,不构成精确基准。
 */
public class JsonFilterBenchTest {

    private static final Set<String> HIDE = new HashSet<>(
            Arrays.asList("PAGE_HIDE_BANNER", "PAGE_HIDE_POPUP"));

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    @Test
    public void filterValue_zeroHitArrayReturnsSameReference() throws Exception {
        JSONArray root = new JSONArray();
        root.put(new JSONObject().put("blockCode", "PAGE_KEEP").put("ext", "[{\"a\":1}]"));
        root.put(new JSONArray().put("plain").put(new JSONObject().put("k", "v")));
        int[] removed = new int[]{0};
        Object out = JsonFilter.filterValue(root, HIDE, removed, 0);
        assertEquals(0, removed[0]);
        assertSame(root, out);
    }

    @Test
    public void filterValue_zeroHitNestedArrayKeepsSameReferenceInParent() throws Exception {
        JSONObject child = new JSONObject().put("blockCode", "PAGE_KEEP");
        JSONArray nested = new JSONArray().put(child);
        JSONObject root = new JSONObject().put("feed", nested);
        int[] removed = new int[]{0};
        JsonFilter.filterValue(root, HIDE, removed, 0);
        assertEquals(0, removed[0]);
        assertSame(nested, root.opt("feed"));
    }

    @Test
    public void filterNestedString_zeroHitReturnsOriginalReference() throws Exception {
        // 带空白:若走了 parse+重序列化,org.json 会归一化并产生新串,assertSame 即失败
        String nested = "[  {\"blockCode\" : \"PAGE_KEEP\"}  ]";
        int[] removed = new int[]{0};
        Object out = JsonFilter.filterNestedString(nested, set("PAGE_HIDE"), removed, 0);
        assertEquals(0, removed[0]);
        assertSame(nested, out);
    }

    @Test
    public void filterNestedString_gateHitButNoDeletionKeepsOriginal() throws Exception {
        // hideSet 值出现在非 blockCode 字段:快门命中(会 parse)但零删除,仍应返回原串
        String nested = "[ {\"title\":\"PAGE_HIDE\",\"blockCode\":\"PAGE_KEEP\"} ]";
        int[] removed = new int[]{0};
        Object out = JsonFilter.filterNestedString(nested, set("PAGE_HIDE"), removed, 0);
        assertEquals(0, removed[0]);
        assertSame(nested, out);
    }

    @Test
    public void filterNestedString_hitStillFiltersAndSerializes() throws Exception {
        String nested = "[{\"blockCode\":\"PAGE_HIDE\"},{\"blockCode\":\"PAGE_KEEP\"}]";
        int[] removed = new int[]{0};
        Object out = JsonFilter.filterNestedString(nested, set("PAGE_HIDE"), removed, 0);
        assertEquals(1, removed[0]);
        assertEquals("[{\"blockCode\":\"PAGE_KEEP\"}]", out);
    }

    @Test
    public void bench_largeZeroHitFeedSingleCallUnder50ms() throws Exception {
        JSONObject root = new JSONObject(buildLargeFeedBlob());
        JSONArray feed = root.optJSONArray("feed");
        String blob = root.toString();
        assertTrue("blob 应为 ~500KB 量级,实际=" + blob.length(), blob.length() > 400 * 1024);
        assertTrue(blob.contains("blockCode"));

        int[] removed = new int[]{0};
        Object out = JsonFilter.filterValue(feed, HIDE, removed, 0);
        assertEquals(0, removed[0]);
        assertSame(feed, out);

        for (int i = 0; i < 20; i++) {
            JsonFilter.filterValue(root, HIDE, new int[]{0}, 0);
        }
        int n = 7;
        long[] elapsed = new long[n];
        for (int i = 0; i < n; i++) {
            long start = System.nanoTime();
            JsonFilter.filterValue(root, HIDE, new int[]{0}, 0);
            elapsed[i] = System.nanoTime() - start;
        }
        Arrays.sort(elapsed);
        double medianUs = elapsed[n / 2] / 1000.0;
        // 宽松 sanity:零命中应走快门,实测 µs 量级;50ms 是防 flaky 的数量级上限
        assertTrue(String.format(Locale.ROOT, "median=%.1fus 超出 50ms sanity 上限", medianUs),
                medianUs < 50_000.0);
    }

    /** 构造 ~500KB 零命中 blob:50 项,每项含 blockCode(非命中值)+ 嵌套 JSON 字符串字段 ext。 */
    private static String buildLargeFeedBlob() throws JSONException {
        StringBuilder pad = new StringBuilder(10 * 1024);
        for (int i = 0; i < 10 * 1024; i++) {
            pad.append((char) ('a' + (i % 26)));
        }
        String padding = pad.toString();
        JSONArray feed = new JSONArray();
        for (int i = 0; i < 50; i++) {
            JSONObject item = new JSONObject();
            item.put("blockCode", "PAGE_RECOMMEND_ITEM_" + i);
            item.put("title", "song " + i);
            JSONObject ext = new JSONObject();
            ext.put("schema", "music-feed-v3");
            ext.put("idx", i);
            ext.put("payload", padding);
            item.put("ext", ext.toString());
            feed.put(item);
        }
        JSONObject root = new JSONObject();
        root.put("ret", 0);
        root.put("feed", feed);
        return root.toString();
    }
}
