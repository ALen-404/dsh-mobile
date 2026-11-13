package com.dshmobile;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * 从系统文件选择器返回的 {@link Uri} 里读出文件名、MIME 和字节。
 *
 * <p>为什么单独一个类：这段代码要走 {@code ContentResolver}（Android 专有），
 * 放在 Activity 里会让那个已经很大的文件继续膨胀，而且它只有"读一个 URI"这一个职责。
 *
 * <p>注意：必须在**后台线程**调用（会做 IO）。
 */
final class PickedFile {

    final String name;
    final String mimeType;
    final byte[] bytes;

    private PickedFile(String name, String mimeType, byte[] bytes) {
        this.name = name;
        this.mimeType = mimeType;
        this.bytes = bytes;
    }

    boolean isImage() {
        return mimeType != null && mimeType.startsWith("image/");
    }

    /** 上传/内联都只接受这几种图片格式（服务端会按字节校验）。 */
    boolean isSupportedImage() {
        return "image/png".equals(mimeType) || "image/jpeg".equals(mimeType)
                || "image/webp".equals(mimeType) || "image/gif".equals(mimeType);
    }

    /**
     * 读取一个 URI。
     *
     * @param maxBytes 超过此大小抛异常（避免把几百 MB 的东西读进内存）
     */
    static PickedFile read(Context context, Uri uri, long maxBytes) throws Exception {
        String name = "file";
        long declaredSize = -1;
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) {
                    String n = cursor.getString(nameIndex);
                    if (n != null && !n.isEmpty()) {
                        name = n;
                    }
                }
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    declaredSize = cursor.getLong(sizeIndex);
                }
            }
        } catch (Exception ignored) {
            // 有些 provider 不支持 query，退回默认名
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
        if (declaredSize > maxBytes) {
            throw new IllegalStateException("文件太大（" + (declaredSize / 1024 / 1024) + " MB）");
        }

        String mime = context.getContentResolver().getType(uri);
        InputStream in = null;
        try {
            in = context.getContentResolver().openInputStream(uri);
            if (in == null) {
                throw new IllegalStateException("无法读取该文件");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > maxBytes) {
                    throw new IllegalStateException("文件太大（超过 " + (maxBytes / 1024 / 1024) + " MB）");
                }
                out.write(buffer, 0, n);
            }
            return new PickedFile(name, mime, out.toByteArray());
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }
}
