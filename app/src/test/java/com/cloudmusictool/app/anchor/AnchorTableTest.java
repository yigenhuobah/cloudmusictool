package com.cloudmusictool.app.anchor;

import com.cloudmusictool.app.profile.MountSpec;
import com.cloudmusictool.app.profile.TargetProfile;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link AnchorTable} 的纯 JVM 单测：用合成 JSON 覆盖统一 schema 解析、
 * mount id 维度的 base 链合并、版本变体 key（{@code mountId@version}）、
 * candidates/signature/params/dexkit/classes 与各类非法输入。
 *
 * <p>真实 anchors.json 的契约由 {@link AnchorContractTest} 覆盖。
 */
public class AnchorTableTest {

    private static final String SYNTHETIC = "{\n"
            + "  \"schemaVersion\": 1,\n"
            + "  \"generatedBy\": \"test\",\n"
            + "  \"app\": \"com.example.host\",\n"
            + "  \"appVersion\": \"2.0\",\n"
            + "  \"versionCode\": 2,\n"
            + "  \"anchors\": {\n"
            + "    \"F/Base#a\": {\"c\": \"com.example.A\", \"m\": \"a\", \"s\": \"(I)V\",\n"
            + "      \"feature\": \"F\", \"critical\": true, \"note\": \"base a\",\n"
            + "      \"logicalName\": \"Base#a\", \"params\": [\"I\"]},\n"
            + "    \"F/Base#a@2.0\": {\"c\": \"com.example.A2\",\n"
            + "      \"candidates\": [\"com.example.A2\", \"com.example.A\"],\n"
            + "      \"m\": \"a2\", \"s\": \"()V\", \"feature\": \"F\", \"critical\": false,\n"
            + "      \"note\": \"2.0 override\", \"logicalName\": \"Base#a\",\n"
            + "      \"dexkit\": [\"inline\"]},\n"
            + "    \"F/Base#b\": {\"c\": \"com.example.B\",\n"
            + "      \"candidates\": [\"com.example.B\", \"com.example.B2\"],\n"
            + "      \"m\": \"b\", \"s\": \"(Ljava/lang/String;)Z\", \"feature\": \"F\",\n"
            + "      \"critical\": false, \"note\": \"base b\", \"logicalName\": \"Base#b\"},\n"
            + "    \"F/New#c\": {\"c\": \"com.example.D\", \"m\": \"c\", \"s\": \"()V\",\n"
            + "      \"feature\": \"F\", \"critical\": true, \"note\": \"new in 2.0\",\n"
            + "      \"logicalName\": \"New#c\"}\n"
            + "  },\n"
            + "  \"classes\": {\"Cls\": \"com.example.C\"},\n"
            + "  \"profiles\": [\n"
            + "    {\"versionName\": \"1.0\", \"versionCode\": 1,\n"
            + "     \"mounts\": [\"F/Base#a\", \"F/Base#b\"]},\n"
            + "    {\"versionName\": \"2.0\", \"versionCode\": 2, \"base\": \"1.0\",\n"
            + "     \"mounts\": [\"F/Base#a@2.0\", \"F/New#c\"]}\n"
            + "  ]\n"
            + "}";

    private static MountSpec find(TargetProfile profile, String id) {
        for (MountSpec spec : profile.mounts) {
            if (id.equals(spec.id)) {
                return spec;
            }
        }
        return null;
    }

    @Test
    public void parsesVersionsAndMetadata() {
        AnchorTable table = AnchorTable.parse(SYNTHETIC);
        assertEquals(1, table.schemaVersion());
        assertEquals("test", table.generatedBy());
        assertEquals("com.example.host", table.app());
        assertEquals("2.0", table.appVersion());
        assertEquals(2L, table.versionCode());
        assertEquals(Arrays.asList("1.0", "2.0"), names(table));
        assertNotNull(table.versionByCode(1L));
        assertNotNull(table.versionByName("2.0"));
        assertNull(table.versionByCode(9L));
        assertNull(table.versionByName("9.9"));
        assertEquals(Arrays.asList("Cls"), Arrays.asList(
                table.classes().keySet().toArray(new String[0])));
        assertEquals("com.example.C", table.classes().get("Cls"));
    }

    @Test
    public void anchorEntryExposesNoteDexkitAndMountId() {
        AnchorTable table = AnchorTable.parse(SYNTHETIC);
        AnchorTable.Anchor anchor = table.anchor("F/Base#a@2.0");
        assertNotNull(anchor);
        assertEquals("F/Base#a", anchor.mountId());
        assertEquals("a2", anchor.methodName);
        assertArrayEquals(new String[]{"inline"}, anchor.dexkitAnchors);
        assertEquals("2.0 override", anchor.note);
        assertFalse(anchor.critical);
        assertEquals(4, table.anchorKeys().size());
        assertNull(table.anchor("F/Missing#x"));
    }

    @Test
    public void baseProfileParsesCandidatesSignatureAndDexkit() {
        TargetProfile profile = AnchorTable.parse(SYNTHETIC).versionByName("1.0").profile;
        assertEquals(2, profile.mounts.size());

        MountSpec a = find(profile, "F/Base#a");
        assertNotNull(a);
        assertArrayEquals(new String[]{"com.example.A"}, a.classCandidates);
        assertEquals("a", a.methodName);
        assertEquals("V", a.returnType);
        assertArrayEquals(new String[]{"I"}, a.paramTypes);
        assertTrue(a.critical);
        assertEquals(0, a.anchors.length);

        MountSpec b = find(profile, "F/Base#b");
        assertNotNull(b);
        assertArrayEquals(new String[]{"com.example.B", "com.example.B2"}, b.classCandidates);
        assertArrayEquals(new String[0], b.anchors);
        assertFalse(b.critical);
    }

    @Test
    public void variantKeyReplacesSameMountIdInPlaceAndAdditionsAppend() {
        TargetProfile profile = AnchorTable.parse(SYNTHETIC).versionByName("2.0").profile;
        assertEquals(3, profile.mounts.size());
        assertEquals(Arrays.asList("F/Base#a", "F/Base#b", "F/New#c"), ids(profile));

        MountSpec replaced = find(profile, "F/Base#a");
        assertNotNull(replaced);
        assertArrayEquals(new String[]{"com.example.A2", "com.example.A"},
                replaced.classCandidates);
        assertEquals("a2", replaced.methodName);
        assertEquals("V", replaced.returnType);
        assertArrayEquals(new String[0], replaced.paramTypes);
        assertArrayEquals(new String[]{"inline"}, replaced.anchors);
        assertFalse(replaced.critical);

        // 未被覆盖的条目沿用 base。
        assertArrayEquals(new String[]{"com.example.B", "com.example.B2"},
                find(profile, "F/Base#b").classCandidates);
    }

    @Test
    public void splitParamTypesHandlesPrimitivesObjectsAndArrays() {
        assertArrayEquals(new String[0], AnchorTable.splitParamTypes("()V"));
        assertArrayEquals(new String[]{"I", "J", "Z"},
                AnchorTable.splitParamTypes("(IJZ)V"));
        assertArrayEquals(new String[]{"Ljava/lang/String;", "[I", "[[Ljava/lang/Object;"},
                AnchorTable.splitParamTypes(
                        "(Ljava/lang/String;[I[[Ljava/lang/Object;)V"));
        assertEquals("V", AnchorTable.returnType("(I)V"));
        assertEquals("Ljava/util/List;", AnchorTable.returnType("()Ljava/util/List;"));
        assertEquals("[I", AnchorTable.returnType("()[I"));
    }

    @Test
    public void parseRejectsBadSchemaVersion() {
        String bad = SYNTHETIC.replace("\"schemaVersion\": 1", "\"schemaVersion\": 2");
        assertThrows(IllegalArgumentException.class, () -> AnchorTable.parse(bad));
    }

    @Test
    public void parseRejectsBaseCycle() {
        String bad = SYNTHETIC
                .replace("\"versionName\": \"2.0\", \"versionCode\": 2, \"base\": \"1.0\"",
                        "\"versionName\": \"2.0\", \"versionCode\": 2, \"base\": \"2.0\"");
        assertThrows(IllegalArgumentException.class, () -> AnchorTable.parse(bad));
    }

    @Test
    public void parseRejectsMissingBase() {
        String bad = SYNTHETIC.replace("\"base\": \"1.0\"", "\"base\": \"0.9\"");
        assertThrows(IllegalArgumentException.class, () -> AnchorTable.parse(bad));
    }

    @Test
    public void parseRejectsUnknownProfileAnchorKey() {
        String bad = SYNTHETIC.replace("\"F/New#c\"]", "\"F/Missing#c\"]");
        assertThrows(IllegalArgumentException.class, () -> AnchorTable.parse(bad));
    }

    @Test
    public void parseRejectsDexkitGroupName() {
        String bad = SYNTHETIC.replace("\"dexkit\": [\"inline\"]",
                "\"dexkit\": \"GROUP\"");
        assertThrows(IllegalArgumentException.class, () -> AnchorTable.parse(bad));
    }

    @Test
    public void parseRejectsSignatureParamsMismatch() {
        String bad = SYNTHETIC.replace("\"params\": [\"I\"]", "\"params\": [\"J\"]");
        assertThrows(IllegalArgumentException.class, () -> AnchorTable.parse(bad));
    }

    @Test
    public void parseRejectsPrimaryClassNotFirstCandidate() {
        String bad = SYNTHETIC.replace("\"c\": \"com.example.A2\"",
                "\"c\": \"com.example.A\"");
        assertThrows(IllegalArgumentException.class, () -> AnchorTable.parse(bad));
    }

    @Test
    public void parseRejectsMissingNote() {
        String bad = SYNTHETIC.replace("\"note\": \"base b\",", "");
        assertThrows(IllegalArgumentException.class, () -> AnchorTable.parse(bad));
    }

    @Test
    public void parseRejectsMissingCritical() {
        String bad = SYNTHETIC.replace(",\n      \"critical\": false, \"note\": \"base b\"",
                ", \"note\": \"base b\"");
        assertThrows(IllegalArgumentException.class, () -> AnchorTable.parse(bad));
    }

    @Test
    public void parseRejectsKeyOutsideMountId() {
        String bad = SYNTHETIC.replace("\"F/New#c\":", "\"F/Other#c\":");
        assertThrows(IllegalArgumentException.class, () -> AnchorTable.parse(bad));
    }

    @Test
    public void parseRejectsVariantKeyWithoutLogicalName() {
        String bad = SYNTHETIC.replace(
                "\"note\": \"2.0 override\", \"logicalName\": \"Base#a\",",
                "\"note\": \"2.0 override\",");
        assertThrows(IllegalArgumentException.class, () -> AnchorTable.parse(bad));
    }

    @Test
    public void loadReadsBundledAnchorsJson() {
        AnchorTable table = AnchorTable.load(AnchorTableTest.class.getClassLoader());
        assertNotNull("build 需要把 app/src/main/resources/anchors.json 打进测试 classpath",
                table);
        assertEquals(4, table.versions().size());
        assertNotNull(table.versionByCode(9006015L));
        assertEquals(145, table.versionByCode(9006015L).profile.mounts.size());
        assertNotNull(table.versionByCode(9006020L));
        assertEquals(143, table.versionByCode(9006020L).profile.mounts.size());
        assertEquals(209, table.anchorKeys().size());
    }

    /** profile.remove：同 id 的 mount 不再从 base 继承，且先于 mounts 应用。 */
    @Test
    public void removeDropsInheritedMountBeforeApplyingMounts() {
        String json = SYNTHETIC.replace(
                "\"versionName\": \"2.0\", \"versionCode\": 2, \"base\": \"1.0\",",
                "\"versionName\": \"2.0\", \"versionCode\": 2, \"base\": \"1.0\","
                        + " \"remove\": [\"F/Base#b\"],");
        TargetProfile profile = AnchorTable.parse(json).versionByName("2.0").profile;
        assertEquals(Arrays.asList("F/Base#a", "F/New#c"), ids(profile));
        assertNull(find(profile, "F/Base#b"));
    }

    /** remove 删除不存在的 mount id 是 build 期数据错误。 */
    @Test
    public void parseRejectsRemoveOfUnknownMountId() {
        String bad = SYNTHETIC.replace(
                "\"versionName\": \"2.0\", \"versionCode\": 2, \"base\": \"1.0\",",
                "\"versionName\": \"2.0\", \"versionCode\": 2, \"base\": \"1.0\","
                        + " \"remove\": [\"F/Missing#z\"],");
        assertThrows(IllegalArgumentException.class, () -> AnchorTable.parse(bad));
    }

    /** remove 元素必须是 feature/logicalName，而不是 anchor key。 */
    @Test
    public void parseRejectsBadRemoveElement() {
        String bad = SYNTHETIC.replace(
                "\"versionName\": \"2.0\", \"versionCode\": 2, \"base\": \"1.0\",",
                "\"versionName\": \"2.0\", \"versionCode\": 2, \"base\": \"1.0\","
                        + " \"remove\": [\"F/Base#a@2.0\"],");
        assertThrows(IllegalArgumentException.class, () -> AnchorTable.parse(bad));
    }

    /** remove 必须是数组，字符串形式不能静默忽略。 */
    @Test
    public void parseRejectsNonArrayRemove() {
        String bad = SYNTHETIC.replace(
                "\"versionName\": \"2.0\", \"versionCode\": 2, \"base\": \"1.0\",",
                "\"versionName\": \"2.0\", \"versionCode\": 2, \"base\": \"1.0\","
                        + " \"remove\": \"F/Base#b\",");
        assertThrows(IllegalArgumentException.class, () -> AnchorTable.parse(bad));
    }

    private static List<String> names(AnchorTable table) {
        String[] names = new String[table.versions().size()];
        for (int i = 0; i < names.length; i++) {
            names[i] = table.versions().get(i).versionName;
        }
        return Arrays.asList(names);
    }

    private static List<String> ids(TargetProfile profile) {
        String[] ids = new String[profile.mounts.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = profile.mounts.get(i).id;
        }
        return Arrays.asList(ids);
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void assertThrows(Class<? extends Throwable> expected,
                                     ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (Throwable t) {
            if (expected.isInstance(t)) {
                return;
            }
            fail("expected " + expected.getName() + " but got " + t);
        }
        fail("expected " + expected.getName() + " but nothing was thrown");
    }
}
