package com.cloudmusictool.app;

import android.content.pm.PackageManager;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 组件启用状态到「图标是否隐藏」的映射契约测试。
 * 只覆盖纯函数与 manifest 契约，不依赖 Context 与 PackageManager 实例。
 */
public class LauncherIconTest {

    @Test
    public void isHiddenState_defaultIsVisible() {
        assertEquals("DEFAULT 应视为未隐藏",
                Boolean.FALSE,
                LauncherIcon.isHiddenState(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT));
    }

    @Test
    public void isHiddenState_enabledIsVisible() {
        assertEquals("ENABLED 应视为未隐藏",
                Boolean.FALSE,
                LauncherIcon.isHiddenState(PackageManager.COMPONENT_ENABLED_STATE_ENABLED));
    }

    @Test
    public void isHiddenState_disabledIsHidden() {
        assertEquals("DISABLED 应视为已隐藏",
                Boolean.TRUE,
                LauncherIcon.isHiddenState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED));
    }

    @Test
    public void isHiddenState_disabledUserIsHidden() {
        assertEquals("DISABLED_USER 应视为已隐藏",
                Boolean.TRUE,
                LauncherIcon.isHiddenState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER));
    }

    @Test
    public void isHiddenState_disabledUntilUsedIsHidden() {
        assertEquals("DISABLED_UNTIL_USED 应视为已隐藏",
                Boolean.TRUE,
                LauncherIcon.isHiddenState(PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED));
    }

    @Test
    public void isHiddenState_unknownValueReturnsNull() {
        assertNull("Integer.MIN_VALUE 属于未知状态，应返回 null",
                LauncherIcon.isHiddenState(Integer.MIN_VALUE));
        assertNull("-1 属于未知状态，应返回 null",
                LauncherIcon.isHiddenState(-1));
        assertNull("5 属于未知状态，应返回 null",
                LauncherIcon.isHiddenState(5));
    }

    /**
     * 代码常量与 manifest 契约：ALIAS_CLASS 必须出现在 AndroidManifest.xml 中，
     * 否则隐藏开关会去改一个不存在的组件。
     * Gradle 单测的工作目录是 app 模块，因此用相对路径读取。
     */
    @Test
    public void manifestDeclaresAliasClass() throws Exception {
        File manifest = new File("src/main/AndroidManifest.xml");
        assertTrue("单测工作目录应位于 app 模块，未找到 manifest：" + manifest.getAbsolutePath(),
                manifest.isFile());
        String text = new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8);
        assertTrue("manifest 缺少 activity-alias：" + LauncherIcon.ALIAS_CLASS,
                text.contains("android:name=\"" + LauncherIcon.ALIAS_CLASS + "\""));
    }
}
