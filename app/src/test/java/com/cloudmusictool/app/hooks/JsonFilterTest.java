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
