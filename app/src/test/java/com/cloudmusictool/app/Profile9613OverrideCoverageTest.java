package com.cloudmusictool.app;

import com.cloudmusictool.app.profile.MountSpec;
import com.cloudmusictool.app.profile.Profile9608;
import com.cloudmusictool.app.profile.Profile9613;
import com.cloudmusictool.app.profile.ProfileAdPv;
import com.cloudmusictool.app.profile.ProfileUi;
import com.cloudmusictool.app.profile.TargetProfile;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Profile9613.applyOverrides 的覆盖完整性。
 *
 * applyOverrides 按 mount id 从 overrides() 取替换 spec，overrides 里多余的 id
 * 会被静默忽略（Profile9613.java:204）——override key 打错字时对应 hook 在
 * 9.6.13 上整体落空，且只能事后在诊断页看到 skip 原因。这里：
 * 1) 反射读私有 overrides()，断言每个 key 都命中 9.6.08 基表与 9.6.13 基础
 *    分片（build 里传给 applyOverrides 的实际列表）中同 id 的 mount，且覆盖
 *    确实替换进了 PROFILE；
 * 2) 对 8 个此前零断言的漂移 override 补名字级断言（清单见 repro-testgaps-3）。
 */
public class Profile9613OverrideCoverageTest {

    @SuppressWarnings("unchecked")
    private static Map<String, MountSpec> overrides() throws Exception {
        Method m = Profile9613.class.getDeclaredMethod("overrides");
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
    public void everyOverrideKeyHitsBaseMount() throws Exception {
        Map<String, MountSpec> overrides = overrides();
        assertFalse("overrides 不应为空", overrides.isEmpty());

        Set<String> baseIds = new HashSet<>();
        for (MountSpec spec : Profile9608.PROFILE.mounts) {
            baseIds.add(spec.id);
        }
        // 9.6.13 的基础分片 = build() 里 applyOverrides 的实际入参。
        Set<String> shardIds = new HashSet<>();
        for (MountSpec spec : ProfileAdPv.mounts()) {
            shardIds.add(spec.id);
        }
        for (MountSpec spec : ProfileUi.mounts(true)) {
            shardIds.add(spec.id);
        }

        for (Map.Entry<String, MountSpec> e : overrides.entrySet()) {
            String key = e.getKey();
            assertTrue("override id 不在 9.6.08 基表(将静默 miss): " + key,
                    baseIds.contains(key));
            assertTrue("override id 不在 9.6.13 基础分片(将静默 miss): " + key,
                    shardIds.contains(key));
            assertEquals("override spec 自身 id 应与 key 一致: " + key,
                    key, e.getValue().id);
            MountSpec inProfile = specIn(Profile9613.PROFILE, key);
            assertNotNull("override 未替换进 PROFILE: " + key, inProfile);
            assertSpecEquals("override 覆盖未生效: " + key, e.getValue(), inProfile);
        }
    }

    /**
     * 断言一个类/方法漂移 override 真实生效：PROFILE 中该 id 的 spec 用
     * 9.6.13 新类名作主候选、新方法名，且旧类名保留为候选
     * （HookKit.findClassSpec(旧名) 仍能命中）、旧方法名经 findByLogicalName 仍可达。
     */
    private static void assertDrift(String id, String oldClass, String oldMethod,
                                    String newClass, String newMethod) {
        MountSpec spec = specIn(Profile9613.PROFILE, id);
        assertNotNull("PROFILE 缺少 mount: " + id, spec);
        assertEquals(id + " 方法名应为 9.6.13 新名", newMethod, spec.methodName);
        assertEquals(id + " classCandidates[0] 应为 9.6.13 新类",
                newClass, spec.primaryClass());
        assertTrue(id + " 应保留旧类候选 " + oldClass,
                Arrays.asList(spec.classCandidates).contains(oldClass));
        MountSpec byOld = Profile9613.PROFILE.findByLogicalName(newClass, oldMethod);
        assertNotNull("findByLogicalName(" + newClass + ", " + oldMethod + ") 未命中", byOld);
        assertEquals(id + " 旧名查询应映射到新方法", newMethod, byOld.methodName);
    }

    @Test
    public void eightUncoveredDriftOverrides_areEffective() {
        // 以下 8 个 override 在 ProfileMethodMappingTest 中零断言（repro-testgaps-3 清单）。
        // 变异判别：override key 改错字后 PROFILE 保留 9.6.08 坐标，新类名断言变红。
        assertDrift("UI-01/fw0.e#q", "fw0.e", "q", "gu0.e", "q");
        assertDrift("PV-06/utils.h2#d", "com.netease.cloudmusic.utils.h2", "d",
                "com.netease.cloudmusic.utils.i2", "d");
        assertDrift("UI-05/th0.h#h", "th0.h", "h", "tf0.h", "h");
        assertDrift("UI-05/rh0.c#c", "rh0.c", "c", "rf0.c", "c");
        assertDrift("UI-07/nr2.c#checkAndShowYouthDialog", "nr2.c", "checkAndShowYouthDialog",
                "en2.c", "checkAndShowYouthDialog");
        assertDrift("UI-08/tt0.d#a", "tt0.d", "a", "ur0.d", "a");
        assertDrift("UI-08/tt0.d#d", "tt0.d", "d", "ur0.d", "d");
        assertDrift("UI-08/vt0.r#j", "vt0.r", "j", "wr0.r", "j");
    }

    /**
     * UI-05 的 9.6.13 专属 tab 列表构建点：只能追加在 Profile9613.build()，
     * 不能出现在共享 ProfileUi / 9.6.08 基表（9.6.08 没有 tf0.o$a）。
     */
    @Test
    public void ui05TabListBuildMount_is9613Only() {
        MountSpec spec = specIn(Profile9613.PROFILE, "UI-05/tf0.o$a#y");
        assertNotNull("PROFILE 缺少 UI-05/tf0.o$a#y", spec);
        assertEquals("tf0.o$a", spec.primaryClass());
        assertEquals(1, spec.classCandidates.length);
        assertEquals("y", spec.methodName);
        assertEquals("V", spec.returnType);
        assertArrayEquals(new String[]{"Ljava/util/List;", "Z"}, spec.paramTypes);
        assertFalse("tf0.o$a#y 不是 critical（9.6.08 没有该类）", spec.critical);
        assertNull("9.6.08 不应有 tf0.o$a#y",
                specIn(Profile9608.PROFILE, "UI-05/tf0.o$a#y"));
    }

    /**
     * EN-01 9.6.13 专属第二层调度点：9.6.08 的 oc()Z 改名 kc()Z；sg.s 只做
     * 只读探针。两者都不能进共享 ProfileUi（9.6.08 没有 kc，且 sg 的坐标未在
     * 9.6.08 核对）。
     */
    @Test
    public void en01ProxyDispatchMounts_are9613Only() {
        MountSpec kc = specIn(Profile9613.PROFILE, "EN-01/MyMusicProxyFragment#kc");
        assertNotNull("PROFILE 缺少 EN-01/MyMusicProxyFragment#kc", kc);
        assertEquals("com.netease.cloudmusic.fragment.MyMusicProxyFragment",
                kc.primaryClass());
        assertEquals("kc", kc.methodName);
        assertEquals("Z", kc.returnType);
        assertArrayEquals(new String[0], kc.paramTypes);
        assertTrue("kc()Z 是 EN-01 第二层调度点，应为 critical", kc.critical);
        assertNull("9.6.08 的 kc 签名不同，不能共享该坐标",
                specIn(Profile9608.PROFILE, "EN-01/MyMusicProxyFragment#kc"));

        MountSpec sg = specIn(Profile9613.PROFILE, "EN-01/sg#s");
        assertNotNull("PROFILE 缺少 EN-01/sg#s", sg);
        assertEquals("com.netease.cloudmusic.utils.sg", sg.primaryClass());
        assertEquals("s", sg.methodName);
        assertEquals("Z", sg.returnType);
        assertArrayEquals(new String[]{"Lorg/json/JSONObject;"}, sg.paramTypes);
        assertFalse("sg.s 只做只读探针，不是 critical", sg.critical);
        assertNull("9.6.08 不共享 sg.s 坐标",
                specIn(Profile9608.PROFILE, "EN-01/sg#s"));
    }
}
