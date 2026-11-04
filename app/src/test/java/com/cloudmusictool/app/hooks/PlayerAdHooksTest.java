package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import org.json.JSONObject;
import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * AD-08 兜底定位语义（finding 45）：adCls 缺失时不再退化为“按参数个数盲扫”——
 * 同名同参数个数但返回类型不同的无关方法不会被整体 no-op，定位失败必须登记
 * （修复前 findByNameAndArity 失败无日志、install(null) 静默 return false）。
 */
public class PlayerAdHooksTest {

    private static final Feature F = Feature.PLAYER_AD;

    private final HookTestSupport.FakeModule module = new HookTestSupport.FakeModule();

    /** 与 q 同名同参数个数但返回 boolean 的无关方法：修复前会被整体 no-op。 */
    public static final class WrongReturnQ {
        public boolean q(Object ad) {
            return true;
        }
    }

    /** 形状与返回类型都匹配的候选。 */
    public static final class MatchedQ {
        public void q(Object ad) {
        }
    }

    @Test
    public void findAdMethod_rejectsWrongReturnTypeAndRegistersFailure() throws Exception {
        int before = failedCount(F);

        Method found = PlayerAdHooks.findAdMethod(module, F, WrongReturnQ.class, "q",
                void.class, null, 1, "PlayerAdJarDelegateImpl#q");

        assertNull("返回类型不符的候选不得被挂载", found);
        assertEquals("缺失必须登记，不能静默失效", before + 1, failedCount(F));
        assertTrue(logContains(module, "return type mismatch"));
    }

    @Test
    public void findAdMethod_acceptsMatchingCandidateWithoutBookkeeping() throws Exception {
        Method probe = MatchedQ.class.getDeclaredMethod("q", Object.class);
        int before = failedCount(F);

        Method found = PlayerAdHooks.findAdMethod(module, F, MatchedQ.class, "q",
                void.class, null, 1, "PlayerAdJarDelegateImpl#q");

        // getDeclaredMethod 每次返回新副本，Method.equals 按声明类+名字+参数比较。
        assertEquals("形状匹配的候选必须命中", probe, found);
        assertEquals("命中不得登记失败", before, failedCount(F));
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

    /** SEARCH_AD/PLAYER_AD 等 Feature 的 failed 计数；buildJson 是包私有入口，跨包测试用反射读取。 */
    private static int failedCount(Feature f) throws Exception {
        Method build = com.cloudmusictool.app.HookState.class.getDeclaredMethod("buildJson", int.class);
        build.setAccessible(true);
        String json = (String) build.invoke(null, 1 << 20);
        JSONObject features = new JSONObject(json).optJSONObject("features");
        JSONObject state = features == null ? null : features.optJSONObject(f.name());
        return state == null ? 0 : state.optInt("failed", 0);
    }
}
