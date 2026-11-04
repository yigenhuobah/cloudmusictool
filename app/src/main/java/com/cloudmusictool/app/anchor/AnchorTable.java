package com.cloudmusictool.app.anchor;

import com.cloudmusictool.app.profile.MountSpec;
import com.cloudmusictool.app.profile.TargetProfile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 锚点表读取器：anchors.json 是挂载点与 DexKit 锚点的唯一来源。
 *
 * <p>公共 core 与 TB/QB/QM 一致（schemaVersion 1）；CMT 额外提供
 * {@code app}/{@code profiles} 做多宿主版本分发，其他模块是单版本，可不带这两段。
 * <pre>
 * {
 *   "schemaVersion": 1,
 *   "generatedBy": "tools/gen_anchors.py",
 *   "app": "com.netease.cloudmusic",
 *   "appVersion": "9.6.15",
 *   "versionCode": 9006015,
 *   "anchors": {
 *     "AD-01/LoadingAdManager#E": {
 *       "c": "com...", "candidates": ["...", "..."], "m": "E",
 *       "s": "(Lcom/...;)Z", "feature": "AD-01", "critical": true,
 *       "note": "...", "logicalName": "LoadingAdManager#E",
 *       "params": ["Lcom/...;"], "dexkit": ["anchor-string"]
 *     }
 *   },
 *   "classes": {},
 *   "profiles": [
 *     { "versionName": "9.6.08", "versionCode": 9006008, "mounts": ["key", ...] },
 *     { "versionName": "9.6.13", "versionCode": 9006013,
 *       "base": "9.6.08", "mounts": ["key", ...] },
 *     { "versionName": "9.6.15", "versionCode": 9006015,
 *       "base": "9.6.13", "mounts": [] }
 *   ]
 * }
 * </pre>
 *
 * <p>profile 的 {@code mounts} 是相对 {@code base} 的增量：先展开 base 链，
 * 再按 mount id（{@code feature + "/" + logicalName}）合并——同 id 原位替换，
 * 新 id 追加。这样 9.6.15 只需 {@code base: 9.6.13} + 空 mounts。
 *
 * <p>方法名/签名随版本漂移的挂载点用 {@code <mountId>@<versionName>} 作为
 * {@code anchors} 的 key，{@code logicalName} 仍保留原 mount id，因此
 * 9.6.08 与 9.6.13 能共享同一张扁平表而不互相覆盖。
 *
 * <p>本类只读数据，不碰框架；解析失败由 {@link #load(ClassLoader)} 返回 null，
 * 调用方（{@link SupportedVersions} / ProfileRegistry）按未适配 fail-open。
 */
public final class AnchorTable {

    public static final String RESOURCE = "anchors.json";
    public static final int SCHEMA_VERSION = 1;

    /** 版本覆盖 key 的后缀分隔符：{@code <mountId>@<versionName>}。 */
    static final String VARIANT_SEPARATOR = "@";

    private final int schemaVersion;
    private final String generatedBy;
    private final String app;
    private final String appVersion;
    private final long versionCode;
    private final Map<String, Anchor> anchors;
    private final Map<String, String> classes;
    private final List<Version> versions;
    private final Map<Long, Version> byCode = new LinkedHashMap<>();
    private final Map<String, Version> byName = new LinkedHashMap<>();

    /** 一条 anchors 记录；字段与 anchors.json 的 anchors 段一一对应。 */
    public static final class Anchor {
        public final String key;
        public final String feature;
        public final String logicalName;
        public final String[] classCandidates;
        public final String methodName;
        public final String returnType;
        public final String[] paramTypes;
        public final String[] dexkitAnchors;
        public final boolean critical;
        public final String note;

        private Anchor(String key, String feature, String logicalName,
                       String[] classCandidates, String methodName, String returnType,
                       String[] paramTypes, String[] dexkitAnchors,
                       boolean critical, String note) {
            this.key = key;
            this.feature = feature;
            this.logicalName = logicalName;
            this.classCandidates = classCandidates;
            this.methodName = methodName;
            this.returnType = returnType;
            this.paramTypes = paramTypes;
            this.dexkitAnchors = dexkitAnchors;
            this.critical = critical;
            this.note = note;
        }

        /** mount id = feature/logicalName；版本变体 key 不改变它。 */
        public String mountId() {
            return feature + "/" + logicalName;
        }

        private MountSpec toMountSpec() {
            return MountSpec.method(feature, logicalName, critical, classCandidates,
                    methodName, returnType, paramTypes, dexkitAnchors);
        }
    }

    /** 一个版本与由 anchors.json 派生出的挂载表。 */
    public static final class Version {
        public final long versionCode;
        public final String versionName;
        public final TargetProfile profile;

        Version(long versionCode, String versionName, TargetProfile profile) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.profile = profile;
        }
    }

    private AnchorTable(JSONObject root) {
        schemaVersion = requireInt(root, "schemaVersion");
        if (schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        generatedBy = requireString(root, "generatedBy");
        app = requireString(root, "app");
        appVersion = requireString(root, "appVersion");
        versionCode = requireLong(root, "versionCode");

        JSONObject anchorsJson = root.optJSONObject("anchors");
        if (anchorsJson == null || anchorsJson.length() == 0) {
            throw new IllegalArgumentException("missing or empty anchors section");
        }
        anchors = parseAnchors(anchorsJson);
        classes = parseClasses(root.optJSONObject("classes"));

        JSONArray profiles = root.optJSONArray("profiles");
        if (profiles == null || profiles.length() == 0) {
            throw new IllegalArgumentException("anchors.json has no profiles");
        }
        Map<String, JSONObject> profileJson = new LinkedHashMap<>();
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject profile = profiles.optJSONObject(i);
            if (profile == null) {
                throw new IllegalArgumentException("profile[" + i + "] is not an object");
            }
            String name = requireString(profile, "versionName");
            if (profileJson.put(name, profile) != null) {
                throw new IllegalArgumentException("duplicate profile versionName: " + name);
            }
        }
        List<Version> out = new ArrayList<>();
        for (String name : profileJson.keySet()) {
            JSONObject profile = profileJson.get(name);
            long code = requireLong(profile, "versionCode");
            List<JSONObject> chain = resolveChain(name, profileJson);
            List<MountSpec> mounts = buildMounts(chain);
            Version version = new Version(code, name, new TargetProfile(code, name, mounts));
            if (byCode.put(code, version) != null) {
                throw new IllegalArgumentException("duplicate versionCode: " + code);
            }
            if (byName.put(name, version) != null) {
                throw new IllegalArgumentException("duplicate versionName: " + name);
            }
            out.add(version);
        }
        versions = Collections.unmodifiableList(out);

        Version latest = byName.get(appVersion);
        if (latest == null) {
            throw new IllegalArgumentException("appVersion is not a profile: " + appVersion);
        }
        if (latest.versionCode != versionCode) {
            throw new IllegalArgumentException("top-level versionCode " + versionCode
                    + " != " + appVersion + " versionCode " + latest.versionCode);
        }
    }

    /** 从 classpath 读取 anchors.json；缺失或解析失败返回 null（调用方 fail-open）。 */
    public static AnchorTable load(ClassLoader loader) {
        ClassLoader cl = loader != null ? loader : AnchorTable.class.getClassLoader();
        if (cl == null) {
            return null;
        }
        try (InputStream stream = cl.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                char[] buffer = new char[4096];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    text.append(buffer, 0, read);
                }
            }
            return new AnchorTable(new JSONObject(text.toString()));
        } catch (Throwable t) {
            return null;
        }
    }

    /** 测试入口：直接解析字符串；格式错误抛 IllegalArgumentException。 */
    public static AnchorTable parse(String json) {
        try {
            return new AnchorTable(new JSONObject(json));
        } catch (JSONException e) {
            throw new IllegalArgumentException("invalid anchors.json", e);
        }
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    public String generatedBy() {
        return generatedBy;
    }

    public String app() {
        return app;
    }

    public String appVersion() {
        return appVersion;
    }

    public long versionCode() {
        return versionCode;
    }

    /** 全部版本，按 anchors.json 声明顺序。 */
    public List<Version> versions() {
        return versions;
    }

    public Version versionByCode(long code) {
        return byCode.get(code);
    }

    public Version versionByName(String name) {
        return byName.get(name);
    }

    /** 全部 anchors key（含 {@code @version} 变体），按声明顺序。 */
    public Set<String> anchorKeys() {
        return Collections.unmodifiableSet(anchors.keySet());
    }

    /** 指定 key 的锚点记录；不存在返回 null。 */
    public Anchor anchor(String key) {
        return anchors.get(key);
    }

    /** 类级锚点（key -> 类名）；没有类级锚点时为空。 */
    public Map<String, String> classes() {
        return classes;
    }

    // ------------------------------------------------------------------ parse

    private static Map<String, Anchor> parseAnchors(JSONObject json) {
        Map<String, Anchor> out = new LinkedHashMap<>();
        java.util.Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject entry = json.optJSONObject(key);
            if (entry == null) {
                throw new IllegalArgumentException("anchor is not an object: " + key);
            }
            Anchor anchor = parseAnchor(key, entry);
            if (out.put(key, anchor) != null) {
                throw new IllegalArgumentException("duplicate anchor key: " + key);
            }
        }
        return out;
    }

    private static Anchor parseAnchor(String key, JSONObject entry) {
        String feature = requireString(entry, "feature");
        String logicalName = optString(entry, "logicalName");
        String mountId;
        if (logicalName == null) {
            String prefix = feature + "/";
            if (!key.startsWith(prefix) || key.length() == prefix.length()) {
                throw new IllegalArgumentException(
                        "anchor key must be feature/logicalName: " + key);
            }
            if (key.indexOf(VARIANT_SEPARATOR) >= 0) {
                throw new IllegalArgumentException(
                        "variant anchor key needs logicalName: " + key);
            }
            logicalName = key.substring(prefix.length());
            mountId = key;
        } else {
            mountId = feature + "/" + logicalName;
            if (!key.equals(mountId)
                    && !key.startsWith(mountId + VARIANT_SEPARATOR)) {
                throw new IllegalArgumentException(
                        "anchor key is not " + mountId + " or " + mountId + "@version: " + key);
            }
            if (key.startsWith(mountId + VARIANT_SEPARATOR)
                    && key.length() == mountId.length() + VARIANT_SEPARATOR.length()) {
                throw new IllegalArgumentException("empty anchor variant: " + key);
            }
        }
        if (!entry.has("critical") || entry.isNull("critical")) {
            throw new IllegalArgumentException("missing critical: " + key);
        }
        Object criticalValue = entry.opt("critical");
        if (!(criticalValue instanceof Boolean)) {
            throw new IllegalArgumentException("critical must be boolean: " + key);
        }
        boolean critical = (Boolean) criticalValue;
        String note = requireString(entry, "note");

        String[] classCandidates = parseCandidates(key, entry, requireString(entry, "c"));
        String methodName = optString(entry, "m");
        if (methodName == null) {
            throw new IllegalArgumentException("anchor has no method: " + key);
        }
        String signature = requireString(entry, "s");
        String[] paramTypes = splitParamTypes(signature);
        String returnType = returnType(signature);
        JSONArray paramsJson = entry.optJSONArray("params");
        if (paramsJson != null) {
            String[] declared = toStringArray(paramsJson, key, "params");
            if (!Arrays.equals(declared, paramTypes)) {
                throw new IllegalArgumentException("params != s params: " + key);
            }
        }
        String[] dexkit = parseDexkit(key, entry);
        return new Anchor(key, feature, logicalName, classCandidates, methodName,
                returnType, paramTypes, dexkit, critical, note);
    }

    private static String[] parseCandidates(String key, JSONObject entry, String primary) {
        if (primary.isEmpty()) {
            throw new IllegalArgumentException("empty class candidate: " + key);
        }
        JSONArray array = entry.optJSONArray("candidates");
        if (array == null) {
            return new String[]{primary};
        }
        String[] out = toStringArray(array, key, "candidates");
        if (out.length < 2) {
            throw new IllegalArgumentException("candidates needs at least two entries: " + key);
        }
        if (!primary.equals(out[0])) {
            throw new IllegalArgumentException("c != candidates[0]: " + key);
        }
        return out;
    }

    /** {@code dexkit} 是内联的 dex 字符串字面量数组（无则返回空数组）。 */
    private static String[] parseDexkit(String key, JSONObject entry) {
        JSONArray array = entry.optJSONArray("dexkit");
        if (array == null) {
            if (entry.has("dexkit") && !entry.isNull("dexkit")) {
                throw new IllegalArgumentException(
                        "dexkit must be an array of anchor strings: " + key);
            }
            return new String[0];
        }
        String[] out = toStringArray(array, key, "dexkit");
        if (out.length == 0) {
            throw new IllegalArgumentException("empty dexkit: " + key);
        }
        return out;
    }

    private static Map<String, String> parseClasses(JSONObject json) {
        if (json == null) {
            throw new IllegalArgumentException("missing classes section");
        }
        Map<String, String> out = new LinkedHashMap<>();
        java.util.Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String value = json.optString(key, "");
            if (value.isEmpty()) {
                throw new IllegalArgumentException("empty class anchor: " + key);
            }
            out.put(key, value);
        }
        return Collections.unmodifiableMap(out);
    }

    /** 返回 base 链（根在前，当前版本在后），并检测缺失/环。 */
    private static List<JSONObject> resolveChain(String name,
                                                 Map<String, JSONObject> profileJson) {
        List<JSONObject> reversed = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String current = name;
        while (current != null) {
            if (!seen.add(current)) {
                throw new IllegalArgumentException("profile base cycle at: " + current);
            }
            JSONObject profile = profileJson.get(current);
            if (profile == null) {
                throw new IllegalArgumentException("profile base not found: " + current);
            }
            reversed.add(profile);
            String base = optString(profile, "base");
            current = (base == null || base.isEmpty()) ? null : base;
        }
        List<JSONObject> chain = new ArrayList<>(reversed);
        Collections.reverse(chain);
        return chain;
    }

    private List<MountSpec> buildMounts(List<JSONObject> chain) {
        Map<String, MountSpec> merged = new LinkedHashMap<>();
        for (JSONObject profile : chain) {
            String versionName = requireString(profile, "versionName");
            JSONArray mounts = profile.optJSONArray("mounts");
            if (mounts == null) {
                throw new IllegalArgumentException(
                        "profile has no mounts: " + versionName);
            }
            Set<String> keysInProfile = new LinkedHashSet<>();
            for (int i = 0; i < mounts.length(); i++) {
                String key = mounts.optString(i, "");
                if (key.isEmpty()) {
                    throw new IllegalArgumentException(
                            "empty mount key in profile " + versionName);
                }
                if (!keysInProfile.add(key)) {
                    throw new IllegalArgumentException(
                            "duplicate mount key in profile " + versionName + ": " + key);
                }
                Anchor anchor = anchors.get(key);
                if (anchor == null) {
                    throw new IllegalArgumentException(
                            "profile " + versionName + " references unknown anchor: " + key);
                }
                MountSpec spec = anchor.toMountSpec();
                // LinkedHashMap.put 保留首次插入位置：同 mount id 原位替换，新 id 追加。
                merged.put(spec.id, spec);
            }
        }
        return new ArrayList<>(merged.values());
    }

    private static String[] toStringArray(JSONArray array, String key, String field) {
        String[] out = new String[array.length()];
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "");
            if (value.isEmpty()) {
                throw new IllegalArgumentException("empty " + field + " in " + key);
            }
            out[i] = value;
        }
        return out;
    }

    /**
     * 解析 "(Lcom/x;I[Ljava/lang/String;Z)V" 的参数段；返回类型单独由
     * {@link #returnType(String)} 解析。描述符非法直接抛异常（build 期错误）。
     */
    static String[] splitParamTypes(String signature) {
        if (signature == null || !signature.startsWith("(")) {
            throw new IllegalArgumentException("bad signature: " + signature);
        }
        int end = signature.indexOf(')');
        if (end < 0) {
            throw new IllegalArgumentException("bad signature: " + signature);
        }
        List<String> out = new ArrayList<>();
        int i = 1;
        while (i < end) {
            int next = descriptorEnd(signature, i, end);
            if (next < 0) {
                throw new IllegalArgumentException("bad signature: " + signature);
            }
            out.add(signature.substring(i, next));
            i = next;
        }
        return out.toArray(new String[0]);
    }

    /** 从 "(...)R" 取返回类型描述符（R 允许 V）。 */
    static String returnType(String signature) {
        if (signature == null) {
            throw new IllegalArgumentException("bad signature: null");
        }
        int end = signature.indexOf(')');
        if (end < 0 || end + 1 >= signature.length()) {
            throw new IllegalArgumentException("bad signature: " + signature);
        }
        String ret = signature.substring(end + 1);
        if ("V".equals(ret)) {
            return ret;
        }
        int next = descriptorEnd(signature, end + 1, signature.length());
        if (next != signature.length()) {
            throw new IllegalArgumentException("bad return type: " + signature);
        }
        return ret;
    }

    /** 返回从 start 开始的一个类型描述符结束位置；非法返回 -1。V 不允许作参数。 */
    private static int descriptorEnd(String text, int start, int limit) {
        int i = start;
        while (i < limit && text.charAt(i) == '[') {
            i++;
        }
        if (i >= limit) {
            return -1;
        }
        char c = text.charAt(i);
        if (c == 'L') {
            int semi = text.indexOf(';', i);
            if (semi < 0 || semi >= limit) {
                return -1;
            }
            return semi + 1;
        }
        if ("ZBCSIJFD".indexOf(c) >= 0) {
            return i + 1;
        }
        return -1;
    }

    private static String requireString(JSONObject object, String field) {
        String value = optString(object, field);
        if (value == null) {
            throw new IllegalArgumentException("missing " + field);
        }
        return value;
    }

    private static String optString(JSONObject object, String field) {
        if (!object.has(field) || object.isNull(field)) {
            return null;
        }
        String value = object.optString(field, "");
        if (value.isEmpty()) {
            return null;
        }
        return value;
    }

    private static int requireInt(JSONObject object, String field) {
        if (!object.has(field) || object.isNull(field)) {
            throw new IllegalArgumentException("missing " + field);
        }
        int value = object.optInt(field, -1);
        if (value < 0) {
            throw new IllegalArgumentException("bad " + field + ": " + value);
        }
        return value;
    }

    private static long requireLong(JSONObject object, String field) {
        if (!object.has(field) || object.isNull(field)) {
            throw new IllegalArgumentException("missing " + field);
        }
        long value = object.optLong(field, -1L);
        if (value <= 0) {
            throw new IllegalArgumentException("bad " + field + ": " + value);
        }
        return value;
    }
}
