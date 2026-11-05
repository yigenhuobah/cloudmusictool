package com.cloudmusictool.app;

import com.cloudmusictool.app.profile.MountSpec;
import com.cloudmusictool.app.profile.Profile9608;
import com.cloudmusictool.app.profile.Profile9613;
import com.cloudmusictool.app.profile.Profile9615;
import com.cloudmusictool.app.profile.Profile9620;
import com.cloudmusictool.app.profile.TargetProfile;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link TargetProfile#findByLogicalName} 的纯 JVM 单测。
 *
 * hook 文件按 9.6.08 的旧混淆方法名请求方法（如 LoadingAdManager#E）；
 * Profile9613/Profile9620 的覆盖里 methodName 已是新名（如 H），logicalName 保留旧逻辑名。
 * 这里断言旧名 -> 新名的映射在 9.6.13 与 9.6.20 生效、9.6.08 不受影响。
 */
public class ProfileMethodMappingTest {

    private static final String LOADING_AD_MANAGER =
            "com.netease.cloudmusic.module.ad.LoadingAdManager";
    private static final String PLAYER_AD_DELEGATE =
            "com.netease.cloudmusic.module.ad.playerjar.PlayerAdJarDelegateImpl";
    private static final String PODCAST_VIDEO_AD =
            "com.netease.cloudmusic.module.ad.podcast.PodcastVideoAdHelperImpl";
    private static final String SETTING_ACTIVITY =
            "com.netease.cloudmusic.music.biz.setting.activity.SettingActivity";
    private static final String BASE_ARK_V3 =
            "com.netease.cloudmusic.discovery.view.newframework.BaseArkV3Fragment";
    private static final String LYRIC_VIDEO_TIPS =
            "com.netease.cloudmusic.fragment.LyricVideoTipsDialog";
    private static final String COMMENT_ADAPTER =
            "com.netease.cloudmusic.adapter.CommentAdapter";
    private static final String MINE_PROXY =
            "com.netease.cloudmusic.fragment.MyMusicProxyFragment";

    private static MountSpec mapped(TargetProfile profile, String className, String requested) {
        MountSpec spec = profile.findByLogicalName(className, requested);
        assertNotNull("no spec for " + className + "#" + requested, spec);
        assertNotNull("mapped spec has null methodName for "
                + className + "#" + requested, spec.methodName);
        return spec;
    }

    @Test
    public void profile9613_mapsRenamedLoadingAdManagerMethods() {
        // 9.6.13: 判定 E(AdInfo)Z -> H；热启动 G(Activity)V -> J；数据源 o(Z)ArrayList -> p。
        assertEquals("H", mapped(Profile9613.PROFILE, LOADING_AD_MANAGER, "E").methodName);
        assertEquals("J", mapped(Profile9613.PROFILE, LOADING_AD_MANAGER, "G").methodName);
        assertEquals("p", mapped(Profile9613.PROFILE, LOADING_AD_MANAGER, "o").methodName);
    }

    @Test
    public void profile9613_mapsOtherRenamedMethods() {
        // 播放页视频广告 renderVideo: k -> j。
        assertEquals("j", mapped(Profile9613.PROFILE, PLAYER_AD_DELEGATE, "k").methodName);
        // 播客视频广告 render: z -> B。
        assertEquals("B", mapped(Profile9613.PROFILE, PODCAST_VIDEO_AD, "z").methodName);
        // 歌词视频提示弹窗: oc -> kc。
        assertEquals("kc", mapped(Profile9613.PROFILE, LYRIC_VIDEO_TIPS, "oc").methodName);
        // 底栏开关: T8/U8/V8 -> P8/O8/Q8。
        assertEquals("P8", mapped(Profile9613.PROFILE, SETTING_ACTIVITY, "T8").methodName);
        assertEquals("O8", mapped(Profile9613.PROFILE, SETTING_ACTIVITY, "U8").methodName);
        assertEquals("Q8", mapped(Profile9613.PROFILE, SETTING_ACTIVITY, "V8").methodName);
        // 顶部频道: Tc -> Pc（写 o 字段）、Lc -> Hc（写 q 字段）、hc -> dc。
        assertEquals("Pc", mapped(Profile9613.PROFILE, BASE_ARK_V3, "Tc").methodName);
        assertEquals("Hc", mapped(Profile9613.PROFILE, BASE_ARK_V3, "Lc").methodName);
        assertEquals("dc", mapped(Profile9613.PROFILE, BASE_ARK_V3, "hc").methodName);
    }

    @Test
    public void profile9613_mapsViaRenamedClassCandidates() {
        // dr.h -> iq.h（类改名、方法 T0 -> O0）：hook 拿到的是 DexKit/profile 解析出的 iq.h。
        assertEquals("O0", mapped(Profile9613.PROFILE, "iq.h", "T0").methodName);
        // lv0.j -> mt0.j（类改名、方法 e -> c）。
        assertEquals("c", mapped(Profile9613.PROFILE, "mt0.j", "e").methodName);
        // q60.w0 -> l40.x0（类改名、方法 g 不变）：命中 spec 且 methodName 相同。
        assertEquals("g", mapped(Profile9613.PROFILE, "l40.x0", "g").methodName);
    }

    @Test
    public void findMethodSpec_resolvesRenamedRequestNames() {
        // HookKit.isCriticalMount 用 hook 请求的旧名查询；9.6.13 覆盖里 methodName
        // 已改名但 logicalName 保留旧名，必须回退到 logicalName 才能拿到 critical。
        MountSpec e = Profile9613.PROFILE.findMethodSpec(LOADING_AD_MANAGER, "E");
        assertNotNull("旧名 E 未映射到 9.6.13 的 H，critical 失败会被误判为非 critical", e);
        assertEquals("H", e.methodName);
        assertTrue(e.critical);
        MountSpec data = Profile9613.PROFILE.findMethodSpec(LOADING_AD_MANAGER, "o");
        assertNotNull(data);
        assertEquals("p", data.methodName);
        assertTrue(data.critical);
        MountSpec k = Profile9613.PROFILE.findMethodSpec(PLAYER_AD_DELEGATE, "k");
        assertNotNull(k);
        assertEquals("j", k.methodName);
        assertTrue(k.critical);
    }

    @Test
    public void findMethodSpec_sameBehaviourFor9615And9608() {
        // 9.6.15 复用 9.6.13 挂载表，必须得到同样的 critical 判定。
        MountSpec e = Profile9615.PROFILE.findMethodSpec(LOADING_AD_MANAGER, "E");
        assertNotNull(e);
        assertEquals("H", e.methodName);
        assertTrue(e.critical);
        // 9.6.08 没有改名覆盖，精确名直接命中。
        MountSpec legacy = Profile9608.PROFILE.findMethodSpec(LOADING_AD_MANAGER, "E");
        assertNotNull(legacy);
        assertEquals("E", legacy.methodName);
        assertTrue(legacy.critical);
    }

    @Test
    public void profile9620_mapsRenamedMethods() {
        // AD-07: y1(CommentListEntry) -> w1；AD-08: q(Ad) -> p。
        assertEquals("w1", mapped(Profile9620.PROFILE, COMMENT_ADAPTER, "y1").methodName);
        assertEquals("p", mapped(Profile9620.PROFILE, PLAYER_AD_DELEGATE, "q").methodName);
        // AD-09: 9.6.13 的 B 在 9.6.20 回退为 z。
        assertEquals("z", mapped(Profile9620.PROFILE, PODCAST_VIDEO_AD, "z").methodName);
        // UI-04: hc -> cc、Tc -> Oc、Lc -> Gc。
        assertEquals("cc", mapped(Profile9620.PROFILE, BASE_ARK_V3, "hc").methodName);
        assertEquals("Oc", mapped(Profile9620.PROFILE, BASE_ARK_V3, "Tc").methodName);
        assertEquals("Gc", mapped(Profile9620.PROFILE, BASE_ARK_V3, "Lc").methodName);
        // UI-14: oc -> jc；EN-01: kc -> jc。
        assertEquals("jc", mapped(Profile9620.PROFILE, LYRIC_VIDEO_TIPS, "oc").methodName);
        assertEquals("jc", mapped(Profile9620.PROFILE, MINE_PROXY, "kc").methodName);
        // 9.6.13 的覆盖继续生效（E -> H）。
        assertEquals("H", mapped(Profile9620.PROFILE, LOADING_AD_MANAGER, "E").methodName);
    }

    @Test
    public void profile9620_mapsViaRenamedClassCandidates() {
        assertEquals("q", mapped(Profile9620.PROFILE, "lu0.e", "q").methodName);
        assertEquals("O0", mapped(Profile9620.PROFILE, "kq.h", "T0").methodName);
        assertEquals("h", mapped(Profile9620.PROFILE, "wf0.h", "h").methodName);
        assertEquals("c", mapped(Profile9620.PROFILE, "uf0.c", "c").methodName);
        assertEquals("y", mapped(Profile9620.PROFILE, "wf0.o$a", "y").methodName);
        assertEquals("checkAndShowYouthDialog",
                mapped(Profile9620.PROFILE, "on2.c", "checkAndShowYouthDialog").methodName);
        assertEquals("m0",
                mapped(Profile9620.PROFILE,
                        "com.netease.cloudmusic.utils.vn", "m0").methodName);
        assertEquals("L",
                mapped(Profile9620.PROFILE,
                        "com.netease.cloudmusic.utils.vn", "L").methodName);
        assertEquals("d",
                mapped(Profile9620.PROFILE,
                        "com.netease.cloudmusic.utils.t2", "d").methodName);
        assertEquals("v",
                mapped(Profile9620.PROFILE,
                        "com.netease.cloudmusic.abtest2.b", "x").methodName);
        assertEquals("m",
                mapped(Profile9620.PROFILE,
                        "com.netease.cloudmusic.oaid.MusicOaidUploadManager", "o").methodName);
        assertEquals("B", mapped(Profile9620.PROFILE, "yb2.e$a", "B").methodName);
        assertEquals("g", mapped(Profile9620.PROFILE, "yb2.f", "g").methodName);
        assertEquals("s", mapped(Profile9620.PROFILE, "yb2.f", "s").methodName);
        assertEquals("s",
                mapped(Profile9620.PROFILE,
                        "com.netease.cloudmusic.utils.eh", "s").methodName);
        assertEquals("a", mapped(Profile9620.PROFILE, "zr0.d", "a").methodName);
        assertEquals("d", mapped(Profile9620.PROFILE, "zr0.d", "d").methodName);
        assertEquals("j", mapped(Profile9620.PROFILE, "bs0.r", "j").methodName);
        assertEquals("c", mapped(Profile9620.PROFILE, "rt0.j", "e").methodName);
    }

    @Test
    public void profile9620_dropsRemovedLogMonitorMethods() {
        assertNull("9.6.20 的 d 已合并进 c",
                Profile9620.PROFILE.findByLogicalName(
                        "com.netease.cloudmusic.statistic.monitor.LogMonitorConfigManager", "d"));
        assertNull(Profile9620.PROFILE.findByLogicalName(
                "com.netease.cloudmusic.statistic.monitor.LogMonitorConfigManager", "e"));
    }

    @Test
    public void findMethodSpec_unknownReturnsNull() {
        assertNull(Profile9613.PROFILE.findMethodSpec(LOADING_AD_MANAGER, "NoSuchMethod"));
        assertNull(Profile9613.PROFILE.findMethodSpec("com.example.Missing", "E"));
        assertNull(Profile9613.PROFILE.findMethodSpec(null, "E"));
        assertNull(Profile9613.PROFILE.findMethodSpec(LOADING_AD_MANAGER, null));
    }

    @Test
    public void profile9608_keepsOriginalNames() {
        assertEquals("E", mapped(Profile9608.PROFILE, LOADING_AD_MANAGER, "E").methodName);
        assertEquals("G", mapped(Profile9608.PROFILE, LOADING_AD_MANAGER, "G").methodName);
        assertEquals("o", mapped(Profile9608.PROFILE, LOADING_AD_MANAGER, "o").methodName);
        assertEquals("k", mapped(Profile9608.PROFILE, PLAYER_AD_DELEGATE, "k").methodName);
        assertEquals("z", mapped(Profile9608.PROFILE, PODCAST_VIDEO_AD, "z").methodName);
        assertEquals("oc", mapped(Profile9608.PROFILE, LYRIC_VIDEO_TIPS, "oc").methodName);
        assertEquals("T8", mapped(Profile9608.PROFILE, SETTING_ACTIVITY, "T8").methodName);
        assertEquals("V8", mapped(Profile9608.PROFILE, SETTING_ACTIVITY, "V8").methodName);
        assertEquals("Tc", mapped(Profile9608.PROFILE, BASE_ARK_V3, "Tc").methodName);
    }

    @Test
    public void unknownRequestReturnsNull() {
        assertNull(Profile9613.PROFILE.findByLogicalName(LOADING_AD_MANAGER, "NoSuchMethod"));
        assertNull(Profile9613.PROFILE.findByLogicalName("com.example.Missing", "E"));
        assertNull(Profile9613.PROFILE.findByLogicalName(null, "E"));
        assertNull(Profile9613.PROFILE.findByLogicalName(LOADING_AD_MANAGER, null));
        assertNull(Profile9608.PROFILE.findByLogicalName(LOADING_AD_MANAGER, "NoSuchMethod"));
    }

    @Test
    public void mappedSpecsKeepCriticalFlags() {
        // 判定入口（E/H）与数据源（o/p）在两个版本里都是 critical。
        assertTrue(mapped(Profile9613.PROFILE, LOADING_AD_MANAGER, "E").critical);
        assertTrue(mapped(Profile9613.PROFILE, LOADING_AD_MANAGER, "o").critical);
        assertTrue(mapped(Profile9608.PROFILE, LOADING_AD_MANAGER, "E").critical);
        // 自动播放总开关 V8/Q8 critical。
        assertTrue(mapped(Profile9613.PROFILE, SETTING_ACTIVITY, "V8").critical);
    }
}
