package com.cloudmusictool.app;

import com.cloudmusictool.app.profile.MountSpec;
import com.cloudmusictool.app.profile.Profile9608;
import com.cloudmusictool.app.profile.Profile9613;
import com.cloudmusictool.app.profile.ProfileRegistry;
import com.cloudmusictool.app.profile.TargetProfile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * 导出各适配版本的挂载点清单，供 {@code tools/check-mounts.sh} 使用。
 *
 * 输出路径优先 {@code <module>/build/profile<ver>.json}：
 * - Gradle 单测默认工作目录是 app 模块，因此通常是 {@code app/build/profile<ver>.json}；
 * - 若从仓库根目录运行，则自动落到 {@code app/build/profile<ver>.json}。
 *
 * 同时校验清单自身的一致性，避免 Profile 漂移（两个版本共用同一套断言）：
 * 1) id 唯一；2) classCandidates 非空；3) 每个有挂载点的 Feature 至少一个 critical；
 * 4) 所有未 blocked 的 UI Feature 都必须有挂载点与 critical。
 */
public class ProfileExportTest {

    @Test
    public void exportProfile9608() throws Exception {
        exportAndVerify(Profile9608.PROFILE, "profile9608.json");
    }

    @Test
    public void exportProfile9613() throws Exception {
        exportAndVerify(Profile9613.PROFILE, "profile9613.json");
    }

    @Test
    public void registryMapsBothVersions() {
        assertSame(Profile9608.PROFILE, ProfileRegistry.forVersionCode(9006008L));
        assertSame(Profile9608.PROFILE, ProfileRegistry.forVersionName("9.6.08"));
        assertSame(Profile9613.PROFILE, ProfileRegistry.forVersionCode(9006013L));
        assertSame(Profile9613.PROFILE, ProfileRegistry.forVersionName("9.6.13"));
        assertEquals(null, ProfileRegistry.forVersionCode(9006009L));
        assertEquals(null, ProfileRegistry.forVersionName("9.6.09"));
    }

    private static void exportAndVerify(TargetProfile profile, String outName) throws Exception {
        assertNotNull(profile);
        assertFalse("profile has no mounts", profile.mounts.isEmpty());

        File moduleDir = resolveModuleDir();
        File buildDir = new File(moduleDir, "build");
        if (!buildDir.exists() && !buildDir.mkdirs()) {
            throw new IllegalStateException("cannot create " + buildDir.getAbsolutePath());
        }
        File outFile = new File(buildDir, outName);

        JSONObject root = new JSONObject();
        root.put("versionCode", profile.versionCode);
        root.put("versionName", profile.versionName);

        JSONArray mounts = new JSONArray();
        Set<String> ids = new HashSet<>();
        Map<String, Integer> criticalByFeature = new HashMap<>();

        for (MountSpec spec : profile.mounts) {
            assertNotNull("featureId is null", spec.featureId);
            assertNotNull("logicalName is null for " + spec.featureId, spec.logicalName);
            assertTrue("duplicate mount id: " + spec.id, ids.add(spec.id));
            assertNotNull("classCandidates is null for " + spec.id, spec.classCandidates);
            assertTrue("classCandidates is empty for " + spec.id,
                    spec.classCandidates.length > 0);
            for (String candidate : spec.classCandidates) {
                assertNotNull("null class candidate for " + spec.id, candidate);
                assertFalse("empty class candidate for " + spec.id, candidate.isEmpty());
            }
            if (spec.methodName != null) {
                assertNotNull("returnType is null for " + spec.id, spec.returnType);
                assertNotNull("paramTypes is null for " + spec.id, spec.paramTypes);
            }
            if (spec.critical) {
                Integer n = criticalByFeature.get(spec.featureId);
                criticalByFeature.put(spec.featureId, n == null ? 1 : n + 1);
            }

            JSONObject o = new JSONObject();
            o.put("id", spec.id);
            o.put("featureId", spec.featureId);
            o.put("logicalName", spec.logicalName);
            JSONArray classCandidates = new JSONArray();
            for (String candidate : spec.classCandidates) {
                classCandidates.put(candidate);
            }
            o.put("classCandidates", classCandidates);
            o.put("methodName", spec.methodName == null ? JSONObject.NULL : spec.methodName);
            o.put("returnType", spec.returnType == null ? JSONObject.NULL : spec.returnType);
            JSONArray paramTypes = new JSONArray();
            if (spec.paramTypes != null) {
                for (String param : spec.paramTypes) {
                    paramTypes.put(param);
                }
            }
            o.put("paramTypes", paramTypes);
            JSONArray anchors = new JSONArray();
            if (spec.anchors != null) {
                for (String anchor : spec.anchors) {
                    anchors.put(anchor);
                }
            }
            o.put("anchors", anchors);
            o.put("critical", spec.critical);
            mounts.put(o);
        }
        root.put("mounts", mounts);

        String json = root.toString(2);
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(outFile), StandardCharsets.UTF_8)) {
            writer.write(json);
        }
        System.out.println(outName + " exported: " + outFile.getAbsolutePath()
                + " mounts=" + profile.mounts.size());

        // 1) 每个有挂载点的 Feature 至少一个 critical。
        for (String featureId : distinctFeatureIds(profile.mounts)) {
            Integer critical = criticalByFeature.get(featureId);
            assertNotNull("no critical mount for " + featureId, critical);
            assertTrue("no critical mount for " + featureId, critical > 0);
        }

        // 2) 所有未 blocked 的 Feature 都必须被 Profile 覆盖（UI-06 blocked，按计划跳过）。
        for (Feature f : Feature.values()) {
            if (f.blocked()) {
                continue;
            }
            assertTrue("feature has no mounts: " + f.id,
                    !profile.forFeature(f.id).isEmpty());
            assertTrue("feature has no critical mount: " + f.id,
                    profile.criticalCount(f.id) > 0);
        }

        // 3) EN-01 的 critical 必须落在真实挂载点 MyMusicProfileHeaderBgViewHolderManager#e。
        // RnMineFragment#onResume 全仓零消费（死坐标），曾以 critical 名义满足上面的断言，禁止回归。
        MountSpec en01Critical = null;
        for (MountSpec spec : profile.forFeature("EN-01")) {
            assertFalse("dead mount registered: " + spec.id,
                    "EN-01/RnMineFragment#onResume".equals(spec.id));
            if (spec.critical && "EN-01/MyMusicProfileHeaderBgViewHolderManager#e".equals(spec.id)) {
                en01Critical = spec;
            }
        }
        assertNotNull("EN-01 critical not on real mount", en01Critical);

        // 4) 回读导出文件，确保 JSON 可解析且条目数一致。
        String exported = new String(java.nio.file.Files.readAllBytes(outFile.toPath()),
                StandardCharsets.UTF_8);
        JSONObject parsed = new JSONObject(exported);
        assertEquals(profile.versionCode, parsed.getLong("versionCode"));
        assertEquals(profile.versionName, parsed.getString("versionName"));
        assertEquals(profile.mounts.size(), parsed.getJSONArray("mounts").length());
    }

    private static File resolveModuleDir() {
        File cwd = new File(System.getProperty("user.dir", "."));
        File appDir = new File(cwd, "app");
        return appDir.isDirectory() ? appDir : cwd;
    }

    private static Set<String> distinctFeatureIds(List<MountSpec> mounts) {
        Set<String> out = new HashSet<>();
        for (MountSpec spec : mounts) {
            out.add(spec.featureId);
        }
        return out;
    }
}
