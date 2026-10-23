package com.cloudmusictool.app;

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
 * EX-02 StartupOrchestrationHooks.shouldOrchestrate 的语义单测（JVM 反射）。
 *
 * shouldOrchestrate 是 private static（StartupOrchestrationHooks.java:231-248）：
 * 返回 true = 允许编排（priority +1000 / runOnUIThread=false / degradeInit 跳过），
 * false = 保持宿主原行为。核心任务保护名单（PROTECTED_TASKS + ServiceFacade 前缀）
 * 命中时即使被列入黑名单也不编排——这是 EX-02 侧（PV-03 命中即整任务跳过、
 * 无保护名单）唯一的防护，变异删除 !isProtected 后本用例变红。
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
}
