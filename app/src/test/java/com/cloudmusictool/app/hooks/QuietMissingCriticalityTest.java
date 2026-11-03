package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.HookState;
import com.cloudmusictool.app.profile.Profile9608;
import com.cloudmusictool.app.profile.ProfileRegistry;
import com.netease.cloudmusic.music.biz.setting.activity.SettingActivity;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 深度复核 id 2 回归：quiet 定位失败时 markQuietMissing 不能固定 critical=false。
 * Profile 标记为 critical 的挂载点（这里用 UI-08 SettingActivity#V8）缺失时必须
 * 进入 criticalFailed，并在 finish 后触发 degraded 横幅。
 *
 * <p>使用真实 9.6.08 Profile 与独立 Feature，避免与其它用例共享失败键。
 */
public class QuietMissingCriticalityTest {

    private final HookTestSupport.FakeModule module = new HookTestSupport.FakeModule();

    @Before
    public void resetProfile() {
        ProfileRegistry.setActive(null);
    }

    @After
    public void clearProfile() {
        ProfileRegistry.setActive(null);
    }

    @Test
    public void quietMissingProfileCriticalMountEntersCriticalFailed() throws Exception {
        // 真实 9.6.08 Profile：UI-08 SettingActivity#V8 为 critical。
        ProfileRegistry.setActive(Profile9608.PROFILE);

        Feature f = Feature.AUTOPLAY;
        JSONObject before = featureJson(f);
        int failedBefore = before.optInt("failed", 0);
        int criticalBefore = before.optInt("criticalFailed", 0);

        HookState.enable(f);
        try {
            assertNull("夹具没有 V8()，quiet 定位必须失败",
                    HookKit.findMethodQuiet(module, f, "SettingActivity#V8",
                            SettingActivity.class, "V8"));
        } finally {
            HookState.finish(f);
        }

        JSONObject after = featureJson(f);
        assertEquals("quiet 挂载点缺失要记账", failedBefore + 1, after.getInt("failed"));
        assertEquals("Profile 标记 critical 的 quiet 挂载点缺失必须计入 criticalFailed",
                criticalBefore + 1, after.getInt("criticalFailed"));
        assertTrue("critical 失败必须触发 degraded 横幅", after.getBoolean("degraded"));
    }

    // ---------------------------------------------------------------- 工具

    private static JSONObject featureJson(Feature f) throws Exception {
        Method m = HookState.class.getDeclaredMethod("buildJson");
        m.setAccessible(true);
        String json = (String) m.invoke(null);
        JSONObject features = new JSONObject(json).optJSONObject("features");
        JSONObject feature = features == null ? null : features.optJSONObject(f.name());
        return feature == null ? new JSONObject() : feature;
    }
}
