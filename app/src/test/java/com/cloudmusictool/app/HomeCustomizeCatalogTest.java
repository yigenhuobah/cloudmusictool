package com.cloudmusictool.app;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
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
 * app 模块源文件，因此数组顺序调整必须同步修改两处。
 */
public class HomeCustomizeCatalogTest {

    @Test
    public void blockTitlesMatchKeysByLengthAndOrder() throws Exception {
        String[] titles = blockTitles();
        String[] keys = HomeCustomizeFragment.BLOCK_KEYS;
        assertEquals("home_block_titles 与 BLOCK_KEYS 长度不一致", keys.length, titles.length);
        for (int i = 0; i < keys.length; i++) {
            assertFalse("BLOCK_KEYS[" + i + "] 为空", keys[i] == null || keys[i].isEmpty());
            assertFalse("home_block_titles[" + i + "] 为空", titles[i].isEmpty());
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

    private static String[] blockTitles() throws Exception {
        Document document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder().parse(locateStringsXml());
        NodeList arrays = document.getElementsByTagName("string-array");
        for (int i = 0; i < arrays.getLength(); i++) {
            Element array = (Element) arrays.item(i);
            if (!"home_block_titles".equals(array.getAttribute("name"))) {
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
        fail("strings.xml 未找到 home_block_titles");
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
