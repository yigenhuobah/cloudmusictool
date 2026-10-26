package com.cloudmusictool.app.hooks;

import com.cloudmusictool.app.profile.Profile9608;
import com.cloudmusictool.app.profile.Profile9613;

import org.junit.Test;

import java.lang.reflect.Method;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * EN-01 代理页分支方法选择：9.6.13 的 kc()Z 优先，9.6.08 的 oc()Z 回退；
 * 9.6.13 的 oc()V 只启动协程，必须被「零参 + boolean 返回」条件拒绝。
 */
public class MineBackgroundProxyTest {

    public static final class ModernProxy {
        public boolean kc() {
            return true;
        }

        public void oc() {
            // 9.6.13: oc()V 只启动协程，不能作为分支 hook 目标。
        }
    }

    public static final class LegacyProxy {
        public boolean oc() {
            return true;
        }
    }

    public static final class VoidOcOnlyProxy {
        public void oc() {
        }
    }

    public static final class AmbiguousProxy {
        public boolean kc() {
            return true;
        }

        public boolean kc(String ignored) {
            return true;
        }
    }

    private static Method select(Class<?> cls) {
        return MineBackgroundHooks.selectProxyBranchMethod(cls, "kc", "oc");
    }

    @Test
    public void modernProxy_prefersKcBooleanOverVoidOc() {
        Method m = select(ModernProxy.class);
        assertEquals("kc", m.getName());
        assertEquals(boolean.class, m.getReturnType());
    }

    @Test
    public void legacyProxy_fallsBackToOcBoolean() {
        Method m = select(LegacyProxy.class);
        assertEquals("oc", m.getName());
        assertEquals(boolean.class, m.getReturnType());
    }

    @Test
    public void voidOcAlone_isNeverSelected() {
        assertNull("oc()V 没有返回值，hook 返回 false 无意义，必须拒绝",
                select(VoidOcOnlyProxy.class));
    }

    @Test
    public void overloadedZeroArgBoolean_isStillSelected() {
        Method m = select(AmbiguousProxy.class);
        assertEquals("kc", m.getName());
        assertEquals(0, m.getParameterTypes().length);
    }

    @Test
    public void fullScreenPath_isVersionGated() {
        // 9.6.13 只有 pe(Activity)，不得调用 W1(boolean)/kd(String)；
        // 9.6.08/未知版本保留 pe()/D1()/W1(true)/kd("custom_bg")。
        assertTrue(MineBackgroundHooks.use9613FullScreen(Profile9613.PROFILE));
        assertFalse(MineBackgroundHooks.use9613FullScreen(Profile9608.PROFILE));
        assertFalse(MineBackgroundHooks.use9613FullScreen(null));
    }
}
