package com.cloudmusictool.app;

import org.junit.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 功能编号、remote key 与默认值的契约测试。
 * 改编号或 key 必须同步本测试，否则单测失败——这是故意的。
 */
public class FeatureTest {

    private static final Pattern ID_PATTERN =
            Pattern.compile("^(AD|UI|PV|EN|EX)-\\d{2}$");

    @Test
    public void idsAreUniqueAndWellFormed() {
        Set<String> ids = new HashSet<>();
        for (Feature f : Feature.values()) {
            assertTrue("非法编号: " + f.id, ID_PATTERN.matcher(f.id).matches());
            assertTrue("编号重复: " + f.id, ids.add(f.id));
        }
    }

    @Test
    public void keysAreUnique() {
        Set<String> keys = new HashSet<>();
        for (Feature f : Feature.values()) {
            assertTrue("key 重复: " + f.key, keys.add(f.key));
        }
    }

    @Test
    public void remoteKeysStayUnchanged() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ad_01_splash", "AD-01");
        expected.put("ad_02_launch_dialog", "AD-02");
        expected.put("ad_08_report_jump", "AD-03");
        expected.put("ad_09_search_ad", "AD-04");
        expected.put("ad_10_ad_preload", "AD-05");
        expected.put("ad_04_feed", "AD-06");
        expected.put("ad_05_comment", "AD-07");
        expected.put("ad_06_player", "AD-08");
        expected.put("ad_07_audio_clip", "AD-09");

        expected.put("ad_03_update_dialog", "UI-01");
        expected.put("ui_05_startup_delay", "UI-02");
        expected.put("ui_01_home_blocks", "UI-03");
        expected.put("ui_02_top_channel", "UI-04");
        expected.put("ui_03_bottom_nav", "UI-05");
        expected.put("ui_04_drawer", "UI-06");
        expected.put("ui_06_hints", "UI-07");
        expected.put("ui_07_autoplay", "UI-08");
        expected.put("ui_09_pendant", "UI-09");
        expected.put("ui_10_player_hints", "UI-10");
        expected.put("ui_11_search_guide", "UI-11");
        expected.put("ui_12_podcast_guide", "UI-12");
        expected.put("ui_13_widget_push_guide", "UI-13");
        expected.put("ui_14_lyrics_hint", "UI-14");
        expected.put("ui_15_community_live", "UI-15");

        expected.put("pv_01_oaid", "PV-01");
        expected.put("pv_02_log_tasks", "PV-02");
        expected.put("pv_03_startup_blacklist", "PV-03");
        expected.put("pv_04_apm", "PV-04");
        expected.put("pv_05_stats_observe", "PV-05");
        expected.put("pv_06_clipboard", "PV-06");

        expected.put("ui_16_mine_background", "EN-01");
        expected.put("ui_08_dark_mode", "EX-01");
        expected.put("pv_07_startup_orchestration", "EX-02");

        assertEquals("功能总数应为 33", 33, Feature.values().length);
        assertEquals("remote key 数量应为 33", 33, expected.size());
        for (Feature f : Feature.values()) {
            assertEquals("key 与编号映射不一致: " + f.key,
                    expected.get(f.key), f.id);
        }
    }

    @Test
    public void defaultsFollowGroupPolicy() {
        for (Feature f : Feature.values()) {
            switch (f.group) {
                case AD:
                case UI:
                    assertEquals("AD/UI 非 blocked 项应默认开启: " + f.id,
                            !f.blocked(), f.defaultEnabled);
                    break;
                case PV:
                case EN:
                case EX:
                    assertFalse("PV/EN/EX 应默认关闭: " + f.id, f.defaultEnabled);
                    break;
                default:
                    throw new AssertionError("未知分组: " + f.group);
            }
        }
    }

    @Test
    public void communityLiveIsDefaultOn() {
        assertTrue("UI-15 按决策改为默认开启", Feature.COMMUNITY_LIVE.defaultEnabled);
    }

    @Test
    public void blockedFeaturesAreDefaultOff() {
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                assertFalse("blocked 必须默认关闭: " + f.id, f.defaultEnabled);
                assertNotNull("blocked 原因不能为空: " + f.id, f.blockedReason);
                assertFalse("blocked 原因不能为空: " + f.id, f.blockedReason.isEmpty());
            }
        }
        assertEquals("当前仅 UI-06 为 blocked", 1, blockedCount());
        assertEquals("UI-06", Feature.DRAWER.id);
        assertTrue(Feature.DRAWER.blocked());
    }

    @Test
    public void groupCountsAndOrderingAreStable() {
        Map<Feature.Group, Integer> counts = new EnumMap<>(Feature.Group.class);
        Map<Feature.Group, Integer> seen = new EnumMap<>(Feature.Group.class);
        for (Feature f : Feature.values()) {
            counts.put(f.group, counts.getOrDefault(f.group, 0) + 1);
            int index = seen.getOrDefault(f.group, 0) + 1;
            seen.put(f.group, index);
            assertEquals("同组编号必须连续: " + f.id,
                    f.group.name() + "-" + String.format(Locale.ROOT, "%02d", index), f.id);
        }
        assertEquals(Integer.valueOf(9), counts.get(Feature.Group.AD));
        assertEquals(Integer.valueOf(15), counts.get(Feature.Group.UI));
        assertEquals(Integer.valueOf(6), counts.get(Feature.Group.PV));
        assertEquals(Integer.valueOf(1), counts.get(Feature.Group.EN));
        assertEquals(Integer.valueOf(2), counts.get(Feature.Group.EX));
    }

    @Test
    public void everyFeatureHasTextResources() {
        for (Feature f : Feature.values()) {
            assertTrue("缺少标题资源: " + f.id, f.titleRes != 0);
            assertTrue("缺少摘要资源: " + f.id, f.summaryRes != 0);
        }
    }

    private int blockedCount() {
        int count = 0;
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                count++;
            }
        }
        return count;
    }
}
