package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * UI-02 启动延迟：
 *
 * 1) 兜底查找必须先校验签名 —— findMethodQuiet / findByNameQuiet 只按名/参数取方法，
 *    自写 lambda 又不受 HookKit.validateReturnType 约束，返回 Long 交给非 long 方法会崩；
 * 2) checkDelayDoAction 的替代实现直接 run() 宿主 Runnable，Runnable 的异常必须原样抛回，
 *    不能用 proceed 兜底（isNeedDelayStart 已被置 false，proceed 会立刻再执行一次）。
 */
public class StartupDelayHooksTest {

    private final HookTestSupport.FakeModule module = new HookTestSupport.FakeModule();

    // ---------------------------------------------------------------- id 21

    @Test
    public void delayTimeSignature_acceptsLongAndBoxedLong() throws Exception {
        assertTrue(StartupDelayHooks.isDelayTimeMethod(method("delayLong", long.class, String.class)));
        assertTrue(StartupDelayHooks.isDelayTimeMethod(method("delayBoxed", Long.class, String.class)));
    }

    @Test
    public void delayTimeSignature_rejectsDriftedSignature() throws Exception {
        assertFalse("返回 String 时返回 0L 会被框架抛 ClassCastException",
                StartupDelayHooks.isDelayTimeMethod(method("delayString", String.class, String.class)));
        assertFalse("参数漂移时不能挂",
                StartupDelayHooks.isDelayTimeMethod(method("delayIntArg", long.class, int.class)));
        assertFalse(StartupDelayHooks.isDelayTimeMethod(method("delayNoArg", long.class)));
        assertFalse(StartupDelayHooks.isDelayTimeMethod(null));
    }

    @Test
    public void checkDelaySignature_requiresVoidRunnableString() throws Exception {
        assertTrue(StartupDelayHooks.isCheckDelayMethod(
                method("checkOk", void.class, Runnable.class, String.class)));
        assertFalse("非 void 不能整段替换",
                StartupDelayHooks.isCheckDelayMethod(
                        method("checkBool", boolean.class, Runnable.class, String.class)));
        assertFalse("参数漂移会让 args.get(0) 不是 Runnable，静默丢掉红屏动作",
                StartupDelayHooks.isCheckDelayMethod(method("checkOneArg", void.class, Runnable.class)));
        assertFalse(StartupDelayHooks.isCheckDelayMethod(
                method("checkInt", void.class, int.class, String.class)));
    }

    // ---------------------------------------------------------------- id 22

    @Test
    public void checkDelay_runsRunnableAndSkipsBusinessBody() throws Throwable {
        AtomicInteger runs = new AtomicInteger();
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(null);
        chain.args.add((Runnable) runs::incrementAndGet);
        chain.args.add("action");

        Object result = StartupDelayHooks.interceptCheckDelay(module, Feature.STARTUP_DELAY, chain);

        assertNull("跳过宿主业务体", result);
        assertEquals(1, runs.get());
        assertEquals("proceed 会让同一启动动作再跑一次", 0, chain.proceedCount);
    }

    @Test
    public void checkDelay_runnableFailurePropagatesWithoutProceed() {
        IllegalStateException boom = new IllegalStateException("action failed");
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(null);
        chain.args.add((Runnable) () -> {
            throw boom;
        });
        chain.args.add("action");

        try {
            StartupDelayHooks.interceptCheckDelay(module, Feature.STARTUP_DELAY, chain);
            fail("Runnable 自身的异常必须原样抛回");
        } catch (Throwable t) {
            assertSame(boom, t);
        }
        assertEquals("catch 里不得补 proceed，否则启动动作被执行两次", 0, chain.proceedCount);
    }

    @Test
    public void checkDelay_unexpectedArgStillProceedsExactlyOnce() throws Throwable {
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(null);
        chain.args.add("not-a-runnable");

        assertEquals("host-result",
                StartupDelayHooks.interceptCheckDelay(module, Feature.STARTUP_DELAY, chain));
        assertEquals(1, chain.proceedCount);
    }

    // ---------------------------------------------------------------- 延迟时间 hook 体

    @Test
    public void delayTime_returnsZeroWithoutProceed() {
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(null);

        assertEquals(Long.valueOf(0L),
                StartupDelayHooks.interceptDelayTime(module, Feature.STARTUP_DELAY, chain));
        assertEquals(0, chain.proceedCount);
    }

    private static Method method(String name, Class<?> returnType, Class<?>... params) throws Exception {
        return Fixture.class.getDeclaredMethod(name, params);
    }

    @SuppressWarnings("unused")
    private static final class Fixture {
        static long delayLong(String key) {
            return 0L;
        }

        static Long delayBoxed(String key) {
            return 0L;
        }

        static String delayString(String key) {
            return null;
        }

        static long delayIntArg(int key) {
            return 0L;
        }

        static long delayNoArg() {
            return 0L;
        }

        static void checkOk(Runnable action, String key) {
        }

        static boolean checkBool(Runnable action, String key) {
            return true;
        }

        static void checkOneArg(Runnable action) {
        }

        static void checkInt(int action, String key) {
        }
    }
}
