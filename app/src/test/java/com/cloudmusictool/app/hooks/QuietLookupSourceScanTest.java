package com.cloudmusictool.app.hooks;

import io.github.yigenhuobah.core.test.SourceScan;

import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 源码门禁：{@code hooks/} 下所有 quiet 定位调用必须使用带
 * {@code XposedModule module, Feature f, String label} 的记账重载。
 *
 * <p>旧的无上下文重载失败后既不 WARN 也不写 HookState，目标方法改名时功能会
 * 静默消失（诊断页看起来一切正常）。core-test 的 {@link SourceScan} 已内置这四条
 * 规则，这里把模块的 hooks 源码目录接上规则，作为编译期之外的持续门禁。
 */
public class QuietLookupSourceScanTest {

    @Test
    public void hooksSourcesUseScopedQuietLookupOverloads() throws Exception {
        Path hooks = locateHooksSources();
        List<SourceScan.Finding> findings = SourceScan.scan(
                hooks, SourceScan.cmtQuietLookupRules());
        assertTrue("quiet 查找必须带 Feature/label 记账重载，实际命中:\n"
                + render(findings), findings.isEmpty());
    }

    /** 反向对照：规则本身必须能抓住旧的无上下文重载，避免门禁空转。 */
    @Test
    public void ruleFlagsLegacyUnscopedOverload() {
        List<SourceScan.Finding> findings = SourceScan.scanSource(
                "class X { void a() { HookKit.findMethodQuiet(Foo.class, \"x\"); } }",
                "X.java", SourceScan.cmtQuietLookupRules());
        assertFalse("旧重载必须被规则命中", findings.isEmpty());
    }

    /** 正向对照：带 module/Feature/label 的重载不能被误报。 */
    @Test
    public void ruleAllowsScopedOverload() {
        List<SourceScan.Finding> findings = SourceScan.scanSource(
                "class X { void a() { HookKit.findMethodQuiet(module, f, \"label\", Foo.class, \"x\"); } }",
                "X.java", SourceScan.cmtQuietLookupRules());
        assertTrue("记账重载不应被误报: " + render(findings), findings.isEmpty());
    }

    /**
     * Gradle 单测工作目录可能是模块根目录或 app/；与其它纯 JVM 源码契约测试
     * 一样依次尝试候选路径，全部不存在时明确失败。
     */
    private static Path locateHooksSources() {
        File cwd = new File(System.getProperty("user.dir", "."));
        File[] candidates = {
                new File(cwd, "src/main/java/com/cloudmusictool/app/hooks"),
                new File(cwd, "app/src/main/java/com/cloudmusictool/app/hooks"),
                new File(cwd, "../app/src/main/java/com/cloudmusictool/app/hooks"),
        };
        for (File candidate : candidates) {
            if (candidate.isDirectory()) {
                return candidate.toPath();
            }
        }
        fail("hooks source directory not found; cwd=" + cwd.getAbsolutePath());
        return null; // unreachable
    }

    private static String render(List<SourceScan.Finding> findings) {
        StringBuilder out = new StringBuilder();
        for (SourceScan.Finding finding : findings) {
            out.append("  ").append(finding).append('\n');
        }
        if (out.length() == 0) {
            out.append("  (none)");
        }
        return out.toString();
    }
}
