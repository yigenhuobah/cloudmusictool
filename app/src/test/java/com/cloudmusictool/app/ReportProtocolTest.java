package com.cloudmusictool.app;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 诊断回传协议：key 白名单、value 长度、hit 合并与失败功能计数。
 */
public class ReportProtocolTest {

    @Test
    public void keyWhitelistAcceptsOnlyKnownPrefixes() {
        assertTrue(ReportProtocol.isValidKey("reg.ad_01_splash"));
        assertTrue(ReportProtocol.isValidKey("hit.ad_01_splash"));
        assertTrue(ReportProtocol.isValidKey("skip.ui_06_hints"));
        assertFalse(ReportProtocol.isValidKey(null));
        assertFalse(ReportProtocol.isValidKey(""));
        assertFalse(ReportProtocol.isValidKey("reg."));
        assertFalse(ReportProtocol.isValidKey("hit"));
        assertFalse(ReportProtocol.isValidKey("other.ad_01"));
        assertFalse(ReportProtocol.isValidKey("reg.bad key"));
    }

    @Test
    public void valueLengthIsBounded() {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < ReportProtocol.MAX_VALUE_CHARS; i++) {
            value.append('x');
        }
        assertTrue(ReportProtocol.isValidValue(value.toString()));
        value.append('x');
        assertFalse(ReportProtocol.isValidValue(value.toString()));
        assertFalse(ReportProtocol.isValidValue(null));
    }

    @Test
    public void hitMergeKeepsHistoricalMaximum() {
        assertEquals(12, ReportProtocol.mergeHit(12, 3));
        assertEquals(12, ReportProtocol.mergeHit(3, 12));
        assertEquals(0, ReportProtocol.mergeHit(0, 0));
        assertEquals(7, ReportProtocol.mergeHit(-1, 7));
    }

    @Test
    public void payloadLengthIsBounded() {
        assertFalse(ReportProtocol.isValidPayload(null));
        assertFalse(ReportProtocol.isValidPayload(""));
        assertTrue(ReportProtocol.isValidPayload("{}"));
        StringBuilder payload = new StringBuilder();
        for (int i = 0; i < ReportProtocol.MAX_PAYLOAD_CHARS; i++) {
            payload.append('x');
        }
        assertTrue(ReportProtocol.isValidPayload(payload.toString()));
        payload.append('x');
        assertFalse(ReportProtocol.isValidPayload(payload.toString()));
    }

    @Test
    public void mainProcessReportDetection() throws Exception {
        JSONObject main = new JSONObject();
        main.put("process", ReportProtocol.MAIN_PROCESS);
        assertTrue(ReportProtocol.isMainProcessReport(main));
        assertTrue("旧版无 process 字段按主进程兼容",
                ReportProtocol.isMainProcessReport(new JSONObject()));

        JSONObject play = new JSONObject();
        play.put("process", "com.netease.cloudmusic:play");
        assertFalse(ReportProtocol.isMainProcessReport(play));
        assertFalse(ReportProtocol.isMainProcessReport("not-json"));
        assertFalse(ReportProtocol.isMainProcessReport((JSONObject) null));
    }

    @Test
    public void failedFeatureCountCountsEnabledFailuresOnly() throws Exception {
        JSONObject features = new JSONObject();
        features.put("SPLASH_AD", feature(true, 0, "类未找到"));
        features.put("FEED_AD", feature(true, 3, "方法未找到"));
        features.put("COMMENT_AD", feature(true, 2, ""));
        features.put("CLIPBOARD", feature(false, 0, "类未找到"));

        JSONObject root = new JSONObject();
        root.put("process", ReportProtocol.MAIN_PROCESS);
        root.put("features", features);

        assertEquals("已启用且 installed=0 或 skip 非空计为失败", 2,
                ReportProtocol.failedFeatureCount(root));
    }

    @Test
    public void failedFeatureCountIgnoresSecondaryProcess() throws Exception {
        JSONObject features = new JSONObject();
        features.put("AUDIO_CLIP_AD", feature(true, 0, "类未找到"));
        JSONObject root = new JSONObject();
        root.put("process", "com.netease.cloudmusic:play");
        root.put("features", features);

        assertEquals(0, ReportProtocol.failedFeatureCount(root));
    }

    @Test
    public void failedFeatureCountHandlesMissingData() {
        assertEquals(0, ReportProtocol.failedFeatureCount(null));
        assertEquals(0, ReportProtocol.failedFeatureCount(new JSONObject()));
    }

    @Test
    public void malformedJsonIsRejected() {
        assertFalse(ReportProtocol.isMainProcessReport("{"));
        assertFalse(ReportProtocol.isMainProcessReport("not-json"));
        assertFalse(ReportProtocol.isMainProcessReport("[]"));
        assertFalse(ReportProtocol.isMainProcessReport("null"));
        assertFalse(ReportProtocol.isMainProcessReport("{\"process\":"));
        assertFalse(ReportProtocol.isMainProcessReport("   "));
    }

    @Test
    public void nullAndEmptyStringInputsAreRejected() {
        assertFalse(ReportProtocol.isMainProcessReport((String) null));
        assertFalse(ReportProtocol.isMainProcessReport(""));
        assertEquals(0, ReportProtocol.failedFeatureCount((JSONObject) null));
    }

    @Test
    public void keySuffixLengthIsBounded() {
        StringBuilder suffix = new StringBuilder();
        for (int i = 0; i < 128; i++) {
            suffix.append('a');
        }
        assertTrue(ReportProtocol.isValidKey("reg." + suffix));
        assertFalse(ReportProtocol.isValidKey("reg." + suffix + "a"));
        assertFalse(ReportProtocol.isValidKey("hit." + suffix + "a"));
        assertFalse(ReportProtocol.isValidKey("skip." + suffix + "a"));
    }

    @Test
    public void unknownKeyShapesAreRejected() {
        assertFalse(ReportProtocol.isValidKey("foo.ad_01_splash"));
        assertFalse(ReportProtocol.isValidKey("registration.ad_01"));
        assertFalse(ReportProtocol.isValidKey("reg"));
        assertFalse(ReportProtocol.isValidKey("reg.\t"));
        assertTrue(ReportProtocol.isValidKey("hit.x"));
    }

    @Test
    public void multiProcessHitMergeKeepsHistoricalMaximum() {
        int merged = 0;
        merged = ReportProtocol.mergeHit(merged, 5); // 主进程首次回传
        merged = ReportProtocol.mergeHit(merged, 9); // :play 进程回传
        merged = ReportProtocol.mergeHit(merged, 7); // 主进程再次回传
        assertEquals("多进程 hit 只能取历史最大值", 9, merged);
        assertEquals(Integer.MAX_VALUE,
                ReportProtocol.mergeHit(Integer.MAX_VALUE, 0));
        assertEquals(Integer.MAX_VALUE,
                ReportProtocol.mergeHit(0, Integer.MAX_VALUE));
    }

    @Test
    public void valueLengthCountsUnicodeChars() {
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < ReportProtocol.MAX_VALUE_CHARS; i++) {
            value.append('\u4e2d');
        }
        assertTrue(ReportProtocol.isValidValue(value.toString()));
        value.append('\u4e2d');
        assertFalse(ReportProtocol.isValidValue(value.toString()));
    }

    private JSONObject feature(boolean enabled, int installed, String skip) throws Exception {
        JSONObject o = new JSONObject();
        o.put("enabled", enabled);
        o.put("installed", installed);
        o.put("lastFailure", skip);
        return o;
    }
}
