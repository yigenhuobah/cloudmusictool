package com.cloudmusictool.app;

import com.cloudmusictool.app.anchor.AnchorTable;
import com.cloudmusictool.app.profile.MountSpec;
import com.cloudmusictool.app.profile.Profile9608;
import com.cloudmusictool.app.profile.Profile9613;
import com.cloudmusictool.app.profile.Profile9615;
import com.cloudmusictool.app.profile.Profile9620;
import com.cloudmusictool.app.profile.TargetProfile;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 运行时 Profile* 与 anchors.json 派生 Profile 的逐项等价（保守切换策略的门闩）。
 *
 * <p>当前运行时仍走编译期 {@link Profile9608}/{@link Profile9613}/{@link Profile9615}/{@link Profile9620}，
 * 但 anchors.json 才是唯一来源。本测试按顺序逐项比较 id、类候选、方法名、
 * 返回/参数类型、anchors 与 critical，任何一侧漂移都会失败：
 * - 只改 anchors.json：提醒同步运行时 Profile（或改用 AnchorTable 派生）；
 * - 只改运行时 Profile：提醒回写 anchors.json。
 *
 * <p>顺序也参与比较：anchors.json 的 base + 覆盖 + 追加顺序与运行时
 * Profile9613.build() 的拼装顺序一致，保证 findClassSpec/findMethodSpec
 * 的首个命中语义不因数据源切换而变化。
 */
public class ProfileAnchorEquivalenceTest {

    private static TargetProfile derived(String versionName) {
        AnchorTable table = AnchorTable.load(ProfileAnchorEquivalenceTest.class.getClassLoader());
        assertNotNull("anchors.json 必须在测试 classpath 上", table);
        AnchorTable.Version version = table.versionByName(versionName);
        assertNotNull("anchors.json 缺少版本 " + versionName, version);
        return version.profile;
    }

    @Test
    public void profile9608MatchesAnchors() {
        assertEquivalent(Profile9608.PROFILE, derived("9.6.08"));
    }

    @Test
    public void profile9613MatchesAnchors() {
        assertEquivalent(Profile9613.PROFILE, derived("9.6.13"));
    }

    @Test
    public void profile9615MatchesAnchors() {
        assertEquivalent(Profile9615.PROFILE, derived("9.6.15"));
    }

    @Test
    public void profile9620MatchesAnchors() {
        assertEquivalent(Profile9620.PROFILE, derived("9.6.20"));
    }

    @Test
    public void profile9620DropsLogMonitorDAndE() {
        TargetProfile profile = derived("9.6.20");
        for (MountSpec spec : profile.mounts) {
            assertTrue("9.6.20 不应包含已合并的挂载点: " + spec.id,
                    !"PV-05/LogMonitorConfigManager#d".equals(spec.id)
                            && !"PV-05/LogMonitorConfigManager#e".equals(spec.id));
        }
    }

    @Test
    public void profile9615InheritsSameMountsAs9613() {
        // anchors.json 里 9.6.15 以 base=9.6.13 继承；派生结果也必须完全一致。
        assertSameMounts(derived("9.6.13"), derived("9.6.15"), "9.6.15");
    }

    private static void assertEquivalent(TargetProfile runtime, TargetProfile derived) {
        assertEquals(runtime.versionName + " versionCode", runtime.versionCode,
                derived.versionCode);
        assertEquals(runtime.versionName + " versionName", runtime.versionName,
                derived.versionName);
        assertSameMounts(runtime, derived, runtime.versionName);
    }

    private static void assertSameMounts(TargetProfile expectedProfile,
                                         TargetProfile actualProfile, String context) {
        assertEquals(context + " mount count", expectedProfile.mounts.size(),
                actualProfile.mounts.size());
        List<MountSpec> expected = expectedProfile.mounts;
        List<MountSpec> actual = actualProfile.mounts;
        for (int i = 0; i < expected.size(); i++) {
            MountSpec left = expected.get(i);
            MountSpec right = actual.get(i);
            String item = context + "[" + i + "] " + left.id;
            assertEquals(item + " id", left.id, right.id);
            assertEquals(item + " featureId", left.featureId, right.featureId);
            assertEquals(item + " logicalName", left.logicalName, right.logicalName);
            assertEquals(item + " methodName", left.methodName, right.methodName);
            assertEquals(item + " returnType", left.returnType, right.returnType);
            assertTrue(item + " classCandidates",
                    Arrays.equals(left.classCandidates, right.classCandidates));
            assertTrue(item + " paramTypes",
                    Arrays.equals(left.paramTypes, right.paramTypes));
            assertTrue(item + " anchors",
                    Arrays.equals(left.anchors, right.anchors));
            assertEquals(item + " critical", left.critical, right.critical);
        }
    }
}
