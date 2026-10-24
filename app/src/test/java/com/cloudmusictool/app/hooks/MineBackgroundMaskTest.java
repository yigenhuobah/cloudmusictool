package com.cloudmusictool.app.hooks;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * 整页透出遮罩常量与 9.6.13 反编译基准的一致性测试。
 *
 * 基准来源（base.apk jadx 产物，MyMusicFragmentV4#gd z15 分支）：
 * {@code frameLayout.setBackground(new ColorDrawable(452984831))}，
 * 遮罩 view 为 layout/cyd 中 id/bottomAreaMask（flHeaderImage 子树，
 * match_parent 全页高）。
 */
public class MineBackgroundMaskTest {

    @Test
    public void scrim_matches9613DecompiledConstant() {
        assertEquals(452984831, MineBackgroundHooks.FULLSCREEN_CONTENT_SCRIM);
        assertEquals(0x1AFFFFFF, MineBackgroundHooks.FULLSCREEN_CONTENT_SCRIM);
    }

    @Test
    public void scrim_isLowAlphaWhite() {
        int scrim = MineBackgroundHooks.FULLSCREEN_CONTENT_SCRIM;
        assertEquals("遮罩应为约 10% 不透明度", 0x1A, (scrim >>> 24) & 0xFF);
        assertEquals("遮罩应为白色基底", 0xFFFFFF, scrim & 0x00FFFFFF);
    }

    @Test
    public void maskViewName_isGetIdentifierCompatible() {
        String name = MineBackgroundHooks.CONTENT_MASK_VIEW;
        assertEquals("bottomAreaMask", name);
        assertTrue("资源名须为 getIdentifier 可用的合法标识符（字母开头，仅字母数字下划线）",
                name.matches("[a-zA-Z][a-zA-Z0-9_]*"));
    }
}
