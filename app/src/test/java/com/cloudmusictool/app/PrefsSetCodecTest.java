package com.cloudmusictool.app;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * {@link PrefsSetCodec} 的跨进程格式契约测试。
 *
 * 目标进程 Config.loadSet 与模块 App ConfigStore 共用本类：字符串兜底格式
 * 必须可逆，元素含逗号/首尾空格/空串时不能被拆分或丢弃；同时必须继续
 * 读取旧版本写下的逗号分隔 CSV。
 */
public class PrefsSetCodecTest {

    @Test
    public void encodedForm_roundTripsCommasSpacesAndEmptyItems() {
        Set<String> value = new LinkedHashSet<>(Arrays.asList("a,b", " c ", "", "d,,e"));
        assertEquals(value, PrefsSetCodec.parse(PrefsSetCodec.toCsv(value)));
    }

    @Test
    public void encodedForm_roundTripsEmptySet() {
        assertEquals(Collections.emptySet(),
                PrefsSetCodec.parse(PrefsSetCodec.toCsv(Collections.emptySet())));
    }

    @Test
    public void encodedForm_preservesSingleEmptyItem() {
        assertEquals(Collections.singleton(""),
                PrefsSetCodec.parse(PrefsSetCodec.toCsv(Collections.singleton(""))));
    }

    @Test
    public void simpleValues_keepLegacyCsvForOldReaders() {
        Set<String> value = new LinkedHashSet<>(Arrays.asList("a", "b", "c"));
        assertEquals("a,b,c", PrefsSetCodec.toCsv(value));
        assertEquals(value, PrefsSetCodec.parse(PrefsSetCodec.toCsv(value)));
    }

    @Test
    public void legacyCsv_stillParsedWithTrimAndEmptyDropped() {
        assertEquals(new LinkedHashSet<>(Arrays.asList("a", "b", "c")),
                PrefsSetCodec.parse(" a , b ,, c "));
    }

    @Test
    public void legacyValueWithJsonLikePrefix_keptAsSingleItem() {
        // 旧值恰好以 json: 开头但不是 JSON 字符串数组时，仍按旧 CSV 语义读取。
        assertEquals(Collections.singleton("json:home"), PrefsSetCodec.parse("json:home"));
        assertEquals(Collections.singleton("json:[1]"), PrefsSetCodec.parse("json:[1]"));
    }

    @Test
    public void nullOrEmpty_meansUnset() {
        assertNull(PrefsSetCodec.parse(null));
        assertNull(PrefsSetCodec.parse(""));
    }
}
