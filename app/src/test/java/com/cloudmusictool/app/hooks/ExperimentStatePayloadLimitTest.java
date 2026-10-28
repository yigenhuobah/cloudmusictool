package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.ReportProtocol;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * experiments 通道单值上限契约：key/group/source 任一超过
 * {@link ReportProtocol#MAX_VALUE_CHARS} 时，StatusProvider 会整体拒绝 payload，
 * 因此 buildJson 必须整条跳过超长条目并保留其它条目（不能截断成半条）。
 */
public class ExperimentStatePayloadLimitTest {

    @Test
    public void entryAtValueLimit_isAccepted() {
        String value = repeat('k', ReportProtocol.MAX_VALUE_CHARS);
        assertTrue(ExperimentState.isEntryWithinProtocolLimits(value, value, value));
    }

    @Test
    public void keyOverLimit_isRejected() {
        assertFalse(ExperimentState.isEntryWithinProtocolLimits(
                repeat('k', ReportProtocol.MAX_VALUE_CHARS + 1), "g", "s"));
    }

    @Test
    public void groupOverLimit_isRejected() {
        assertFalse(ExperimentState.isEntryWithinProtocolLimits(
                "key", repeat('g', ReportProtocol.MAX_VALUE_CHARS + 1), "s"));
    }

    @Test
    public void sourceOverLimit_isRejected() {
        assertFalse(ExperimentState.isEntryWithinProtocolLimits(
                "key", "g", repeat('s', ReportProtocol.MAX_VALUE_CHARS + 1)));
    }

    @Test
    public void nullGroupAndSource_areAccepted() {
        // buildJson 对 null 使用 "c"/"unknown" 默认值，长度远小于上限。
        assertTrue(ExperimentState.isEntryWithinProtocolLimits("key", null, null));
    }

    /**
     * 端到端契约：超长 key 的条目被跳过，正常条目仍在，且整个 payload 通过
     * ReportProtocol.validateExperimentsPayload（否则 StatusProvider 会整体拒绝）。
     */
    @Test
    public void buildJson_skipsOversizeEntry_keepsOthers() throws Exception {
        String oversizedKey = repeat('x', ReportProtocol.MAX_VALUE_CHARS + 1);
        ExperimentState.record(oversizedKey, "g", "s");
        ExperimentState.record("within-limit", "g", "s");

        JSONObject root = new JSONObject(buildJson());
        assertNull("payload 必须整体通过 experiments 契约",
                ReportProtocol.validateExperimentsPayload(root));

        JSONArray items = root.getJSONArray("items");
        boolean foundNormal = false;
        for (int i = 0; i < items.length(); i++) {
            String key = items.getJSONObject(i).optString("key");
            assertFalse("超长条目必须被整条跳过", oversizedKey.equals(key));
            if ("within-limit".equals(key)) {
                foundNormal = true;
            }
        }
        assertTrue("其它合法条目必须保留", foundNormal);
    }

    private static String buildJson() throws Exception {
        Method m = ExperimentState.class.getDeclaredMethod("buildJson");
        m.setAccessible(true);
        return (String) m.invoke(null);
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
