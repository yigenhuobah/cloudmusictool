package com.cloudmusictool.app;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link Config#parseForceMap(String)} 的纯 JVM 单测（EX-01 强开链路第一道过滤）。
 *
 * parseForceMap 是 package-private static，在测试包内直接调用。判别性输入与
 * AbTestPolicyTest 对齐：黑名单 key 即使被用户提升也必须丢弃、非法分组必须
 * 丢弃——运行时虽有 AbTestHooks.forcedGroup 二次校验兜底，两层各自要有回归网，
 * 避免同时弱化时静默击穿。
 */
public class ParseForceMapTest {

    private static Map<String, String> parse(String raw) {
        return Config.parseForceMap(raw);
    }

    @Test
    public void curatedKeyWithValidGroup_kept() {
        assertEquals(Collections.singletonMap("tabMerge", "t1"),
                parse("{\"tabMerge\":\"t1\"}"));
        assertEquals(Collections.singletonMap("home-headframe", "c"),
                parse("{\"home-headframe\":\"c\"}"));
    }

    @Test
    public void promotedNonBlockedKey_kept() {
        // promoted = 表内 key - curated；非黑名单的 promoted key 允许通过。
        assertEquals(Collections.singletonMap("some-new-experiment", "t2"),
                parse("{\"some-new-experiment\":\"t2\"}"));
    }

    @Test
    public void blockedKey_droppedEvenIfPromoted() {
        // 变异判别输入：删除 isAllowed 过滤后 VIP 条目会存活，本用例变红。
        assertEquals(Collections.emptyMap(), parse("{\"VIP_accountcard_01\":\"t1\"}"));
        assertEquals(Collections.emptyMap(), parse("{\"svip_theme\":\"t2\"}"));
    }

    @Test
    public void bootAndKvPrefixedKeys_dropped() {
        assertEquals(Collections.emptyMap(), parse("{\"boot-foo\":\"t1\"}"));
        assertEquals(Collections.emptyMap(), parse("{\"kv-bar\":\"t1\"}"));
        assertEquals(Collections.emptyMap(), parse("{\"fh-kv-baz\":\"t1\"}"));
    }

    @Test
    public void invalidGroup_dropped() {
        // 变异判别输入：删除 isValidGroup 过滤后 t9 条目会存活，本用例变红。
        assertEquals(Collections.emptyMap(), parse("{\"tabMerge\":\"t9\"}"));
        assertEquals(Collections.emptyMap(), parse("{\"tabMerge\":\"C\"}"));
        assertEquals(Collections.emptyMap(), parse("{\"tabMerge\":\" t1\"}"));
    }

    @Test
    public void mixedInput_keepsOnlyAllowedEntries() {
        Map<String, String> out = parse(
                "{\"VIP_accountcard_01\":\"t1\",\"tabMerge\":\"t1\","
                        + "\"local_search_local_sort_1020\":\"t4\",\"some-new-experiment\":\"t1\"}");
        Set<String> expected = new HashSet<>(Arrays.asList(
                "tabMerge", "local_search_local_sort_1020", "some-new-experiment"));
        assertEquals(3, out.size());
        assertEquals(expected, out.keySet());
        assertEquals("t1", out.get("tabMerge"));
        assertEquals("t4", out.get("local_search_local_sort_1020"));
        assertEquals("t1", out.get("some-new-experiment"));
    }

    @Test
    public void emptyKeyOrEmptyGroup_dropped() {
        assertEquals(Collections.emptyMap(), parse("{\"\":\"t1\"}"));
        assertEquals(Collections.emptyMap(), parse("{\"tabMerge\":\"\"}"));
    }

    @Test
    public void invalidJson_emptyMap() {
        assertEquals(Collections.emptyMap(), parse("{oops"));
        assertEquals(Collections.emptyMap(), parse("[1,2]"));
        assertEquals(Collections.emptyMap(), parse("{\"tabMerge\":}"));
    }

    @Test
    public void nullOrBlankOrEmptyObject_emptyMap() {
        assertEquals(Collections.emptyMap(), parse(null));
        assertEquals(Collections.emptyMap(), parse(""));
        assertEquals(Collections.emptyMap(), parse("   "));
        assertEquals(Collections.emptyMap(), parse("{}"));
    }

    @Test
    public void nonEmptyResult_unmodifiable() {
        Map<String, String> out = parse("{\"tabMerge\":\"t1\"}");
        assertThrows(UnsupportedOperationException.class, () -> out.put("x", "t1"));
    }
}
