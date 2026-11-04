package com.cloudmusictool.app.anchor;

import com.cloudmusictool.app.Feature;
import com.cloudmusictool.app.dexkit.DexKitAnchors;
import com.cloudmusictool.app.profile.MountSpec;
import com.cloudmusictool.app.profile.TargetProfile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 锚点契约（纯 JVM）：anchors.json、生成的 AnchorKeys/DexKitAnchors 与运行时约束一致。
 *
 * <p>anchors.json 与 TB/QB/QM 使用同一套 schema（schemaVersion 1）：
 * 顶层 {@code anchors/classes/profiles}，锚点条目不重复 {@code key}（map key 即锚点键）。
 * 本类覆盖：
 * 1) schema/顶层元数据、顶层键集合与版本清单；
 * 2) 每个锚点条目的字段、note、feature、critical、signature（s ↔ params）、
 *    key ↔ feature/logicalName（版本变体 key 只允许 {@code mountId@versionName}）；
 * 3) profiles 的 key 都存在、base 链完整、每个锚点至少被一个 profile 引用；
 * 4) AnchorKeys 常量 ↔ anchors 双向一致，常量名符合生成规则；
 * 5) DexKitAnchors 常量 ↔ 锚点内联 dexkit 数组双向一致；
 * 6) 派生 Profile 覆盖全部 Feature（UI-06 blocked 除外）且每功能至少一个 critical；
 * 7) README 的「适配版本」与 anchors.json 一致（README 不改，由测试派生校验）。
 *
 * <p>运行时 Profile* 与派生表的逐项等价见 ProfileAnchorEquivalenceTest。
 */
public class AnchorContractTest {

    /** ABTest 不是 Feature 开关，但同样有版本化坐标（ProfileAdPv.ABTEST_FEATURE）。 */
    private static final String ABTEST_FEATURE = "ABTEST";

    private static final String[][] EXPECTED_VERSIONS = {
            {"9.6.08", "9006008", "142"},
            {"9.6.13", "9006013", "145"},
            {"9.6.15", "9006015", "145"},
    };

    /** 统一 schema 的顶层键（顺序不参与断言）。 */
    private static final Set<String> TOP_LEVEL_KEYS = new HashSet<>(Arrays.asList(
            "schemaVersion", "generatedBy", "app", "appVersion", "versionCode",
            "anchors", "classes", "profiles"));

    /** 锚点条目允许出现的字段；出现其它字段说明 schema 又分叉了。 */
    private static final Set<String> ANCHOR_FIELDS = new HashSet<>(Arrays.asList(
            "c", "candidates", "m", "s", "feature", "critical", "note",
            "logicalName", "params", "dexkit"));

    // ---------------------------------------------------------------- helpers

    private static JSONObject rawAnchors() throws Exception {
        InputStream stream = AnchorContractTest.class.getClassLoader()
                .getResourceAsStream(AnchorTable.RESOURCE);
        assertNotNull("anchors.json 必须在测试 classpath 上", stream);
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                text.append(buffer, 0, read);
            }
        }
        return new JSONObject(text.toString());
    }

    private static AnchorTable table() {
        AnchorTable table = AnchorTable.load(AnchorContractTest.class.getClassLoader());
        assertNotNull("anchors.json 必须能被 AnchorTable 解析", table);
        return table;
    }

    private static JSONArray profiles(JSONObject root) {
        JSONArray profiles = root.optJSONArray("profiles");
        assertNotNull("profiles 段必须是数组", profiles);
        return profiles;
    }

    private static JSONObject anchorsSection(JSONObject root) {
        JSONObject anchors = root.optJSONObject("anchors");
        assertNotNull("anchors 段必须是对象", anchors);
        return anchors;
    }

    private static Set<String> knownFeatures() {
        Set<String> out = new HashSet<>();
        for (Feature feature : Feature.values()) {
            out.add(feature.id);
        }
        out.add(ABTEST_FEATURE);
        return out;
    }

    private static Set<String> keys(JSONObject object) {
        Set<String> out = new HashSet<>();
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            out.add(iterator.next());
        }
        return out;
    }

    private static String constantName(String key) {
        return key.replaceAll("[^0-9A-Za-z]+", "_").toUpperCase();
    }

    private static Map<String, String> anchorKeys() throws Exception {
        Map<String, String> out = new HashMap<>();
        for (Field field : AnchorKeys.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers)) {
                continue;
            }
            assertTrue("AnchorKeys." + field.getName() + " 必须是 public",
                    Modifier.isPublic(modifiers));
            assertTrue("AnchorKeys." + field.getName() + " 必须是 final",
                    Modifier.isFinal(modifiers));
            assertEquals("AnchorKeys." + field.getName() + " 必须是 String",
                    String.class, field.getType());
            field.setAccessible(true);
            out.put(field.getName(), (String) field.get(null));
        }
        return out;
    }

    private static Map<String, List<String>> dexKitAnchors() throws Exception {
        Map<String, List<String>> out = new HashMap<>();
        for (Field field : DexKitAnchors.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            assertEquals("DexKitAnchors." + field.getName() + " 必须是 String[]",
                    String[].class, field.getType());
            field.setAccessible(true);
            out.put(field.getName(), Arrays.asList((String[]) field.get(null)));
        }
        return out;
    }

    private static List<String> jsonStringList(JSONArray array) throws Exception {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            out.add(array.getString(i));
        }
        return out;
    }

    private static Set<String> declaredVersionNames(JSONObject root) throws Exception {
        Set<String> out = new HashSet<>();
        JSONArray profiles = profiles(root);
        for (int i = 0; i < profiles.length(); i++) {
            out.add(profiles.getJSONObject(i).getString("versionName"));
        }
        return out;
    }

    // ------------------------------------------------------------------ tests

    @Test
    public void schemaAndTopLevelMetadata() throws Exception {
        JSONObject root = rawAnchors();
        assertEquals("顶层键必须与统一 schema 一致",
                TOP_LEVEL_KEYS, keys(root));
        assertEquals("schemaVersion 必须为 1", 1, root.getInt("schemaVersion"));
        assertEquals("generatedBy 必须指向生成器",
                "tools/gen_anchors.py", root.getString("generatedBy"));
        assertEquals("app 必须是目标包名",
                "com.netease.cloudmusic", root.getString("app"));
        JSONObject classes = root.getJSONObject("classes");
        for (String key : keys(classes)) {
            assertFalse("classes." + key + " 不能为空", classes.getString(key).isEmpty());
        }
        JSONArray profiles = profiles(root);
        JSONObject latest = profiles.getJSONObject(profiles.length() - 1);
        assertEquals("appVersion 必须是最后一个 profile", latest.getString("versionName"),
                root.getString("appVersion"));
        assertEquals("versionCode 必须是最后一个 profile", latest.getLong("versionCode"),
                root.getLong("versionCode"));
    }

    @Test
    public void versionListIsCompleteAndOrdered() throws Exception {
        JSONArray profiles = profiles(rawAnchors());
        assertEquals(EXPECTED_VERSIONS.length, profiles.length());
        for (int i = 0; i < EXPECTED_VERSIONS.length; i++) {
            JSONObject profile = profiles.getJSONObject(i);
            assertEquals(EXPECTED_VERSIONS[i][0], profile.getString("versionName"));
            assertEquals(Long.parseLong(EXPECTED_VERSIONS[i][1]),
                    profile.getLong("versionCode"));
            if (i > 0) {
                assertEquals("base 必须指向上一个版本",
                        EXPECTED_VERSIONS[i - 1][0], profile.getString("base"));
            } else {
                assertFalse("首个版本不得有 base", profile.has("base"));
            }
            JSONArray mounts = profile.optJSONArray("mounts");
            assertNotNull(profile.getString("versionName") + " 缺少 mounts", mounts);
        }
    }

    @Test
    public void everyAnchorEntryIsWellFormed() throws Exception {
        JSONObject root = rawAnchors();
        JSONObject anchors = anchorsSection(root);
        Set<String> features = knownFeatures();
        Set<String> versions = declaredVersionNames(root);
        assertFalse("anchors 段不能为空", anchors.length() == 0);
        Iterator<String> keys = anchors.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject entry = anchors.getJSONObject(key);
            String context = "anchor " + key;

            assertTrue("字段集合超出统一 schema: " + context + " -> " + keys(entry),
                    ANCHOR_FIELDS.containsAll(keys(entry)));
            String note = entry.getString("note");
            assertFalse("note 不能为空: " + context, note.isEmpty());

            String feature = entry.getString("feature");
            assertTrue("未知 feature: " + context + " -> " + feature,
                    features.contains(feature));
            assertTrue("critical 必须是布尔值: " + context,
                    entry.get("critical") instanceof Boolean);

            String logicalName = entry.getString("logicalName");
            String mountId = feature + "/" + logicalName;
            boolean plain = key.equals(mountId);
            boolean variant = key.startsWith(mountId + AnchorTable.VARIANT_SEPARATOR);
            assertTrue("key 必须是 mountId 或 mountId@version: " + context,
                    plain || variant);
            if (variant) {
                String version = key.substring(mountId.length()
                        + AnchorTable.VARIANT_SEPARATOR.length());
                assertTrue("未知版本变体: " + context + " -> " + version,
                        versions.contains(version));
            }

            String primary = entry.getString("c");
            assertFalse("空类名: " + context, primary.isEmpty());
            if (entry.has("candidates")) {
                JSONArray candidates = entry.getJSONArray("candidates");
                assertTrue("candidates 至少两个: " + context, candidates.length() > 1);
                assertEquals("candidates[0] 必须等于 c: " + context,
                        primary, candidates.getString(0));
            }

            String method = entry.getString("m");
            assertFalse("空方法名: " + context, method.isEmpty());
            String signature = entry.getString("s");
            String[] params = AnchorTable.splitParamTypes(signature);
            assertFalse("s 返回类型为空: " + context,
                    AnchorTable.returnType(signature).isEmpty());
            if (entry.has("params")) {
                JSONArray declared = entry.getJSONArray("params");
                assertEquals("params 与 s 的参数段长度不一致: " + context,
                        params.length, declared.length());
                for (int k = 0; k < params.length; k++) {
                    assertEquals("params[" + k + "] 与 s 不一致: " + context,
                            params[k], declared.getString(k));
                }
            }

            if (entry.has("dexkit")) {
                JSONArray dexkit = entry.getJSONArray("dexkit");
                assertTrue("dexkit 不能为空: " + context, dexkit.length() > 0);
                for (int k = 0; k < dexkit.length(); k++) {
                    assertFalse("dexkit[" + k + "] 不能为空: " + context,
                            dexkit.getString(k).isEmpty());
                }
            }
        }
    }

    @Test
    public void profileMountsAreDeclaredAndUsed() throws Exception {
        JSONObject root = rawAnchors();
        JSONObject anchors = anchorsSection(root);
        Set<String> allKeys = keys(anchors);
        Set<String> used = new HashSet<>();
        JSONArray profiles = profiles(root);
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.getJSONObject(i);
            String version = profile.getString("versionName");
            JSONArray mounts = profile.getJSONArray("mounts");
            Set<String> inProfile = new HashSet<>();
            for (int j = 0; j < mounts.length(); j++) {
                String key = mounts.getString(j);
                assertFalse("profile " + version + " 重复引用 key: " + key,
                        !inProfile.add(key));
                assertTrue("profile " + version + " 引用了不存在的 key: " + key,
                        allKeys.contains(key));
                used.add(key);
            }
        }
        assertEquals("anchors 中存在未被任何 profile 引用的条目",
                allKeys, used);
    }

    @Test
    public void derivedProfilesCoverEveryFeature() {
        AnchorTable table = table();
        assertEquals(EXPECTED_VERSIONS.length, table.versions().size());
        for (int i = 0; i < EXPECTED_VERSIONS.length; i++) {
            AnchorTable.Version version = table.versions().get(i);
            TargetProfile profile = version.profile;
            assertEquals("挂载点数量漂移: " + version.versionName,
                    Integer.parseInt(EXPECTED_VERSIONS[i][2]), profile.mounts.size());

            Set<String> ids = new HashSet<>();
            for (MountSpec spec : profile.mounts) {
                assertTrue("重复 mount id: " + spec.id, ids.add(spec.id));
                assertNotNull("classCandidates 为空: " + spec.id, spec.classCandidates);
                assertTrue("classCandidates 为空: " + spec.id,
                        spec.classCandidates.length > 0);
                assertNotNull("methodName 为空: " + spec.id, spec.methodName);
                assertNotNull("returnType 为空: " + spec.id, spec.returnType);
                assertNotNull("paramTypes 为空: " + spec.id, spec.paramTypes);
            }
            for (Feature feature : Feature.values()) {                if (feature.blocked()) {
                    continue;
                }
                assertFalse("feature 没有挂载点: " + version.versionName + " " + feature.id,
                        profile.forFeature(feature.id).isEmpty());
                assertTrue("feature 没有 critical: " + version.versionName + " " + feature.id,
                        profile.criticalCount(feature.id) > 0);
            }
            // EN-01 的 critical 必须在真实挂载点，死坐标 RnMineFragment#onResume 禁止回归。
            boolean en01Critical = false;
            for (MountSpec spec : profile.forFeature("EN-01")) {
                assertFalse("dead mount registered: " + spec.id,
                        "EN-01/RnMineFragment#onResume".equals(spec.id));
                if (spec.critical
                        && "EN-01/MyMusicProfileHeaderBgViewHolderManager#e".equals(spec.id)) {
                    en01Critical = true;
                }
            }
            assertTrue("EN-01 critical 不在真实挂载点: " + version.versionName, en01Critical);
        }
    }

    @Test
    public void anchorKeysAndTableAreBidirectional() throws Exception {
        Set<String> keys = keys(anchorsSection(rawAnchors()));
        Map<String, String> constants = anchorKeys();
        Set<String> constantValues = new HashSet<>(constants.values());
        for (String key : keys) {
            assertTrue("anchors.json 的 key 缺少 AnchorKeys 常量：" + key,
                    constantValues.contains(key));
            assertTrue("常量名不符合生成规则: " + key + " -> " + constantName(key),
                    constants.containsKey(constantName(key)));
        }
        for (Map.Entry<String, String> entry : constants.entrySet()) {
            assertTrue("AnchorKeys." + entry.getKey() + " 指向表中不存在的 key："
                    + entry.getValue(), keys.contains(entry.getValue()));
        }
    }

    @Test
    public void constantNamesMatchGeneratedRule() throws Exception {
        for (Map.Entry<String, String> entry : anchorKeys().entrySet()) {
            assertEquals("常量名必须是 key 的大写下划线形式",
                    constantName(entry.getValue()), entry.getKey());
        }
    }

    @Test
    public void dexKitAnchorsMatchAnchorsJson() throws Exception {
        Map<String, List<String>> fromCode = dexKitAnchors();
        Set<List<String>> used = new HashSet<>();
        JSONObject anchors = anchorsSection(rawAnchors());
        Iterator<String> keys = anchors.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject entry = anchors.getJSONObject(key);
            if (!entry.has("dexkit")) {
                continue;
            }
            List<String> inline = jsonStringList(entry.getJSONArray("dexkit"));
            assertTrue("dexkit 数组不对应任何 DexKitAnchors 常量: " + key,
                    fromCode.containsValue(inline));
            used.add(inline);
        }
        assertEquals("DexKitAnchors 存在未被任何锚点引用的常量",
                new HashSet<>(fromCode.values()), used);
    }

    @Test
    public void readmeListsSupportedVersions() throws Exception {
        File readme = findRepoFile("README.md");
        String text = new String(Files.readAllBytes(readme.toPath()), StandardCharsets.UTF_8);
        assertTrue("README 缺少版本名清单: " + SupportedVersions.namesLabel(),
                text.contains(SupportedVersions.namesLabel()));
        assertTrue("README 缺少 versionCode 清单: " + SupportedVersions.codesLabel(),
                text.contains(SupportedVersions.codesLabel()));
        for (AnchorTable.Version version : SupportedVersions.all()) {
            assertTrue("README 缺少版本 code: " + version.versionCode,
                    text.contains(String.valueOf(version.versionCode)));
        }
    }

    @Test
    public void supportedVersionsComeFromAnchors() {
        assertEquals("9.6.08 / 9.6.13 / 9.6.15", SupportedVersions.namesLabel());
        assertEquals("9006008 / 9006013 / 9006015", SupportedVersions.codesLabel());
        assertEquals("9.6.15", SupportedVersions.table().appVersion());
        assertTrue(SupportedVersions.isSupported("9.6.15"));
        assertFalse(SupportedVersions.isSupported("9.6.09"));
        assertFalse(SupportedVersions.isSupported(null));
        assertNull(SupportedVersions.byCode(1L));
        assertNull(SupportedVersions.byName("1.0.0"));
        assertEquals(3, SupportedVersions.all().size());
    }

    /** 从测试工作目录向上找仓库文件（Gradle 单测 cwd 多为模块目录）。 */
    private static File findRepoFile(String name) {
        File dir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        while (dir != null) {
            File candidate = new File(dir, name);
            if (candidate.isFile()) {
                return candidate;
            }
            dir = dir.getParentFile();
        }
        fail("找不到 " + name + "（cwd=" + System.getProperty("user.dir") + "）");
        return null;
    }
}
