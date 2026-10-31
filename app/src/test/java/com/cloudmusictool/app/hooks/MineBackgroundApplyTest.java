package com.cloudmusictool.app.hooks;

import android.app.Activity;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * EN-01 apply 管线：缓存命中决策、解码在途时的补偿贴图、Profile 模型注入与
 * pe(Activity) 一次性初始化的成功判定。
 *
 * 全部是纯 JVM 可测的判定/反射逻辑（不依赖 Android 运行时；Activity 传 null）。
 */
public class MineBackgroundApplyTest {

    // ------------------------------------------------------------ cache policy

    @Test
    public void cacheDecision_missingBitmapAlwaysReloads() {
        assertTrue(MineBackgroundHooks.shouldReloadForSource(false, false, false));
        assertTrue(MineBackgroundHooks.shouldReloadForSource(false, true, false));
    }

    @Test
    public void cacheDecision_providerFingerprintNeverBlocksMainThread() {
        // Provider 兜底指纹需要跨进程 query：主线程先贴缓存，校验交给后台线程，
        // 不得因为「疑似过期」在主线程同步查询。
        assertFalse(MineBackgroundHooks.shouldReloadForSource(true, true, true));
    }

    @Test
    public void cacheDecision_externalFingerprintStillGatesReload() {
        assertTrue(MineBackgroundHooks.shouldReloadForSource(true, false, true));
        assertFalse(MineBackgroundHooks.shouldReloadForSource(true, false, false));
    }

    @Test
    public void pendingApply_reappliesOnlyForNewManager() {
        Object inFlight = new Object();
        assertFalse("没有排队请求时不重复贴图",
                MineBackgroundHooks.shouldReapplyAfterLoad(inFlight, null));
        assertFalse("同一 manager 已由当前加载贴图",
                MineBackgroundHooks.shouldReapplyAfterLoad(inFlight, inFlight));
        assertTrue("解码期间重建页面（新 manager）必须补一次贴图",
                MineBackgroundHooks.shouldReapplyAfterLoad(inFlight, new Object()));
    }

    // ------------------------------------------------------- profile injection

    @Test
    public void injectFullScreenModelFields_setsAllFields() {
        FakeInfo info = new FakeInfo();
        FakeValue value = new FakeValue();
        assertTrue(MineBackgroundHooks.injectFullScreenModelFields(info, value));
        assertEquals(4, info.validType);
        assertNotNull(value.fullPicValue);
        assertTrue(value.latestSetPicTime > 0);
    }

    @Test
    public void injectFullScreenModelFields_missingSetterReportsFailure() {
        // 反射坐标漂移时调用方必须拿到 false，不能再记 hit 把失败显示成成功。
        assertFalse("setValidType 缺失",
                MineBackgroundHooks.injectFullScreenModelFields(new Object(), new FakeValue()));
        assertFalse("setLatestSetPicTime 缺失",
                MineBackgroundHooks.injectFullScreenModelFields(new FakeInfo(), new ValueWithoutTime()));
        assertFalse(MineBackgroundHooks.injectFullScreenModelFields(null, new FakeValue()));
        assertFalse(MineBackgroundHooks.injectFullScreenModelFields(new FakeInfo(), null));
    }

    // ---------------------------------------------------------- pe(Activity)

    @Test
    public void peActivity_failureIsRetriedOnNextApply() {
        FlakyPeFragment fragment = new FlakyPeFragment();
        MineBackgroundHooks.applyPeActivityOnce(fragment, null);
        MineBackgroundHooks.applyPeActivityOnce(fragment, null);
        assertEquals("pe 首次抛异常不得记为已初始化，下一次 apply 必须重试",
                2, fragment.calls);
    }

    @Test
    public void peActivity_successIsAppliedOnlyOnce() {
        StablePeFragment fragment = new StablePeFragment();
        MineBackgroundHooks.applyPeActivityOnce(fragment, null);
        MineBackgroundHooks.applyPeActivityOnce(fragment, null);
        assertEquals("同一 Fragment 成功初始化后不得重复调用", 1, fragment.calls);
    }

    @Test
    public void peActivity_missingMethodIsIgnored() {
        MineBackgroundHooks.applyPeActivityOnce(new Object(), null);
    }

    public static final class FlakyPeFragment {
        int calls;

        public void pe(Activity activity) {
            calls++;
            if (calls == 1) {
                throw new IllegalStateException("view not ready");
            }
        }
    }

    public static final class StablePeFragment {
        int calls;

        public void pe(Activity activity) {
            calls++;
        }
    }

    public static final class FakeInfo {
        int validType;

        public void setValidType(int type) {
            validType = type;
        }
    }

    public static final class FakeValue {
        String fullPicValue;
        long latestSetPicTime;

        public void setFullPicValue(String value) {
            fullPicValue = value;
        }

        public void setLatestSetPicTime(long time) {
            latestSetPicTime = time;
        }
    }

    public static final class ValueWithoutTime {
        public void setFullPicValue(String value) {
        }
    }
}
