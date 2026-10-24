package com.cloudmusictool.app;

import com.cloudmusictool.app.hooks.StartupBlacklistHooks;
import com.cloudmusictool.app.hooks.StartupOrchestrationHooks;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 启动任务黑名单核心保护名单的语义单测（JVM 反射），覆盖 EX-02 与 PV-03 两侧。
 *
 * EX-02：shouldOrchestrate 是 private static（StartupOrchestrationHooks.java:225-242），
 * 返回 true = 允许编排（priority +1000 / runOnUIThread=false / degradeInit 跳过），
 * false = 保持宿主原行为。
 * PV-03：shouldSkip 是 private static（StartupBlacklistHooks.java），返回 true =
 * needExecute 拦截整个任务，false = 放行原返回。
 * 两侧共用核心任务保护名单（PROTECTED_TASKS + ServiceFacade 前缀，经共享的
 * StartupOrchestrationHooks.isProtected）：命中保护名单时即使被列入黑名单也
 * 不编排/不跳过，变异删除 !isProtected 判定后对应侧用例变红。
 */
public class StartupOrchestrationPolicyTest {

    private static boolean shouldOrchestrate(Set<String> blocklist, Object task)
            throws Exception {
        Method m = StartupOrchestrationHooks.class.getDeclaredMethod(
                "shouldOrchestrate", Set.class, Object.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, blocklist, task);
    }

    private static Set<String> blocklist(String... names) {
        return new HashSet<>(Arrays.asList(names));
    }

    /** taskName() 返回固定名；simpleName 恒为 "FakeTask"（taskNameOf 两路匹配均可驱动）。 */
    public static class FakeTask {
        private final String name;

        FakeTask(String name) {
            this.name = name;
        }

        public String taskName() {
            return name;
        }
    }

    @Test
    public void listedNonProtectedTask_orchestrated() throws Exception {
        assertTrue(shouldOrchestrate(blocklist("AdOaidUploadTask"),
                new FakeTask("AdOaidUploadTask")));
        assertTrue(shouldOrchestrate(blocklist("SomeCustomTask"),
                new FakeTask("SomeCustomTask")));
    }

    @Test
    public void protectedTaskInBlocklist_notOrchestrated() throws Exception {
        // 变异判别输入：删除 !isProtected 判定后本用例变红。
        String[] protectedTasks = {
                "CoreInitTask", "NativeInitTask", "MMKVTask",
                "ServiceFacadeAllTask", "ServiceFacadeMainTask",
                "ServiceFacadeMainManualTask", "ServiceFacadeWithoutAppWidgetTask",
                "WorkPathTask", "SdWorkPathTask", "CookiePreloadTask",
                "NetworkTask", "SessionTask"
        };
        for (String name : protectedTasks) {
            assertFalse("核心任务不应被编排: " + name,
                    shouldOrchestrate(blocklist(name), new FakeTask(name)));
        }
    }

    @Test
    public void serviceFacadePrefixTask_notOrchestrated() throws Exception {
        // PROTECTED_PREFIX = "ServiceFacade"：前缀命中即受保护，
        // 即使黑名单里显式写了 ServiceFacade* 任务也不编排。
        assertFalse(shouldOrchestrate(blocklist("ServiceFacadeFooTask"),
                new FakeTask("ServiceFacadeFooTask")));
        assertFalse(shouldOrchestrate(blocklist("ServiceFacadeMainTask"),
                new FakeTask("ServiceFacadeMainTask")));
    }

    @Test
    public void unlistedTask_notOrchestrated() throws Exception {
        assertFalse(shouldOrchestrate(blocklist("OtherTask"), new FakeTask("NetworkTask")));
        assertFalse(shouldOrchestrate(blocklist("NetworkTask"),
                new FakeTask("AdOaidUploadTask")));
    }

    @Test
    public void emptyBlocklist_notOrchestrated() throws Exception {
        assertFalse(shouldOrchestrate(Collections.<String>emptySet(),
                new FakeTask("SomeTask")));
    }

    @Test
    public void nullTask_notOrchestrated() throws Exception {
        assertFalse(shouldOrchestrate(blocklist("NetworkTask"), null));
    }

    @Test
    public void simpleNameFallback_matchesBlocklist() throws Exception {
        // taskName() 未命中黑名单时按 getSimpleName() 兜底（本类的 simpleName 即 "FakeTask"）。
        assertTrue(shouldOrchestrate(blocklist("FakeTask"), new FakeTask("SomeUnlistedTask")));
    }

    @Test
    public void protectedTaskName_blocksEvenWhenSimpleNameListed() throws Exception {
        // 任一名字（taskName/simpleName）命中保护名单即拒绝编排。
        assertFalse(shouldOrchestrate(blocklist("FakeTask", "NetworkTask"),
                new FakeTask("NetworkTask")));
    }

    // ---------- PV-03 StartupBlacklistHooks.shouldSkip（与 EX-02 共享保护名单） ----------

    private static boolean shouldSkip(Set<String> blocklist, String taskName, String simpleName)
            throws Exception {
        Method m = StartupBlacklistHooks.class.getDeclaredMethod(
                "shouldSkip", Set.class, String.class, String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, blocklist, taskName, simpleName);
    }

    @Test
    public void pv03_blockedUnprotectedTask_skipped() throws Exception {
        // 未受保护的黑名单命中：维持拦截整个任务（needExecute=false）。
        assertTrue(shouldSkip(blocklist("AdOaidUploadTask"), "AdOaidUploadTask", "FakeTask"));
        // taskName 反射提取失败时按 simpleName 兜底匹配，未受保护仍拦截。
        assertTrue(shouldSkip(blocklist("NBLogTask"), null, "NBLogTask"));
    }

    @Test
    public void pv03_blockedProtectedTask_notSkipped() throws Exception {
        // 变异判别输入：PV-03 退回"命中即跳过、无保护名单"旧行为后本用例变红。
        String[] protectedTasks = {
                "CoreInitTask", "NativeInitTask", "MMKVTask",
                "ServiceFacadeAllTask", "ServiceFacadeMainTask",
                "ServiceFacadeMainManualTask", "ServiceFacadeWithoutAppWidgetTask",
                "WorkPathTask", "SdWorkPathTask", "CookiePreloadTask",
                "NetworkTask", "SessionTask"
        };
        for (String name : protectedTasks) {
            assertFalse("核心任务命中黑名单不应被拦截: " + name,
                    shouldSkip(blocklist(name), name, "FakeTask"));
        }
    }

    @Test
    public void pv03_serviceFacadePrefixTask_notSkipped() throws Exception {
        // ServiceFacade 前缀保护与 EX-02 同源：前缀命中即放行原返回。
        assertFalse(shouldSkip(blocklist("ServiceFacadeFooTask"),
                "ServiceFacadeFooTask", "FakeTask"));
    }

    @Test
    public void pv03_unlistedTask_notSkipped() throws Exception {
        // 未命中黑名单：不拦截（保持 needExecute 原返回）。
        assertFalse(shouldSkip(blocklist("OtherTask"), "NetworkTask", "FakeTask"));
    }

    @Test
    public void pv03_protectedTaskName_blocksEvenWhenSimpleNameListed() throws Exception {
        // taskName 受保护即放行（与 EX-02 的 protectedTaskName_blocksEvenWhenSimpleNameListed 对称）。
        assertFalse(shouldSkip(blocklist("FakeTask", "NetworkTask"), "NetworkTask", "FakeTask"));
    }
}
