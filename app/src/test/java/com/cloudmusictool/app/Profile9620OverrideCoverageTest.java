package com.cloudmusictool.app;

import com.cloudmusictool.app.profile.MountSpec;
import com.cloudmusictool.app.profile.Profile9615;
import com.cloudmusictool.app.profile.Profile9620;
import com.cloudmusictool.app.profile.TargetProfile;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Profile9620 的覆盖完整性与 remove 语义。
 *
 * <p>applyOverrides 按 mount id 从 overrides() 取替换 spec，key 打错字会被
 * 静默忽略（与 Profile9613 相同）——对应 hook 在 9.6.20 上会继续用 9.6.15
 * 的旧坐标，静态 check-mounts 能发现但单测更早。这里：
 * 1) 反射读私有 overrides()，断言恰好 27 个 key，且每个 key 都命中
 *    Profile9615 基表并真正替换进 PROFILE；
 * 2) 断言 PROFILE 删除了 PV-05 的 d/e，总挂载点为 143；
 * 3) 对类改名的条目断言 candidates[0] 是新类、历史类名仍保留
 *    （HookKit.findClassSpec(旧名) 仍能命中）。
 */
public class Profile9620OverrideCoverageTest {

    private static final int EXPECTED_OVERRIDES = 27;
    private static final int EXPECTED_MOUNTS = 143;

    @SuppressWarnings("unchecked")
    private static Map<String, MountSpec> overrides() throws Exception {
        Method m = Profile9620.class.getDeclaredMethod("overrides");
        m.setAccessible(true);
        return (Map<String, MountSpec>) m.invoke(null);
    }

    private static MountSpec specIn(TargetProfile profile, String id) {
        for (MountSpec spec : profile.mounts) {
            if (id.equals(spec.id)) {
                return spec;
            }
        }
        return null;
    }

    /** MountSpec 无 equals，按字段比较（id 已由调用方保证一致，可比其余坐标）。 */
    private static void assertSpecEquals(String context, MountSpec expected, MountSpec actual) {
        assertEquals(context + " logicalName", expected.logicalName, actual.logicalName);
        assertEquals(context + " methodName", expected.methodName, actual.methodName);
        assertEquals(context + " returnType", expected.returnType, actual.returnType);
        assertTrue(context + " classCandidates",
                Arrays.equals(expected.classCandidates, actual.classCandidates));
        assertTrue(context + " paramTypes",
                Arrays.equals(expected.paramTypes, actual.paramTypes));
        assertTrue(context + " anchors", Arrays.equals(expected.anchors, actual.anchors));
        assertEquals(context + " critical", expected.critical, actual.critical);
    }

    @Test
    public void everyOverrideKeyHits9615BaseAndReplacesProfileMount() throws Exception {
        Map<String, MountSpec> overrides = overrides();
        assertEquals("9.6.20 的覆盖数量必须与 anchors.json 的 @9.6.20 变体一致",
                EXPECTED_OVERRIDES, overrides.size());

        Set<String> baseIds = new HashSet<>();
        for (MountSpec spec : Profile9615.PROFILE.mounts) {
            baseIds.add(spec.id);
        }

        for (Map.Entry<String, MountSpec> e : overrides.entrySet()) {
            String key = e.getKey();
            assertTrue("override id 不在 9.6.15 基表(将静默 miss): " + key,
                    baseIds.contains(key));
            assertEquals("override spec 自身 id 应与 key 一致: " + key,
                    key, e.getValue().id);
            MountSpec inProfile = specIn(Profile9620.PROFILE, key);
            assertNotNull("override 未替换进 PROFILE: " + key, inProfile);
            assertSpecEquals("override 覆盖未生效: " + key, e.getValue(), inProfile);
        }
    }

    @Test
    public void profileDropsMergedLogMonitorDAndE() {
        assertEquals(EXPECTED_MOUNTS, Profile9620.PROFILE.mounts.size());
        assertNull(specIn(Profile9620.PROFILE, "PV-05/LogMonitorConfigManager#d"));
        assertNull(specIn(Profile9620.PROFILE, "PV-05/LogMonitorConfigManager#e"));
        // b/c 仍然保留（c 是 9.6.20 合并后的唯一解析入口）。
        assertNotNull(specIn(Profile9620.PROFILE, "PV-05/LogMonitorConfigManager#b"));
        assertNotNull(specIn(Profile9620.PROFILE, "PV-05/LogMonitorConfigManager#c"));
    }

    @Test
    public void renamedClassesKeepHistoricalCandidates() throws Exception {
        Map<String, MountSpec> overrides = overrides();
        assertCandidates(overrides, "UI-01/fw0.e#q", "lu0.e", "gu0.e", "fw0.e");
        assertCandidates(overrides, "PV-06/utils.h2#d",
                "com.netease.cloudmusic.utils.t2",
                "com.netease.cloudmusic.utils.i2",
                "com.netease.cloudmusic.utils.h2");
        assertCandidates(overrides, "UI-04/dr.h#T0", "kq.h", "iq.h", "dr.h");
        assertCandidates(overrides, "UI-05/th0.h#h", "wf0.h", "tf0.h", "th0.h");
        assertCandidates(overrides, "UI-05/rh0.c#c", "uf0.c", "rf0.c", "rh0.c");
        assertCandidates(overrides, "UI-05/tf0.o$a#y", "wf0.o$a", "tf0.o$a");
        assertCandidates(overrides, "UI-07/nr2.c#checkAndShowYouthDialog",
                "on2.c", "en2.c", "nr2.c");
        assertCandidates(overrides, "UI-08/jn#m0",
                "com.netease.cloudmusic.utils.vn",
                "com.netease.cloudmusic.utils.jn");
        assertCandidates(overrides, "UI-08/tt0.d#a", "zr0.d", "ur0.d", "tt0.d");
        assertCandidates(overrides, "UI-08/tt0.d#d", "zr0.d", "ur0.d", "tt0.d");
        assertCandidates(overrides, "UI-08/vt0.r#j", "bs0.r", "wr0.r", "vt0.r");
        assertCandidates(overrides, "UI-08/lv0.j#e", "rt0.j", "mt0.j", "lv0.j");
        assertCandidates(overrides, "UI-09/jn#L",
                "com.netease.cloudmusic.utils.vn",
                "com.netease.cloudmusic.utils.jn");
        assertCandidates(overrides, "EN-01/BgInfoParser#B",
                "yb2.e$a", "nb2.e$a", "pc2.e$a");
        assertCandidates(overrides, "EN-01/BgStore#g", "yb2.f", "nb2.f", "pc2.f");
        assertCandidates(overrides, "EN-01/BgStore#s", "yb2.f", "nb2.f", "pc2.f");
        assertCandidates(overrides, "EN-01/sg#s",
                "com.netease.cloudmusic.utils.eh",
                "com.netease.cloudmusic.utils.sg");
    }

    private static void assertCandidates(Map<String, MountSpec> overrides, String id,
                                         String... expected) {
        MountSpec spec = overrides.get(id);
        assertNotNull("override 缺失: " + id, spec);
        assertTrue(id + " classCandidates 必须保留历史类名: "
                        + Arrays.toString(spec.classCandidates),
                Arrays.equals(expected, spec.classCandidates));
        assertEquals(id + " candidates[0] 必须是新类", expected[0], spec.primaryClass());
    }

    @Test
    public void sameClassOverridesUseExpectedMethodNames() throws Exception {
        Map<String, MountSpec> overrides = overrides();
        assertEquals("w1", overrides.get("AD-07/CommentAdapter#y1").methodName);
        assertEquals("p", overrides.get("AD-08/PlayerAdJarDelegateImpl#q").methodName);
        assertEquals("z", overrides.get("AD-09/PodcastVideoAdHelperImpl#z").methodName);
        assertEquals("v", overrides.get("ABTEST/abtest2.b#x").methodName);
        assertEquals("cc", overrides.get("UI-04/BaseArkV3Fragment#hc").methodName);
        assertEquals("Oc", overrides.get("UI-04/BaseArkV3Fragment#Tc").methodName);
        assertEquals("Gc", overrides.get("UI-04/BaseArkV3Fragment#Lc").methodName);
        assertEquals("jc", overrides.get("UI-14/LyricVideoTipsDialog#oc").methodName);
        assertEquals("jc", overrides.get("EN-01/MyMusicProxyFragment#kc").methodName);
        assertFalse("AD-09 的 z 不是 critical",
                overrides.get("AD-09/PodcastVideoAdHelperImpl#z").critical);
    }
}
