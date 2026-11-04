package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * UI-14 的选项气泡挂载点（finding 42）：宿主 LyricViewContainer#checkAndShowLrcOptionHint()
 * 只有 return-void，没有可拦截逻辑，因此标记 unsupported 且不安装。回归点：
 * 该挂载点必须把原因写进日志/诊断，且不得被计成 failed（不是失败，也不是无声消失）。
 */
public class LyricsHintHooksTest {

    private final HookTestSupport.FakeModule module = new HookTestSupport.FakeModule();

    @Test
    public void optionHintMount_markedUnsupportedWithoutCountingFailure() throws Exception {
        Feature f = Feature.LYRICS_HINT;
        int failedBefore = failedCount(f);
        int expectedBefore = expectedCount(f);

        LyricsHintHooks.markOptionHintUnsupported(module, f);

        assertTrue("诊断里必须出现 unsupported 标记", logContains(module, "unsupported"));
        assertTrue("标记必须指明挂载点", logContains(module, "checkAndShowLrcOptionHint"));
        assertEquals("已知无逻辑的挂载点不计入 failed", failedBefore, failedCount(f));
        assertEquals("不再安装也不得虚增 expected", expectedBefore, expectedCount(f));
        // 宿主空方法无法在纯 JVM 单测里加载，安装路径由真机 dexsql 验证
        //（9.6.08/9.6.13/9.6.15 的 checkAndShowLrcOptionHint()V 均为 opcode_count=1、0x0e）。
    }

    // ------------------------------------------------------------------
    // 断言辅助
    // ------------------------------------------------------------------

    private static boolean logContains(HookTestSupport.FakeModule module, String fragment) {
        for (String log : module.logs) {
            if (log.contains(fragment)) {
                return true;
            }
        }
        return false;
    }

    /** UI-14 的诊断快照；buildJson 是包私有入口，跨包测试用反射读取。 */
    private static JSONObject featureJson(Feature f) throws Exception {
        Method build = com.cloudmusictool.app.HookState.class.getDeclaredMethod("buildJson", int.class);
        build.setAccessible(true);
        String json = (String) build.invoke(null, 1 << 20);
        JSONObject features = new JSONObject(json).optJSONObject("features");
        JSONObject state = features == null ? null : features.optJSONObject(f.name());
        return state == null ? new JSONObject() : state;
    }

    private static int failedCount(Feature f) throws Exception {
        return featureJson(f).optInt("failed", 0);
    }

    private static int expectedCount(Feature f) throws Exception {
        return featureJson(f).optInt("expected", 0);
    }
}
