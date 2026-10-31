package com.cloudmusictool.app.hooks;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class JsonFilterTest {

    private static Set<String> set(String... values) {
        return new HashSet<>(Arrays.asList(values));
    }

    @Test
    public void filterCodeListString_removesMatchingCodes() throws Exception {
        String json = "[\"PAGE_A\",\"PAGE_B\",\"PAGE_C\"]";
        int[] removed = new int[]{0};
        String out = JsonFilter.filterCodeListString(json, set("PAGE_B"), removed);
        assertEquals(1, removed[0]);
        JSONArray arr = new JSONArray(out);
        assertEquals(2, arr.length());
        assertEquals("PAGE_A", arr.optString(0));
        assertEquals("PAGE_C", arr.optString(1));
    }

    @Test
    public void filterCodeListString_keepsOriginalWhenNoMatch() throws Exception {
        String json = "[\"PAGE_A\"]";
        int[] removed = new int[]{0};
        String out = JsonFilter.filterCodeListString(json, set("PAGE_X"), removed);
        assertEquals(0, removed[0]);
        assertSame(json, out);
    }

    @Test
    public void filterCodeListString_keepsOriginalWhenSetEmpty() throws Exception {
        String json = "[\"PAGE_A\"]";
        String out = JsonFilter.filterCodeListString(json, Collections.emptySet(), new int[]{0});
        assertSame(json, out);
    }

    @Test
    public void filterCodeListString_preservesNonStringElements() throws Exception {
        // optString 会把非字符串元素字符串化：对象变 JSON 文本、数字变 "2"、null 变 "null"。
        // 只要有一次删除整串就会被回写，因此非字符串元素必须按原 JSON 值保留。
        String json = "[{\"a\":1},2,\"PAGE_HIDE\",null,true]";
        int[] removed = new int[]{0};
        String out = JsonFilter.filterCodeListString(json, set("PAGE_HIDE"), removed);
        assertEquals(1, removed[0]);
        JSONArray arr = new JSONArray(out);
        assertEquals(4, arr.length());
        assertTrue("对象元素必须仍是对象: " + arr.opt(0), arr.opt(0) instanceof JSONObject);
        assertEquals(Integer.valueOf(2), arr.opt(1));
        assertSame(JSONObject.NULL, arr.opt(2));
        assertEquals(Boolean.TRUE, arr.opt(3));
        for (int i = 0; i < arr.length(); i++) {
            assertFalse("元素不得被字符串化: " + arr.opt(i), arr.opt(i) instanceof String);
        }
    }

    @Test
    public void filterCodeListString_invalidJsonReturnsOriginal() throws Exception {
        String json = "not-json";
        String out = JsonFilter.filterCodeListString(json, set("PAGE_A"), new int[]{0});
        assertSame(json, out);
    }

    @Test
    public void filterValue_removesObjectsByBlockCode() throws Exception {
        JSONArray root = new JSONArray();
        root.put(new JSONObject().put("blockCode", "PAGE_HIDE").put("x", 1));
        root.put(new JSONObject().put("blockCode", "PAGE_KEEP").put("x", 2));
        int[] removed = new int[]{0};
        Object out = JsonFilter.filterValue(root, set("PAGE_HIDE"), removed, 0);
        assertTrue(out instanceof JSONArray);
        assertEquals(1, removed[0]);
        assertEquals(1, ((JSONArray) out).length());
        assertEquals("PAGE_KEEP", ((JSONArray) out).optJSONObject(0).optString("blockCode"));
    }

    @Test
    public void filterValue_removesBlockedObjectValue() throws Exception {
        // block 以「对象值」出现（直接子对象 + 更深层子对象）时必须删除该键，
        // 此前 JSONObject 分支只递归子值、从不看对象自身的 blockCode。
        JSONObject feed = new JSONObject().put("blockCode", "PAGE_HIDE").put("x", 1);
        JSONObject root = new JSONObject()
                .put("feed", feed)
                .put("deep", new JSONObject().put("inner",
                        new JSONObject().put("blockCode", "PAGE_HIDE")))
                .put("keep", new JSONObject().put("blockCode", "PAGE_KEEP"));
        int[] removed = new int[]{0};
        Object out = JsonFilter.filterValue(root, set("PAGE_HIDE"), removed, 0);
        assertEquals(2, removed[0]);
        JSONObject obj = (JSONObject) out;
        assertFalse(obj.has("feed"));
        // 只删除命中的那个对象本身；包着它的父对象保留，但里面的 block 已消失
        assertFalse(obj.getJSONObject("deep").has("inner"));
        assertEquals("PAGE_KEEP", obj.getJSONObject("keep").optString("blockCode"));
    }

    @Test
    public void filterValue_removesBlockedNestedStringRoot() throws Exception {
        // 嵌套 JSON 字符串的「根对象」命中 blockCode 时整串删除，宿主键一并删除
        JSONObject root = new JSONObject()
                .put("payload", "{\"blockCode\":\"PAGE_HIDE\",\"x\":1}")
                .put("keep", "{\"blockCode\":\"PAGE_KEEP\"}");
        int[] removed = new int[]{0};
        Object out = JsonFilter.filterValue(root, set("PAGE_HIDE"), removed, 0);
        assertEquals(1, removed[0]);
        JSONObject obj = (JSONObject) out;
        assertFalse(obj.has("payload"));
        assertEquals("{\"blockCode\":\"PAGE_KEEP\"}", obj.optString("keep"));
    }

    @Test
    public void filterValue_removesBlockedNestedStringRootInArray() throws Exception {
        JSONArray root = new JSONArray()
                .put("{\"blockCode\":\"PAGE_HIDE\"}")
                .put("{\"blockCode\":\"PAGE_KEEP\"}");
        int[] removed = new int[]{0};
        Object out = JsonFilter.filterValue(root, set("PAGE_HIDE"), removed, 0);
        assertEquals(1, removed[0]);
        JSONArray arr = (JSONArray) out;
        assertEquals(1, arr.length());
        assertEquals("{\"blockCode\":\"PAGE_KEEP\"}", arr.optString(0));
    }

    @Test
    public void filterValue_zeroDeleteKeepsReferencesForObjectValues() throws Exception {
        JSONObject child = new JSONObject().put("blockCode", "PAGE_KEEP");
        JSONObject root = new JSONObject().put("feed", child);
        int[] removed = new int[]{0};
        Object out = JsonFilter.filterValue(root, set("PAGE_HIDE"), removed, 0);
        assertEquals(0, removed[0]);
        assertSame(root, out);
        assertSame(child, root.opt("feed"));
    }

    @Test
    public void filterValue_filtersNestedJsonString() throws Exception {
        JSONObject root = new JSONObject();
        root.put("payload", "[{\"blockCode\":\"PAGE_HIDE\"}]");
        root.put("keep", "[{\"blockCode\":\"PAGE_KEEP\"}]");
        int[] removed = new int[]{0};
        Object out = JsonFilter.filterValue(root, set("PAGE_HIDE"), removed, 0);
        assertEquals(1, removed[0]);
        assertEquals("[]", ((JSONObject) out).optString("payload"));
        assertEquals("[{\"blockCode\":\"PAGE_KEEP\"}]", ((JSONObject) out).optString("keep"));
    }

    @Test
    public void filterValue_depthLimitStopsRecursion() throws Exception {
        JSONObject deep = new JSONObject();
        deep.put("blockCode", "PAGE_HIDE");
        int[] removed = new int[]{0};
        Object out = JsonFilter.filterValue(deep, set("PAGE_HIDE"), removed, 7);
        assertEquals(0, removed[0]);
        assertSame(deep, out);
    }
}
