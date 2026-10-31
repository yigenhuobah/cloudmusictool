package com.cloudmusictool.app;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「我的」页自定义背景的本地存储。
 *
 * 用户选择的图片统一压缩成 JPEG，落在模块 App 私有目录的固定文件
 * {@code files/mine_background.jpg}；目标进程只能通过 StatusProvider 的
 * {@code /mine_background} 读取，不接触用户选择的原始 URI。
 */
public final class MineBackgroundStore {

    private static final String TAG = "CloudMusicTool";
    private static final String FILE_NAME = "mine_background.jpg";
    private static final String TMP_NAME = "mine_background.tmp";
    /** 共享副本写入用同目录临时文件后缀：先写 {@code <最终名>.tmp} 再 rename 原子替换。 */
    private static final String TMP_SUFFIX = ".tmp";
    /** 存储上限：宽度 1440、高度 2560，等比缩放，不拉伸。 */
    private static final int MAX_WIDTH = 1440;
    private static final int MAX_HEIGHT = 2560;
    private static final int JPEG_QUALITY = 90;

    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "CloudMusicTool-MineBackground");
        t.setDaemon(true);
        return t;
    });
    /** 主线程 Handler 懒初始化：静态初始化不构造 Android 对象，文件操作可被纯 JVM 单测直接加载。 */
    private static volatile Handler sMainHandler;

    private static Handler mainHandler() {
        Handler handler = sMainHandler;
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
            sMainHandler = handler;
        }
        return handler;
    }

    /** 异步导入/清除的结果回调，保证在主线程执行。 */
    public interface Callback {
        void onResult(boolean ok);
    }

    private MineBackgroundStore() {
    }

    public static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    /**
     * 目标进程直接读取的共享路径：Android/media/&lt;pkg&gt;/mine_background.jpg。
     *
     * MIUI/HyperOS 的 WakePathChecker 会拦截「网易云 -> 模块 App」的 Provider 唤醒，
     * 导致 openFile 走不通；这里再留一份共享副本，目标进程有 READ_MEDIA_IMAGES
     * 时可以直接按路径读。
     */
    public static File externalFile(Context context) {
        try {
            File[] dirs = context.getExternalMediaDirs();
            if (dirs != null) {
                for (File dir : dirs) {
                    if (dir == null) {
                        continue;
                    }
                    if (!dir.exists() && !dir.mkdirs()) {
                        continue;
                    }
                    return new File(dir, FILE_NAME);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "external media dir unavailable", t);
        }
        return null;
    }

    /**
     * 把已有内部图片补一份到共享目录；模块 App 打开定制页时调用。
     *
     * <p>投递到与导入/清除共用的单线程队列执行，本方法投递后立即返回：
     * 与在途导入/清除按 FIFO 串行，不会交错出半写或被覆盖的终态。
     */
    public static void syncExternal(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        final Context ctx = app != null ? app : context;
        sExecutor.execute(() -> syncExternalSync(ctx));
    }

    private static void syncExternalSync(Context context) {
        try {
            File internal = file(context);
            File external = externalFile(context);
            if (external == null || !internal.isFile() || internal.length() <= 0) {
                return;
            }
            if (external.isFile() && external.length() == internal.length()) {
                return;
            }
            copy(internal, external);
        } catch (Throwable t) {
            Log.w(TAG, "sync mine background to external failed", t);
        }
    }

    private static File tmpFile(Context context) {
        return new File(context.getFilesDir(), TMP_NAME);
    }

    /** 共享副本 dst 同目录的临时文件：与 dst 同分区，保证 rename 原子。 */
    private static File tmpOf(File dst) {
        return new File(dst.getParentFile(), dst.getName() + TMP_SUFFIX);
    }

    /** 覆盖旧图前先移动到这里的备份：与 dest 同目录，替换失败时用于恢复。 */
    private static File backupFile(Context context) {
        return new File(context.getFilesDir(), FILE_NAME + ".bak");
    }

    /** 文件移动抽象：生产环境是 {@link File#renameTo}，单测可伪造失败。 */
    interface Mover {
        boolean move(File from, File to);
    }

    /**
     * 首次 rename 失败（个别 ROM 不允许覆盖已存在文件）后的兜底。
     *
     * <p>先把旧图移动到备份再重试，重试失败则把备份恢复回 dest：任何一步失败
     * 都不会删除用户原有背景（旧实现先 delete 再重试，重试失败即丢图）。
     *
     * <p>包私有 + {@link Mover} 便于纯 JVM 单测覆盖“重试仍失败”的分支。
     *
     * @return true 表示 dest 已是新图。
     */
    static boolean replaceExisting(File tmp, File dest, File backup, Mover mover) {
        boolean hadOld = dest.exists();
        if (hadOld && !mover.move(dest, backup)) {
            // 旧图原样留在 dest：本次导入失败，但用户背景不受影响。
            Log.w(TAG, "backup mine background failed");
            return false;
        }
        if (mover.move(tmp, dest)) {
            if (hadOld && backup.exists() && !backup.delete()) {
                Log.w(TAG, "delete mine background backup failed");
            }
            return true;
        }
        if (hadOld && !mover.move(backup, dest)) {
            // rename 恢复不可用时降级为拷贝，尽量把旧图放回目标路径。
            if (copyTo(backup, dest)) {
                if (backup.exists() && !backup.delete()) {
                    Log.w(TAG, "delete mine background backup failed");
                }
            } else {
                Log.w(TAG, "restore mine background failed");
            }
        }
        return false;
    }

    public static boolean hasImage(Context context) {
        try {
            File file = file(context);
            if (file.isFile() && file.length() > 0) {
                return true;
            }
            File external = externalFile(context);
            return external != null && external.isFile() && external.length() > 0;
        } catch (Throwable t) {
            Log.w(TAG, "check mine background failed", t);
            return false;
        }
    }

    /**
     * 同步导入图片，会做解码与压缩，禁止在主线程调用。
     *
     * @return 成功写入 mine_background.jpg 返回 true。
     */
    public static boolean importImage(Context context, Uri uri) {
        if (context == null || uri == null) {
            return false;
        }
        Context app = context.getApplicationContext();
        if (app == null) {
            app = context;
        }

        File tmp = tmpFile(app);
        Bitmap bitmap = null;
        try {
            bitmap = decode(app, uri);
            if (bitmap == null) {
                Log.w(TAG, "mine background decode failed");
                return false;
            }
            if (!writeJpeg(bitmap, tmp)) {
                return false;
            }
            File dest = file(app);
            if (!tmp.renameTo(dest)
                    && !replaceExisting(tmp, dest, backupFile(app), File::renameTo)) {
                Log.w(TAG, "rename mine background failed");
                return false;
            }
            File external = externalFile(app);
            if (external != null) {
                copy(dest, external);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "import mine background failed", t);
            return false;
        } finally {
            if (bitmap != null && !bitmap.isRecycled()) {
                try {
                    bitmap.recycle();
                } catch (Throwable ignored) {
                }
            }
            if (tmp.exists() && !tmp.delete()) {
                Log.w(TAG, "delete mine background tmp failed");
            }
        }
    }

    /**
     * 异步导入图片，解码/压缩在工作线程完成，回调切回主线程。
     */
    public static void importImageAsync(final Context context, final Uri uri, final Callback callback) {
        if (context == null || uri == null) {
            if (callback != null) {
                mainHandler().post(() -> callback.onResult(false));
            }
            return;
        }
        Context app = context.getApplicationContext();
        final Context ctx = app != null ? app : context;
        sExecutor.execute(() -> {
            boolean result;
            try {
                result = importImage(ctx, uri);
            } catch (Throwable t) {
                Log.w(TAG, "async import mine background failed", t);
                result = false;
            }
            final boolean ok = result;
            mainHandler().post(() -> notifyResult(callback, ok));
        });
    }

    private static void notifyResult(Callback callback, boolean ok) {
        if (callback == null) {
            return;
        }
        try {
            callback.onResult(ok);
        } catch (Throwable t) {
            Log.w(TAG, "mine background callback failed", t);
        }
    }

    /**
     * 删除已保存的背景与残留临时文件。
     *
     * <p>删除被投递到与导入共用的单线程队列执行，投递后立即返回。调用方
     * 需要“删除已完成”的确定结果时用 {@link #clear(Context, Callback)}：
     * 回调在主线程收到真实删除结果，之后再调用 {@link #hasImage} 不会读到旧文件。
     */
    public static void clear(Context context) {
        clear(context, null);
    }

    /**
     * 删除已保存的背景，并在主线程回调真实结果。
     *
     * <p>回调在队列出队并完成删除后才投递，因此回调里的刷新能读到删除后的状态；
     * 任一路径删除失败（内部图 / 共享副本）返回 false，调用方不能再提示“已清除”。
     */
    public static void clear(Context context, Callback callback) {
        if (context == null) {
            mainHandler().post(() -> notifyResult(callback, false));
            return;
        }
        Context app = context.getApplicationContext();
        final Context ctx = app != null ? app : context;
        try {
            sExecutor.execute(() -> {
                final boolean ok = clearSync(ctx);
                mainHandler().post(() -> notifyResult(callback, ok));
            });
        } catch (Throwable t) {
            Log.w(TAG, "enqueue clear mine background failed", t);
            mainHandler().post(() -> notifyResult(callback, false));
        }
    }

    /** @return true 表示内部图与共享副本都已删除（删除失败时目标进程仍可能读到旧图）。 */
    private static boolean clearSync(Context context) {
        try {
            File external = externalFile(context);
            File externalTmp = external != null ? tmpOf(external) : null;
            return deleteBackgroundFiles(file(context), tmpFile(context), external, externalTmp);
        } catch (Throwable t) {
            Log.w(TAG, "clear mine background failed", t);
            return false;
        }
    }

    /**
     * 逐个删除背景相关文件；任一存在但删不掉都返回 false。
     * 包私有便于单测用真实临时文件覆盖失败判定。
     */
    static boolean deleteBackgroundFiles(File internal, File tmp, File external, File externalTmp) {
        boolean ok = true;
        if (internal != null && internal.exists() && !internal.delete()) {
            Log.w(TAG, "delete mine background failed");
            ok = false;
        }
        if (tmp != null && tmp.exists() && !tmp.delete()) {
            Log.w(TAG, "delete mine background tmp failed");
            ok = false;
        }
        if (externalTmp != null && externalTmp.exists() && !externalTmp.delete()) {
            Log.w(TAG, "delete external mine background tmp failed");
            ok = false;
        }
        if (external != null && external.exists() && !external.delete()) {
            Log.w(TAG, "delete external mine background failed");
            ok = false;
        }
        return ok;
    }

    /**
     * 复制到共享目录：先写同目录临时文件再 rename 原子替换，与 internal 路径
     * 的 tmp+rename 同构，目标进程直读只会看到旧文件或完整新文件，不会读到
     * 半写的 JPEG；rename 失败时降级直写并告警，不因原子替换不可用而失败。
     */
    private static boolean copy(File src, File dst) {
        File tmp = tmpOf(dst);
        if (!copyTo(src, tmp)) {
            return false;
        }
        if (tmp.renameTo(dst)) {
            return true;
        }
        Log.w(TAG, "rename mine background external failed, fallback to direct copy");
        if (tmp.exists() && !tmp.delete()) {
            Log.w(TAG, "delete mine background external tmp failed");
        }
        return copyTo(src, dst);
    }

    private static boolean copyTo(File src, File dst) {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            out.flush();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "copy mine background failed", t);
            return false;
        }
    }

    private static Bitmap decode(Context context, Uri uri) {
        ContentResolver resolver = context.getContentResolver();
        Bitmap bitmap = decodeWithImageDecoder(resolver, uri);
        if (bitmap != null) {
            return bitmap;
        }
        return decodeWithBitmapFactory(resolver, uri);
    }

    /** API 28+：按原图尺寸在解码阶段直接缩放到不超过 1440x2560，保持比例。 */
    private static Bitmap decodeWithImageDecoder(ContentResolver resolver, Uri uri) {
        try {
            ImageDecoder.Source source = ImageDecoder.createSource(resolver, uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, src) -> {
                // 硬件 Bitmap 无法 compress()，强制软件分配。
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                if (width <= 0 || height <= 0) {
                    return;
                }
                float scale = Math.min(1f,
                        Math.min((float) MAX_WIDTH / width, (float) MAX_HEIGHT / height));
                int targetW = Math.max(1, Math.round(width * scale));
                int targetH = Math.max(1, Math.round(height * scale));
                decoder.setTargetSize(targetW, targetH);
            });
        } catch (Throwable t) {
            Log.w(TAG, "ImageDecoder failed, fallback to BitmapFactory", t);
            return null;
        }
    }

    /** 兜底：inJustDecodeBounds 读尺寸 + 标准 inSampleSize 下采样。 */
    private static Bitmap decodeWithBitmapFactory(ContentResolver resolver, Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = resolver.openInputStream(uri)) {
                if (in == null) {
                    Log.w(TAG, "open mine background source failed");
                    return null;
                }
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int width = bounds.outWidth;
            int height = bounds.outHeight;
            if (width <= 0 || height <= 0) {
                Log.w(TAG, "mine background source is not an image");
                return null;
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = SampleSize.calculateInSampleSize(
                    width, height, MAX_WIDTH, MAX_HEIGHT);
            Bitmap bitmap;
            try (InputStream in = resolver.openInputStream(uri)) {
                if (in == null) {
                    Log.w(TAG, "open mine background source failed");
                    return null;
                }
                bitmap = BitmapFactory.decodeStream(in, null, options);
            }
            if (bitmap == null) {
                Log.w(TAG, "BitmapFactory decode failed");
                return null;
            }
            return scaleToFit(bitmap);
        } catch (Throwable t) {
            Log.w(TAG, "BitmapFactory fallback failed", t);
            return null;
        }
    }

    /** 兜底解码后仍可能超过上限（标准 inSampleSize 只保证不小于请求尺寸）。 */
    private static Bitmap scaleToFit(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        if (width <= MAX_WIDTH && height <= MAX_HEIGHT) {
            return source;
        }
        try {
            float scale = Math.min((float) MAX_WIDTH / width, (float) MAX_HEIGHT / height);
            int targetW = Math.max(1, Math.round(width * scale));
            int targetH = Math.max(1, Math.round(height * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(source, targetW, targetH, true);
            if (scaled != source) {
                source.recycle();
            }
            return scaled;
        } catch (Throwable t) {
            Log.w(TAG, "scale mine background failed", t);
            return source;
        }
    }

    private static boolean writeJpeg(Bitmap bitmap, File tmp) {
        OutputStream out = null;
        try {
            out = new FileOutputStream(tmp);
            boolean ok = bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
            out.flush();
            if (!ok) {
                Log.w(TAG, "compress mine background failed");
            }
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "write mine background failed", t);
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
