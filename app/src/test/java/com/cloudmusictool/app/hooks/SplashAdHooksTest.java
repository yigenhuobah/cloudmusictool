package com.cloudmusictool.app.hooks;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * AD-01 签名扫描的排除语义（finding 47）：只排除第 1 步实际挂上的判定方法。
 * 修复前按名字无条件跳过（"E" 与 Profile 映射名），第 1 步 install 失败时
 * 映射出的新方法名永远不会被扫描补挂，判定 hook 整体失效。
 */
public class SplashAdHooksTest {

    /** 与 LoadingAdManager#E 同构的最小判定方法集合。 */
    public static final class AdInfoProbe {
    }

    public static final class JudgeProbe {
        public boolean E(AdInfoProbe info) {
            return true;
        }

        public boolean H(AdInfoProbe info) {
            return true;
        }

        public boolean other(AdInfoProbe info) {
            return true;
        }

        public void voidJudge(AdInfoProbe info) {
        }

        public boolean wrongParam(Object info) {
            return true;
        }
    }

    private static Method method(String name) throws Exception {
        return JudgeProbe.class.getDeclaredMethod(name, AdInfoProbe.class);
    }

    @Test
    public void scan_skipsOnlyTheMethodActuallyInstalled() throws Exception {
        List<Method> targets = SplashAdHooks.judgeScanTargets(
                JudgeProbe.class, AdInfoProbe.class, method("E"));

        assertTrue(targets.contains(method("H")));
        assertTrue(targets.contains(method("other")));
        assertFalse("已挂载的判定方法不得重复挂载", targets.contains(method("E")));
    }

    @Test
    public void scan_fallsBackToAllCandidatesWhenPrimaryInstallFailed() throws Exception {
        // 第 1 步定位/安装失败（installedJudge == null）时，E/H 都是补挂目标；
        // 修复前按名字无条件跳过，第 1 步失败的映射名（H）永远无人补挂。
        List<Method> targets = SplashAdHooks.judgeScanTargets(
                JudgeProbe.class, AdInfoProbe.class, null);

        assertTrue("第 1 步失败后 E 必须可被扫描补挂", targets.contains(method("E")));
        assertTrue("第 1 步失败后映射名 H 必须可被扫描补挂", targets.contains(method("H")));
    }

    @Test
    public void scan_keepsSignatureFilter() throws Exception {
        List<Method> targets = SplashAdHooks.judgeScanTargets(
                JudgeProbe.class, AdInfoProbe.class, null);

        assertFalse(targets.contains(JudgeProbe.class.getDeclaredMethod("voidJudge", AdInfoProbe.class)));
        assertFalse(targets.contains(JudgeProbe.class.getDeclaredMethod("wrongParam", Object.class)));
    }
}
