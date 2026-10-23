package com.cloudmusictool.app;

import com.cloudmusictool.app.hooks.Hooks;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Hook 注册表完整性：所有非 blocked Feature 必须有 installer，blocked Feature 不得注册。
 *
 * Hooks 位于 com.cloudmusictool.app.hooks，installerKeys() 按设计是 package-private；
 * 本测试按任务约定放在 com.cloudmusictool.app 包，因此用反射读取键集合，
 * 不为了测试放宽生产代码可见性。
 */
public class HookRegistryTest {

    @Test
    public void everyNonBlockedFeatureHasInstaller() throws Exception {
        Set<Feature> registered = installerKeys();
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                continue;
            }
            assertTrue("缺少 installer: " + f.id + " (" + f.key + ")",
                    registered.contains(f));
        }
    }

    @Test
    public void blockedFeaturesAreNotInstalled() throws Exception {
        Set<Feature> registered = installerKeys();
        int blocked = 0;
        for (Feature f : Feature.values()) {
            if (!f.blocked()) {
                continue;
            }
            blocked++;
            assertFalse("blocked Feature 不应注册 installer: " + f.id,
                    registered.contains(f));
        }
        assertTrue("应至少有一个 blocked Feature 用于校验该规则", blocked > 0);
    }

    @Test
    public void registryIsNonEmptyAndUnique() throws Exception {
        Set<Feature> registered = installerKeys();
        assertFalse("installer 注册表为空", registered.isEmpty());
        assertEquals("installer 键必须唯一",
                registered.size(), EnumSet.copyOf(registered).size());
        assertFalse(registered.contains(null));
    }

    @SuppressWarnings("unchecked")
    private static Set<Feature> installerKeys() throws Exception {
        Method method = Hooks.class.getDeclaredMethod("installerKeys");
        method.setAccessible(true);
        return (Set<Feature>) method.invoke(null);
    }
}
