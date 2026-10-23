package com.cloudmusictool.app;

import com.cloudmusictool.app.profile.MountSpec;
import com.cloudmusictool.app.profile.Profile9608;
import com.cloudmusictool.app.profile.Profile9613;
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
 * Profile9613 的覆盖里 methodName 已是新名（如 H），logicalName 保留旧逻辑名。
 * 这里断言旧名 -> 新名的映射在 9.6.13 生效、9.6.08 不受影响。
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
