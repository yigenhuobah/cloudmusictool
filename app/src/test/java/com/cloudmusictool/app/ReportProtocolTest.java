package com.cloudmusictool.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 诊断回传协议：结构白名单、条数与长度边界、主进程门禁与失败功能计数。
 *
 * 合法形态夹具对齐真实生产者字段面（HookState.buildJson / ExperimentState.buildJson，
 * 见 HookStateJsonContractTest 的生产侧钉子）；篡改用例覆盖：
 * 未知根键、非 Feature 名的 features 键、超长字符串、items 超 MAX_ENTRIES、类型错位。
 */
public class ReportProtocolTest {

    @Test
    public void mountsPayloadAcceptsProducerShape() throws Exception {
        // HookState.buildJson 的字段面：根级汇总 + 每个 Feature 的完整 feature 对象。
        JSONObject features = new JSONObject();
        for (Feature f : Feature.values()) {
            JSONObject o = new JSONObject();
            o.put("id", f.id);
            o.put("enabled", true);
            o.put("expected", 3);
            o.put("installed", 2);
            o.put("failed", 1);
            o.put("criticalFailed", 1);
            o.put("hit", true);
            o.put("hitCount", 12);
            o.put("degraded", false);
            o.put("unavailable", false);
            o.put("lastFailure", "方法未找到");
            features.put(f.name(), o);
        }
        JSONObject root = new JSONObject();
        root.put("updatedAt", System.currentTimeMillis());
        root.put("process", ReportProtocol.MAIN_PROCESS);
        root.put("enabledFeatures", Feature.values().length);
        root.put("installed", 18);
        root.put("expected", 30);
        root.put("failed", 1);
        root.put("noHitFeatures", 0);
        root.put("degradedFeatures", 0);
        root.put("unavailableFeatures", 0);
        root.put("features", features);

        assertNull(ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsTruncatedVariantIsAccepted() throws Exception {
        // HookState.buildJson 超 64KB 时退回摘要：无 features/process，truncated=true。
        JSONObject root = new JSONObject();
        root.put("updatedAt", System.currentTimeMillis());
        root.put("enabledFeatures", 3);
        root.put("installed", 5);
        root.put("expected", 9);
        root.put("failed", 1);
        root.put("noHitFeatures", 0);
        root.put("degradedFeatures", 0);
        root.put("unavailableFeatures", 0);
        root.put("truncated", true);

        assertNull(ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsPayloadAcceptsAbtestRootKey() throws Exception {
        // abtest 是新版本将新增的根键，当前版本没有也合法；其字符串同样受长度约束。
        JSONObject abtest = new JSONObject();
        abtest.put("ad_01_splash", "group_a");
        JSONObject root = mountsRoot();
        root.put("abtest", abtest);

        assertNull(ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsPayloadAcceptsStringAtLengthBoundary() throws Exception {
        JSONObject root = mountsRoot();
        root.getJSONObject("features").put("SPLASH_AD",
                feature().put("lastFailure", repeat('x', ReportProtocol.MAX_VALUE_CHARS)));

        assertNull(ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsPayloadRejectsUnknownRootKey() throws Exception {
        JSONObject root = mountsRoot();
        root.put("sneaky", "any value");

        assertNotNull("未知根键必须整体拒绝",
                ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsPayloadRejectsNonFeatureKey() throws Exception {
        JSONObject root = mountsRoot();
        root.getJSONObject("features").put("NOT_A_FEATURE", feature());

        assertNotNull("features 键必须是 Feature 枚举名",
                ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsPayloadRejectsUnknownFeatureField() throws Exception {
        JSONObject root = mountsRoot();
        root.getJSONObject("features").put("SPLASH_AD",
                feature().put("junk", 1));

        assertNotNull("feature 对象键超出白名单必须拒绝",
                ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsPayloadRejectsOverlongString() throws Exception {
        JSONObject root = mountsRoot();
        root.getJSONObject("features").put("SPLASH_AD",
                feature().put("lastFailure", repeat('x', ReportProtocol.MAX_VALUE_CHARS + 1)));

        assertNotNull("字符串值超过 MAX_VALUE_CHARS 必须拒绝",
                ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void mountsPayloadRejectsTypeMismatch() throws Exception {
        JSONObject notObject = mountsRoot();
        notObject.put("features", "not-an-object");
        assertNotNull("features 必须是对象", ReportProtocol.validateMountsPayload(notObject));

        JSONObject entryNotObject = mountsRoot();
        entryNotObject.getJSONObject("features").put("SPLASH_AD", "not-an-object");
        assertNotNull("feature 条目必须是对象",
                ReportProtocol.validateMountsPayload(entryNotObject));
    }

    @Test
    public void mountsPayloadRejectsNonMainProcess() throws Exception {
        JSONObject root = mountsRoot();
        root.put("process", "com.netease.cloudmusic:play");

        assertNotNull(ReportProtocol.validateMountsPayload(root));
    }

    @Test
    public void experimentsPayloadAcceptsProducerShape() throws Exception {
        // ExperimentState.buildJson 的字段面：updatedAt + items[{key, group, source, count, lastAt}]。
        JSONObject root = new JSONObject();
        root.put("updatedAt", System.currentTimeMillis());
        JSONArray items = new JSONArray();
        items.put(new JSONObject()
                .put("key", "ad_01_splash")
                .put("group", "c")
                .put("source", "unknown")
                .put("count", 3)
                .put("lastAt", System.currentTimeMillis()));
        root.put("items", items);

        assertNull(ReportProtocol.validateExperimentsPayload(root));
    }

    @Test
    public void experimentsPayloadAcceptsExactlyMaxEntries() throws Exception {
        JSONObject root = new JSONObject();
        root.put("updatedAt", System.currentTimeMillis());
        JSONArray items = new JSONArray();
        for (int i = 0; i < ReportProtocol.MAX_ENTRIES; i++) {
            items.put(new JSONObject().put("key", "k" + i).put("count", i));
        }
        root.put("items", items);

        assertNull(ReportProtocol.validateExperimentsPayload(root));
    }

    @Test
    public void experimentsPayloadRejectsMoreThanMaxEntries() throws Exception {
        JSONObject root = new JSONObject();
        root.put("updatedAt", System.currentTimeMillis());
        JSONArray items = new JSONArray();
        for (int i = 0; i <= ReportProtocol.MAX_ENTRIES; i++) {
            items.put(new JSONObject().put("key", "k" + i).put("count", i));
        }
        root.put("items", items);

        assertNotNull("items 超过 MAX_ENTRIES 必须整体拒绝",
                ReportProtocol.validateExperimentsPayload(root));
    }

    @Test
    public void experimentsPayloadRejectsUnknownRootKey() throws Exception {
        JSONObject root = new JSONObject();
        root.put("updatedAt", System.currentTimeMillis());
        root.put("items", new JSONArray());
        root.put("sneaky", 1);

        assertNotNull("未知根键必须整体拒绝",
                ReportProtocol.validateExperimentsPayload(root));
    }

    @Test
    public void experimentsPayloadRejectsOverlongString() throws Exception {
        JSONObject root = new JSONObject();
        root.put("updatedAt", System.currentTimeMillis());
        root.put("items", new JSONArray().put(new JSONObject()
                .put("key", "k")
                .put("group", repeat('g', ReportProtocol.MAX_VALUE_CHARS + 1))));

        assertNotNull("字符串值超过 MAX_VALUE_CHARS 必须拒绝",
                ReportProtocol.validateExperimentsPayload(root));
    }

    @Test
    public void experimentsPayloadRejectsTypeMismatch() throws Exception {
        JSONObject itemsNotArray = new JSONObject();
        itemsNotArray.put("updatedAt", 1L);
        itemsNotArray.put("items", new JSONObject().put("k", 1));
        assertNotNull("items 必须是数组",
                ReportProtocol.validateExperimentsPayload(itemsNotArray));

        JSONObject itemNotObject = new JSONObject();
        itemNotObject.put("updatedAt", 1L);
        itemNotObject.put("items", new JSONArray().put("not-an-object"));
        assertNotNull("数组每条必须是对象",
                ReportProtocol.validateExperimentsPayload(itemNotObject));
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
        assertNotNull(ReportProtocol.validateMountsPayload(null));
        assertNotNull(ReportProtocol.validateExperimentsPayload(null));
    }

    // ------------------------------------------------------------------ helpers

    /** mounts 通道的最小合法根（HookState.buildJson 字段面，features 留空由用例填充）。 */
    private JSONObject mountsRoot() throws Exception {
        JSONObject root = new JSONObject();
        root.put("updatedAt", System.currentTimeMillis());
        root.put("process", ReportProtocol.MAIN_PROCESS);
        root.put("enabledFeatures", 1);
        root.put("installed", 1);
        root.put("expected", 1);
        root.put("failed", 0);
        root.put("noHitFeatures", 0);
        root.put("degradedFeatures", 0);
        root.put("unavailableFeatures", 0);
        root.put("features", new JSONObject());
        return root;
    }

    private JSONObject feature() throws Exception {
        return new JSONObject().put("enabled", true);
    }

    private String repeat(char c, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(c);
        }
        return builder.toString();
    }

    private JSONObject feature(boolean enabled, int installed, String skip) throws Exception {
        JSONObject o = new JSONObject();
        o.put("enabled", enabled);
        o.put("installed", installed);
        o.put("lastFailure", skip);
        return o;
    }
}
