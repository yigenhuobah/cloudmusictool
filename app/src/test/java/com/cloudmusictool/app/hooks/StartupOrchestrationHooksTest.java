package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import org.junit.Test;

import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

/**
 * EX-02 启动任务编排的 hook 体语义：
 * priority / runOnStage 先 proceed 一次拿到宿主值，后处理失败只返回宿主原值；
 * runOnUIThread / degradeInit 的跳过路径不 proceed，放行路径也只 proceed 一次。
 * 任何路径都不允许二次 proceed（旧写法在 catch 里补一次，宿主方法会执行两遍）。
 */
public class StartupOrchestrationHooksTest {

    private static final Set<String> BLOCKED = Collections.singleton("ListedTask");

    private final HookTestSupport.FakeModule module = new HookTestSupport.FakeModule();

    // ---------------------------------------------------------------- priority

    @Test
    public void priority_proceedsOnceAndAddsPenalty() throws Throwable {
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(new FakeTask("ListedTask"));
        chain.result = 10;

        Object out = StartupOrchestrationHooks.interceptPriority(
                module, Feature.STARTUP_ORCHESTRATION, BLOCKED, chain);

        assertEquals(Integer.valueOf(1010), out);
        assertEquals(1, chain.proceedCount);
    }

    @Test
    public void priority_unlistedTaskKeepsHostValue() throws Throwable {
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(new FakeTask("OtherTask"));
        chain.result = 7;

        assertEquals(7, StartupOrchestrationHooks.interceptPriority(
                module, Feature.STARTUP_ORCHESTRATION, BLOCKED, chain));
        assertEquals(1, chain.proceedCount);
    }

    @Test
    public void priority_hostExceptionPropagatesAfterSingleProceed() {
        RuntimeException boom = new RuntimeException("host priority failed");
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(new FakeTask("ListedTask"));
        chain.failure = boom;

        try {
            StartupOrchestrationHooks.interceptPriority(
                    module, Feature.STARTUP_ORCHESTRATION, BLOCKED, chain);
            fail("宿主 priority() 的异常必须原样抛回");
        } catch (Throwable t) {
            assertSame("第二次 proceed 会覆盖首个异常", boom, t);
        }
        assertEquals(1, chain.proceedCount);
    }

    @Test
    public void priority_postProcessFailureReturnsHostValueWithoutSecondProceed() throws Throwable {
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(new FakeTask("ListedTask"));
        chain.result = 3;
        chain.thisObjectFailure = new IllegalStateException("host object unreadable");

        assertEquals(3, StartupOrchestrationHooks.interceptPriority(
                module, Feature.STARTUP_ORCHESTRATION, BLOCKED, chain));
        assertEquals("后处理异常时不得再 proceed 一次", 1, chain.proceedCount);
    }

    // ---------------------------------------------------------------- runOnStage

    @Test
    public void runOnStage_proceedsOnceAndKeepsHostStage() throws Throwable {
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(new FakeTask("ListedTask"));
        chain.result = 2;

        assertEquals(2, StartupOrchestrationHooks.interceptRunOnStage(
                module, Feature.STARTUP_ORCHESTRATION, BLOCKED, chain));
        assertEquals(1, chain.proceedCount);
    }

    @Test
    public void runOnStage_hostExceptionPropagatesAfterSingleProceed() {
        RuntimeException boom = new RuntimeException("host runOnStage failed");
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(new FakeTask("ListedTask"));
        chain.failure = boom;

        try {
            StartupOrchestrationHooks.interceptRunOnStage(
                    module, Feature.STARTUP_ORCHESTRATION, BLOCKED, chain);
            fail("宿主 runOnStage() 的异常必须原样抛回");
        } catch (Throwable t) {
            assertSame(boom, t);
        }
        assertEquals(1, chain.proceedCount);
    }

    @Test
    public void runOnStage_postProcessFailureKeepsHostStageWithoutSecondProceed() throws Throwable {
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(new FakeTask("ListedTask"));
        chain.result = 5;
        chain.thisObjectFailure = new IllegalStateException("host object unreadable");

        assertEquals(5, StartupOrchestrationHooks.interceptRunOnStage(
                module, Feature.STARTUP_ORCHESTRATION, BLOCKED, chain));
        assertEquals(1, chain.proceedCount);
    }

    // ---------------------------------------------------------------- runOnUIThread

    @Test
    public void runOnUiThread_listedTaskReturnsFalseWithoutProceed() throws Throwable {
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(new FakeTask("ListedTask"));

        assertEquals(Boolean.FALSE, StartupOrchestrationHooks.interceptRunOnUiThread(
                module, Feature.STARTUP_ORCHESTRATION, BLOCKED, chain));
        assertEquals(0, chain.proceedCount);
    }

    @Test
    public void runOnUiThread_unlistedTaskProceedsOnce() throws Throwable {
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(new FakeTask("OtherTask"));
        chain.result = Boolean.TRUE;

        assertEquals(Boolean.TRUE, StartupOrchestrationHooks.interceptRunOnUiThread(
                module, Feature.STARTUP_ORCHESTRATION, BLOCKED, chain));
        assertEquals(1, chain.proceedCount);
    }

    // ---------------------------------------------------------------- degradeInit

    @Test
    public void degradeInit_listedTaskSkipsWithoutProceed() throws Throwable {
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(new FakeTask("ListedTask"));

        assertNull(StartupOrchestrationHooks.interceptDegradeInit(
                module, Feature.STARTUP_ORCHESTRATION, BLOCKED, chain));
        assertEquals(0, chain.proceedCount);
    }

    @Test
    public void degradeInit_unlistedTaskProceedsOnce() throws Throwable {
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(new FakeTask("OtherTask"));

        assertEquals("host-result", StartupOrchestrationHooks.interceptDegradeInit(
                module, Feature.STARTUP_ORCHESTRATION, BLOCKED, chain));
        assertEquals(1, chain.proceedCount);
    }

    /** taskName() 返回黑名单里的名字；simpleName 恒为 FakeTask。 */
    public static class FakeTask {
        private final String name;

        FakeTask(String name) {
            this.name = name;
        }

        public String taskName() {
            return name;
        }
    }
}
