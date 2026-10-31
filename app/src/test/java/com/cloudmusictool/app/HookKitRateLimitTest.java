package com.cloudmusictool.app;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * HookKit.hit() 限流键解耦（performance-3）的纯 JVM 单测。
 *
 * HookKit 是 com.cloudmusictool.app.hooks 包私有 final class，按仓库既有做法
 * （HookKitKeepFilterTest）用反射驱动，不为测试放宽生产代码可见性；日志经
 * XposedModule 的匿名子类 + attachFramework(动态代理) 承接。HIT_STATE 与清表告警标志均为静态，
 * @Before 里重置，用例之间互不依赖执行顺序。
 *
 * 覆盖四点：
 * 1) 限流键 = label：同一 label 不同 detail 不增 entry，打日志时仍完整拼 detail；
 * 2) 旧 3 参签名取 detail 首个稳定 token 作键，动态计数/标题不再拆键；
 * 3) 快速路径：窗口内第二次 hit 不进 map 写路径、不触碰日志通道；
 * 4) 无界治理：entry 超 512 整表清空重建，清空告警 warning-once。
 */
public class HookKitRateLimitTest {

    private static final String FEATURE_ID = "AD-01"; // Feature.SPLASH_AD 的 id
    private static final int LOG_WARN = 5;            // android.util.Log.WARN（编译期内联）

    private static final class LogCall {
        final int priority;
        final String msg;

        LogCall(int priority, String msg) {
            this.priority = priority;
            this.msg = msg;
        }
    }

    private Map<String, long[]> mState;
    private List<LogCall> mLogged;
    private XposedModule mModule;

    @Before
    public void setUp() throws Exception {
        mState = hitState();
        mState.clear();
        resetClearWarnedFlag();
        mLogged = new CopyOnWriteArrayList<>();
        mModule = loggingModule(mLogged);
    }

    /**
     * HIT_STATE_CLEAR_WARNED 是进程级 once 标志，全仓无生产入口可重置；用例内显式重置，
     * 使「恰好一次清表告警」不依赖本进程里哪个测试类先触发过清表。
     */
    private static void resetClearWarnedFlag() throws Exception {
        Class<?> kit = Class.forName("com.cloudmusictool.app.hooks.HookKit");
        Field f = kit.getDeclaredField("HIT_STATE_CLEAR_WARNED");
        f.setAccessible(true);
        ((AtomicBoolean) f.get(null)).set(false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, long[]> hitState() throws Exception {
        Class<?> kit = Class.forName("com.cloudmusictool.app.hooks.HookKit");
        Field f = kit.getDeclaredField("HIT_STATE");
        f.setAccessible(true);
        return (Map<String, long[]>) f.get(null);
    }

    /** XposedModule 借 no-arg 构造 + attachFramework(代理) 承接日志，代理只记录 log。 */
    private static XposedModule loggingModule(final List<LogCall> sink) throws Exception {
        XposedInterface base = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if ("log".equals(method.getName())) {
                            sink.add(new LogCall((Integer) args[0], (String) args[2]));
                            return null;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    }
                });
        XposedModule module = new XposedModule() {
        };
        module.attachFramework(base, new Runnable() {
            @Override
            public void run() {
            }
        });
        return module;
    }

    private static void hit(XposedModule module, String label, String detail) throws Exception {
        Method m = hookKitMethod("hit",
                XposedModule.class, Feature.class, String.class, String.class);
        m.invoke(null, module, Feature.SPLASH_AD, label, detail);
    }

    private static void hitLegacy(XposedModule module, String detail) throws Exception {
        Method m = hookKitMethod("hit", XposedModule.class, Feature.class, String.class);
        m.invoke(null, module, Feature.SPLASH_AD, detail);
    }

    private static Method hookKitMethod(String name, Class<?>... params) throws Exception {
        Class<?> cls = Class.forName("com.cloudmusictool.app.hooks.HookKit");
        Method m = cls.getDeclaredMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    /** 限流键 = label：同一 label 不同 detail 共享 entry 且窗口内只打一条；不同 label 各自成键。 */
    @Test
    public void sameLabelDifferentDetailSharesEntry() throws Exception {
        hit(mModule, "feed", "blockCodeOrderList filtered 3/10");
        // 生产用 System.currentTimeMillis() 判窗口：第二次 hit 前把窗口起点钉到当前时刻，
        // 避免两次 hit 之间的真实停顿超过 5 秒时误走慢路径。
        mState.get(FEATURE_ID + "|feed")[0] = System.currentTimeMillis();
        hit(mModule, "feed", "blockCodeOrderList filtered 5/12");
        assertEquals(1, mState.size());
        assertEquals(1, mLogged.size());
        assertTrue(mState.containsKey(FEATURE_ID + "|feed"));

        hit(mModule, "other", "blockCodeOrderList filtered 3/10");
        assertEquals(2, mState.size());
        assertTrue(mState.containsKey(FEATURE_ID + "|other"));
    }

    /** 窗口内第二次 hit 走快速路径：map 不增、限流状态不写、module 为 null 也不抛错。 */
    @Test
    public void secondHitInWindowTakesFastPath() throws Exception {
        hit(mModule, "fast", "d1");
        long[] state = mState.get(FEATURE_ID + "|fast");
        // 窗口判定读真实时钟：在第二次 hit 前钉住窗口起点，不再依赖两次调用之间的耗时。
        state[0] = System.currentTimeMillis();
        long lastLoggedAt = state[0];

        hit(mModule, "fast", "d2");
        assertEquals(1, mState.size());
        assertEquals(lastLoggedAt, state[0]);
        assertEquals(1L, state[1]);
        assertEquals(1, mLogged.size());

        // 预置窗口内状态后以 null module 命中：快速路径不触碰日志通道，不抛 NPE。
        mState.put(FEATURE_ID + "|quiet", new long[]{System.currentTimeMillis(), 1L});
        hit(null, "quiet", "d");
        assertEquals(2, mState.size());
        assertEquals(1, mLogged.size());
    }

    /** 窗口外重打：限流键仍不含 detail（同 label 不产生新 entry），日志完整拼 detail。 */
    @Test
    public void outsideWindowSameLabelStaysOneEntry() throws Exception {
        hit(mModule, "slow", "d1");
        long[] state = mState.get(FEATURE_ID + "|slow");
        state[0] = System.currentTimeMillis() - 6000L; // 人为老化出 5 秒窗口

        hit(mModule, "slow", "d2");
        assertEquals(1, mState.size());
        assertEquals(2, mLogged.size());
        // 打日志时仍完整拼 detail（不含 label），键不随 detail 分裂。
        assertEquals(FEATURE_ID + " hit d2", mLogged.get(1).msg);
        assertEquals(2L, state[1]);
    }

    /** 旧签名（只有 detail）：限流键取 detail 的首个稳定 token，动态 payload 不再拆键。 */
    @Test
    public void legacySignatureUsesStableDetailPrefix() throws Exception {
        hitLegacy(mModule, "blockCodeOrderList filtered 3/10");
        hitLegacy(mModule, "blockCodeOrderList filtered 5/12");
        assertEquals(1, mState.size());
        assertTrue(mState.containsKey(FEATURE_ID + "|blockCodeOrderList"));
        assertEquals(1, mLogged.size());
        // 打日志时仍完整保留 detail（只压缩限流键，不裁剪日志）。
        assertEquals(FEATURE_ID + " hit blockCodeOrderList filtered 3/10",
                mLogged.get(0).msg);

        // 不同挂载点的首 token 不同：各自成键、各自首次必打。
        hitLegacy(mModule, "resources filtered 1/10");
        assertEquals(2, mState.size());
        assertTrue(mState.containsKey(FEATURE_ID + "|resources"));
        assertEquals(2, mLogged.size());
    }

    /** Feature 为 null（ABTest / 引导钩子路径）：hit 不再解引用 f.id。 */
    @Test
    public void nullFeatureHitUsesAbTestPrefix() throws Exception {
        Method m = hookKitMethod("hit",
                XposedModule.class, Feature.class, String.class, String.class);
        m.invoke(null, mModule, null, "label", "detail");
        assertEquals(1, mState.size());
        assertTrue(mState.containsKey("ABTEST|label"));
        assertEquals("ABTEST hit detail", mLogged.get(0).msg);
    }

    /** 无界治理：entry 超 512 整表清空重建，清空 WARN 只打一次。 */
    @Test
    public void overThresholdClearsTableAndWarnsOnce() throws Exception {
        seed(513);
        hit(mModule, "fresh", "trigger");
        assertEquals(1, mState.size());
        assertTrue(mState.containsKey(FEATURE_ID + "|fresh"));
        int warnsAfterFirst = countWarns();
        assertEquals(1, warnsAfterFirst);

        seed(513); // 再次超阈值：只清空，不再告警
        hit(mModule, "fresh2", "trigger2");
        assertEquals(1, mState.size());
        assertEquals(warnsAfterFirst, countWarns());
    }

    private void seed(int entries) {
        for (int i = 0; i < entries; i++) {
            mState.put(FEATURE_ID + "|seed" + i, new long[]{0L, 1L});
        }
    }

    private int countWarns() {
        int n = 0;
        for (LogCall c : mLogged) {
            if (c.priority == LOG_WARN) {
                n++;
            }
        }
        return n;
    }
}
