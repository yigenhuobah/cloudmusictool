package com.cloudmusictool.app;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 首页定制 BLOCK_KEYS 与 strings.xml home_block_titles 的顺序契约（纯 JVM）。
 *
 * MultiSelectPreference 的 entries / entryValues 按索引绑定；任一侧单独增删、
 * 换序都会让界面标题与隐藏 code 错位。本测试不依赖 Robolectric，直接读取
 * app 模块源文件，因此数组顺序调整必须同步修改 BLOCK_KEYS、strings.xml 与
 * 本测试的 BLOCK_CATALOG（逐项 key/标题对照表）。
 */
public class HomeCustomizeCatalogTest {

    /**
     * BLOCK_KEYS ↔ home_block_titles 的权威目录，索引即 MultiSelectPreference
     * 的 which。逐项断言 key 与标题，任两项互换（或标题与 code 错配）都会失败。
     */
    private static final String[][] BLOCK_CATALOG = {
            {"PAGE_RECOMMEND_PRIVATE_RCMD_SONG", "猜你喜欢"},
            {"PAGE_RECOMMEND_RANK", "排行榜"},
            {"PAGE_BLOCK_TOPLIST", "排行榜（备用 code）"},
            {"PAGE_RECOMMEND_SHORTCUT", "快捷入口（每日推荐/心动模式等）"},
            {"PAGE_RECOMMEND_RADAR", "雷达歌单"},
            {"PAGE_RECOMMEND_DAILY_RECOMMEND", "每日推荐"},
            {"PAGE_RECOMMEND_GREETING", "首页问候语"},
            {"PAGE_RECOMMEND_NOTE", "笔记卡片"},
            {"PAGE_RECOMMEND_NEW_SONG_AND_ALBUM", "新歌新碟"},
            {"PAGE_RECOMMEND_SPECIAL_CLOUD_VILLAGE_PLAYLIST", "云村精选歌单"},
            {"PAGE_RECOMMEND_RED_SIMILAR_SONG", "红心相似推荐"},
            {"PAGE_RECOMMEND_MIXED_ARTIST_PLAYLIST", "艺人/组合歌单"},
            {"PAGE_RECOMMEND_FEELING_PLAYLIST_LOCATION", "心动歌单"},
            {"PAGE_RECOMMEND_SCENE_PLAYLIST_LOCATION", "场景歌单"},
            {"PAGE_RECOMMEND_STYLE_PLAYLIST_1", "风格歌单"},
            {"PAGE_RECOMMEND_SURVEY", "调研问卷"},
            {"PAGE_RECOMMEND_VIP_MODULE", "VIP 专属模块"},
            {"PAGE_RECOMMEND_VIP_SMALL_CARD", "VIP 小卡片"},
            {"PAGE_RECOMMEND_BANNER_1", "运营 Banner 1"},
            {"PAGE_RECOMMEND_BANNER_6", "运营 Banner 6"},
            {"PAGE_RECOMMEND_BANNER_0", "运营 Banner 0"},
            {"PAGE_RECOMMEND_SEARCH_TOP_BANNER", "搜索顶部 Banner"},
            {"PAGE_RECOMMEND_SECONDARY_TAB", "首页二级 Tab"},
            {"PAGE_RECOMMEND_PODCAST_RADIO_PROGRAM", "播客电台"},
            {"PAGE_RECOMMEND_PODCAST_MUSIC_RADIO", "播客音乐电台"},
            {"PAGE_RECOMMEND_PODCAST_SUB_UPDATE", "播客订阅更新"},
            {"PAGE_RECOMMEND_PODCAST_NARROW_SCENE", "播客窄场景"},
            {"PAGE_RECOMMEND_PODCAST_RELATE_PROGRAM", "播客关联节目"},
            {"PAGE_RECOMMEND_ARTIST_TREND", "艺人动态"},
            {"PAGE_RECOMMEND_ARTIST_FM_LIST", "艺人 FM"},
            {"PAGE_RECOMMEND_MUSIC_FM_LIST", "音乐 FM"},
            {"PAGE_RECOMMEND_FANS_GROUP_LEADER", "粉丝团卡片"},
            {"PAGE_RECOMMEND_REAL_TIME_INTEREST_RCMD", "实时兴趣推荐"},
            {"PAGE_RECOMMEND_COMBINATION", "组合模块"},
            {"PAGE_RECOMMEND_FIRM_PLAYLIST", "品牌歌单"},
            {"PAGE_IMG_TEXT_MUSIC_PODCAST", "音乐播客图文"},
            {"PAGE_IMG_TEXT_VOICELIST_RCMD_HOT", "声音/视频歌单推荐"},
            {"PAGE_RECOMMEND_SEARCH_CHANNEL_ENTRY", "搜索频道入口"},
            {"PAGE_RECOMMEND_MORE_RCMD", "为你专属推荐（更多推荐）"},
    };

    private static final String[][] CHANNEL_CATALOG = {
            {"心动", "心动"},
            {"推荐", "推荐"},
            {"音乐", "音乐"},
            {"播客", "播客"},
            {"听书", "听书"},
            {"AI写歌", "AI 写歌"},
    };

    private static final String[][] BOTTOM_NAV_CATALOG = {
            {"首页", "首页"},
            {"搜索", "搜索"},
            {"关注", "关注"},
            {"我的", "我的"},
            {"social", "云村 (social)"},
            {"publish", "发布 (publish)"},
    };

    /** abtest_titles 与 AbTestPolicy.CURATED_KEYS 同序，索引即 AbTestDescriptions.indexOf 的结果。 */
    private static final String[] ABTEST_TITLES = {
            "Tab 合并",
            "首页头图",
            "评论点击",
            "回复置顶",
            "评论加载样式",
            "搜索 RN 框架",
            "最佳搜索",
            "热搜列表",
            "本地搜索排序",
            "封面交叉淡入",
            "播放器数据源",
            "播放环 UI",
            "音频焦点弹窗",
            "通知接收器异步注册",
            "仅前台通知",
            "消息中心通知",
            "存储策略调整",
            "磁盘扫描树",
            "扫描后清理",
            "本地文件状态修复",
            "每日推荐刷新",
            "歌单推荐",
    };

    @Test
    public void blockTitlesMatchKeysByLengthAndOrder() throws Exception {
        String[] titles = blockTitles();
        String[] keys = HomeCustomizeFragment.BLOCK_KEYS;
        assertEquals("BLOCK_KEYS 与目录表长度不一致", BLOCK_CATALOG.length, keys.length);
        assertEquals("home_block_titles 与 BLOCK_KEYS 长度不一致", keys.length, titles.length);
        for (int i = 0; i < keys.length; i++) {
            assertEquals("BLOCK_KEYS[" + i + "] 顺序漂移", BLOCK_CATALOG[i][0], keys[i]);
            assertEquals("home_block_titles[" + i + "] 与 " + keys[i] + " 错位",
                    BLOCK_CATALOG[i][1], titles[i]);
        }
    }

    /** 顶部频道/底部导航的标题数组同样按 which 与 key 逐项绑定，不能只靠人工对齐。 */
    @Test
    public void channelAndBottomNavTitlesMatchKeysByIndex() throws Exception {
        assertPrivateCatalog("CHANNEL_KEYS", "home_channel_titles", CHANNEL_CATALOG);
        assertPrivateCatalog("BOTTOM_NAV_KEYS", "home_bottom_nav_titles", BOTTOM_NAV_CATALOG);
    }

    /** abtest_titles 的每一项都由 AbTestDescriptions.indexOf 按 CURATED_KEYS 索引取用。 */
    @Test
    public void abtestTitlesAlignWithCuratedKeys() throws Exception {
        String[] keys = AbTestPolicy.CURATED_KEYS;
        String[] titles = array("abtest_titles");
        assertEquals("abtest_titles 与 CURATED_KEYS 长度不一致", keys.length, titles.length);
        assertEquals("ABTEST_TITLES 目录长度不一致", keys.length, ABTEST_TITLES.length);
        for (int i = 0; i < keys.length; i++) {
            assertEquals("abtest_titles[" + i + "] 与 " + keys[i] + " 错位",
                    ABTEST_TITLES[i], titles[i]);
        }

        String[] descriptions = array("abtest_descriptions");
        assertEquals("abtest_descriptions 与 CURATED_KEYS 长度不一致",
                keys.length, descriptions.length);
        for (int i = 0; i < descriptions.length; i++) {
            assertFalse("abtest_descriptions[" + i + "] 为空", descriptions[i].isEmpty());
        }
    }

    @Test
    public void new9613CodesAreAppendedWithoutShiftingOldOnes() throws Exception {
        String[] keys = HomeCustomizeFragment.BLOCK_KEYS;
        String[] titles = blockTitles();
        int n = keys.length;
        assertEquals("PAGE_RECOMMEND_PRIVATE_RCMD_SONG", keys[0]);
        assertEquals("猜你喜欢", titles[0]);
        assertEquals("PAGE_RECOMMEND_SEARCH_CHANNEL_ENTRY", keys[n - 2]);
        assertEquals("搜索频道入口", titles[n - 2]);
        assertEquals("PAGE_RECOMMEND_MORE_RCMD", keys[n - 1]);
        assertEquals("为你专属推荐（更多推荐）", titles[n - 1]);
    }

    @Test
    public void blockKeysAreUnique() {
        Set<String> seen = new LinkedHashSet<>();
        for (String key : HomeCustomizeFragment.BLOCK_KEYS) {
            assertTrue("BLOCK_KEYS 重复: " + key, seen.add(key));
        }
    }

    /** CHANNEL_KEYS/BOTTOM_NAV_KEYS 是 private：按仓库既有做法用反射读取，不放宽可见性。 */
    private static void assertPrivateCatalog(String fieldName, String arrayName,
                                             String[][] expected) throws Exception {
        Field field = HomeCustomizeFragment.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        String[] keys = (String[]) field.get(null);
        String[] titles = array(arrayName);
        assertEquals(arrayName + " 与 " + fieldName + " 长度不一致",
                keys.length, titles.length);
        assertEquals(fieldName + " 与目录表长度不一致", expected.length, keys.length);
        for (int i = 0; i < keys.length; i++) {
            assertEquals(fieldName + "[" + i + "] 顺序漂移", expected[i][0], keys[i]);
            assertEquals(arrayName + "[" + i + "] 与 " + keys[i] + " 错位",
                    expected[i][1], titles[i]);
        }
    }

    private static String[] blockTitles() throws Exception {
        return array("home_block_titles");
    }

    private static String[] array(String name) throws Exception {
        Document document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(locateStringsXml());
        NodeList arrays = document.getElementsByTagName("string-array");
        for (int i = 0; i < arrays.getLength(); i++) {
            Element array = (Element) arrays.item(i);
            if (!name.equals(array.getAttribute("name"))) {
                continue;
            }
            List<String> items = new ArrayList<>();
            NodeList children = array.getChildNodes();
            for (int j = 0; j < children.getLength(); j++) {
                Node child = children.item(j);
                if (child.getNodeType() == Node.ELEMENT_NODE
                        && "item".equals(child.getNodeName())) {
                    items.add(child.getTextContent().trim());
                }
            }
            return items.toArray(new String[0]);
        }
        fail("strings.xml 未找到 " + name);
        return new String[0]; // unreachable
    }

    /**
     * Gradle 单测的工作目录通常是 app/；从仓库根目录运行时回退到 app/src/...。
     */
    private static File locateStringsXml() {
        File cwd = new File(System.getProperty("user.dir", "."));
        File[] candidates = {
                new File(cwd, "src/main/res/values/strings.xml"),
                new File(cwd, "../app/src/main/res/values/strings.xml"),
                new File(cwd, "app/src/main/res/values/strings.xml"),
        };
        for (File candidate : candidates) {
            if (candidate.isFile()) {
                return candidate;
            }
        }
        fail("strings.xml not found; cwd=" + cwd.getAbsolutePath()
                + ", tried: " + Arrays.toString(candidates));
        return null; // unreachable
    }
}
