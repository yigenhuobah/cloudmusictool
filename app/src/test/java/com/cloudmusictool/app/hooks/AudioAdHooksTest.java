package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.Feature;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * AD-09 音频贴片 hook 体语义：
 * 1. invokeCallbackNull 必须先 setAccessible 再反射调用（宿主 Kotlin lambda 是包级私有，
 *    直接 invoke 会抛 IllegalAccessException），且只有真的通知了回调才允许记 callback(null)；
 * 2. d 的 hook 体先通知回调再跳过业务体，proceed 不得出现在跳过路径；
 * 3. 同名同参数个数但返回类型不同的无关方法不得被整体 no-op，缺失必须登记。
 */
public class AudioAdHooksTest {

    private static final Feature F = Feature.AUDIO_CLIP_AD;

    private final HookTestSupport.FakeModule module = new HookTestSupport.FakeModule();

    /** 宿主回调的最小同构体：public 方法、声明类 private（Kotlin lambda 的常见形态）。 */
    private static final class PrivateCallback {
        Object received = "not-called";

        public void invoke(Object value) {
            received = value;
        }
    }

    /** invoke 自身抛异常的回调：失败必须可见，不能被静默吞掉。 */
    private static final class ThrowingCallback {
        public void invoke(Object value) {
            throw new IllegalStateException("host boom");
        }
    }

    @Test
    public void callbackIsNotifiedWithNullEvenForPrivateImplClass() {
        PrivateCallback callback = new PrivateCallback();

        assertTrue("setAccessible 后回调应真正执行", AudioAdHooks.invokeCallbackNull(module, F, callback));
        assertNull("回调收到的必须是 null", callback.received);
    }

    @Test
    public void nullCallback_reportsNotNotified() {
        assertFalse(AudioAdHooks.invokeCallbackNull(module, F, null));
    }

    @Test
    public void hostInvokeFailure_isLoggedNotSwallowed() {
        assertFalse(AudioAdHooks.invokeCallbackNull(module, F, new ThrowingCallback()));
        assertTrue(logContains(module, "callback(null) failed"));
    }

    @Test
    public void missingInvokeMethod_isReported() {
        assertFalse(AudioAdHooks.invokeCallbackNull(module, F, new Object()));
        assertTrue(logContains(module, "invoke(Object) not found"));
    }

    @Test
    public void dHookBody_notifiesCallbackThenSkipsWithoutProceed() throws Throwable {
        PrivateCallback callback = new PrivateCallback();
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(null);
        // 与 d(String,int,long,Function1,Continuation) 同构的入参。
        chain.args.add("clip-id");
        chain.args.add(1);
        chain.args.add(2L);
        chain.args.add(callback);
        chain.args.add(null);

        Object result = AudioAdHooks.interceptAudioClipD(module, F, chain);

        assertNull("业务体被跳过", result);
        assertNull("回调必须先按契约收到 null", callback.received);
        assertEquals("跳过路径不得 proceed", 0, chain.proceedCount);
    }

    @Test
    public void dHookBody_unreadableArgs_fallsBackToSingleProceed() throws Throwable {
        HookTestSupport.FakeChain chain = new HookTestSupport.FakeChain(null);

        Object result = AudioAdHooks.interceptAudioClipD(module, F, chain);

        assertEquals("参数不可读时回退宿主实现", "host-result", result);
        assertEquals("回退路径 proceed 恰好一次", 1, chain.proceedCount);
    }

    @Test
    public void findAdMethod_rejectsUnrelatedVoidMethodWithSameShape() throws Exception {
        // 与 d 同名、同前导参数、同参数个数但返回 void 的无关方法：修复前会被整体 no-op。
        int before = failedCount(F);
        java.lang.reflect.Method m = AudioAdHooks.findAdMethod(module, F,
                UnrelatedVoidD.class, "d", Object.class,
                new Class<?>[]{String.class, int.class, long.class}, 5, "AdAudioClipManager#d");

        assertNull("无关 void 方法不得被挂载", m);
        assertEquals("缺失必须登记，不能静默失效", before + 1, failedCount(F));
        assertTrue(logContains(module, "method not found"));
    }

    @Test
    public void findAdMethod_acceptsMatchingShapeWithoutBookkeeping() throws Exception {
        java.lang.reflect.Method probe = MatchedD.class.getDeclaredMethod("d",
                String.class, int.class, long.class, Object.class, Object.class);
        int before = failedCount(F);

        java.lang.reflect.Method found = AudioAdHooks.findAdMethod(module, F,
                MatchedD.class, "d", Object.class,
                new Class<?>[]{String.class, int.class, long.class}, 5, "AdAudioClipManager#d");

        assertEquals("形状匹配的候选必须命中", probe, found);
        assertEquals("命中不得登记失败", before, failedCount(F));
    }

    /** 与 d 同名同参数个数但返回 void 的无关方法。 */
    public static final class UnrelatedVoidD {
        public void d(String id, int kind, long seq, Object callback, Object continuation) {
        }
    }

    /** 形状与返回类型都匹配的候选。 */
    public static final class MatchedD {
        public Object d(String id, int kind, long seq, Object callback, Object continuation) {
            return null;
        }
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

    private static int failedCount(Feature f) throws Exception {
        Field states = com.cloudmusictool.app.HookState.class.getDeclaredField("sStates");
        states.setAccessible(true);
        Object state = ((Map<?, ?>) states.get(null)).get(f);
        if (state == null) {
            return 0;
        }
        Field failed = state.getClass().getDeclaredField("failed");
        failed.setAccessible(true);
        return ((AtomicInteger) failed.get(state)).get();
    }
}
