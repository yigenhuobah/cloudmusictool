package com.cloudmusictool.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 「我的」背景文件替换/清除的失败语义（纯 JVM，真实临时文件）。
 *
 * <p>覆盖两条曾经会丢数据或误报成功的路径：
 * <ul>
 *   <li>替换旧图的重试失败时必须保留旧图（不能先 delete 再重试）；</li>
 *   <li>任何一路删除失败都要返回 false，调用方不能再提示“已清除”。</li>
 * </ul>
 */
public class MineBackgroundStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void replaceExisting_swapsInNewImageAndDropsBackup() throws Exception {
        File tmp = folder.newFile("mine_background.tmp");
        File dest = folder.newFile("mine_background.jpg");
        File backup = new File(folder.getRoot(), "mine_background.jpg.bak");
        write(tmp, "new");
        write(dest, "old");

        assertTrue(MineBackgroundStore.replaceExisting(
                tmp, dest, backup, File::renameTo));

        assertEquals("new", read(dest));
        assertFalse("成功后备份必须清掉", backup.exists());
    }

    @Test
    public void replaceExisting_retryFailureRestoresOldImage() throws Exception {
        File tmp = folder.newFile("mine_background.tmp");
        File dest = folder.newFile("mine_background.jpg");
        File backup = new File(folder.getRoot(), "mine_background.jpg.bak");
        write(tmp, "new");
        write(dest, "old");
        // 只让“新图 -> 目标路径”失败，模拟 ROM 不允许覆盖目标文件。
        MineBackgroundStore.Mover mover = (from, to) ->
                !(from.equals(tmp) && to.equals(dest)) && from.renameTo(to);

        assertFalse(MineBackgroundStore.replaceExisting(tmp, dest, backup, mover));

        assertEquals("重试失败后旧背景必须还在目标路径", "old", read(dest));
        assertFalse("旧图已恢复，备份不应留下", backup.exists());
    }

    @Test
    public void replaceExisting_backupFailureKeepsOldImage() throws Exception {
        File tmp = folder.newFile("mine_background.tmp");
        File dest = folder.newFile("mine_background.jpg");
        File backup = new File(folder.getRoot(), "mine_background.jpg.bak");
        write(tmp, "new");
        write(dest, "old");
        MineBackgroundStore.Mover mover = (from, to) ->
                !from.equals(dest) && from.renameTo(to);

        assertFalse(MineBackgroundStore.replaceExisting(tmp, dest, backup, mover));

        assertEquals("移走旧图失败时不能继续替换", "old", read(dest));
        assertFalse(backup.exists());
    }

    @Test
    public void replaceExisting_restoreFallsBackToCopy() throws Exception {
        File tmp = folder.newFile("mine_background.tmp");
        File dest = folder.newFile("mine_background.jpg");
        File backup = new File(folder.getRoot(), "mine_background.jpg.bak");
        write(tmp, "new");
        write(dest, "old");
        // 只允许“旧图 -> 备份”；新图落位与备份还原的 rename 都失败，走拷贝还原。
        MineBackgroundStore.Mover mover = (from, to) -> {
            if (from.equals(tmp) || from.equals(backup)) {
                return false;
            }
            return from.renameTo(to);
        };

        assertFalse(MineBackgroundStore.replaceExisting(tmp, dest, backup, mover));

        assertEquals("rename 还原不可用时必须降级拷贝旧图", "old", read(dest));
    }

    @Test
    public void replaceExisting_withoutOldImageMovesNewImage() throws Exception {
        File tmp = folder.newFile("mine_background.tmp");
        File dest = new File(folder.getRoot(), "mine_background.jpg");
        File backup = new File(folder.getRoot(), "mine_background.jpg.bak");
        write(tmp, "new");

        assertTrue(MineBackgroundStore.replaceExisting(
                tmp, dest, backup, File::renameTo));

        assertEquals("new", read(dest));
        assertFalse(backup.exists());
    }

    @Test
    public void deleteBackgroundFiles_reportsSuccessWhenAllGone() throws Exception {
        File internal = folder.newFile("mine_background.jpg");
        File tmp = folder.newFile("mine_background.tmp");
        File external = folder.newFile("external.jpg");
        File externalTmp = folder.newFile("external.jpg.tmp");
        write(internal, "a");
        write(tmp, "b");
        write(external, "c");
        write(externalTmp, "d");

        assertTrue(MineBackgroundStore.deleteBackgroundFiles(
                internal, tmp, external, externalTmp));

        assertFalse(internal.exists());
        assertFalse(tmp.exists());
        assertFalse(external.exists());
        assertFalse(externalTmp.exists());
    }

    @Test
    public void deleteBackgroundFiles_reportsFailureWhenDeleteFails() throws Exception {
        // 非空目录无法用 File.delete() 删除，模拟删除失败。
        File internal = folder.newFolder("mine_background.jpg");
        folder.newFile("mine_background.jpg/child");
        File tmp = folder.newFile("mine_background.tmp");
        File external = folder.newFile("external.jpg");
        File externalTmp = folder.newFile("external.jpg.tmp");

        assertFalse("任一文件删不掉都不能报告已清除",
                MineBackgroundStore.deleteBackgroundFiles(
                        internal, tmp, external, externalTmp));

        assertTrue("删不掉的文件必须保留，供调用方如实提示", internal.exists());
        assertFalse(tmp.exists());
        assertFalse(external.exists());
        assertFalse(externalTmp.exists());
    }

    private static void write(File file, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
