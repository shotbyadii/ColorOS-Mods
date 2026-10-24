package com.odialer.lockvideocover;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class WallpaperBackupManager {
    private static final String TAG = "WallpaperBackup";
    private static final String IMAGE_FILE_NAME = "original_lock_wallpaper.png";
    private static final String VIDEO_FILE_NAME = "idle_wallpaper_video.mp4";
    private static final String PREF_NAME = "wallpaper_prefs";
    private static final String KEY_IDLE_MODE = "idle_mode"; // "video" or "image"

    public static File getImageFile(Context context) {
        return new File(context.getFilesDir(), IMAGE_FILE_NAME);
    }

    public static File getVideoFile(Context context) {
        return new File(context.getFilesDir(), VIDEO_FILE_NAME);
    }

    public static boolean isIdleVideoMode(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_IDLE_MODE, "video").equals("video");
    }

    public static void setIdleMode(Context context, String mode) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_IDLE_MODE, mode).apply();
    }

    public static boolean hasCustomImage(Context context) {
        File file = getImageFile(context);
        return file.exists() && file.length() > 5000;
    }

    public static boolean hasCustomVideo(Context context) {
        File file = getVideoFile(context);
        return file.exists() && file.length() > 10000;
    }

    public static boolean saveCustomImage(Context context, InputStream inputStream) {
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bitmap = BitmapFactory.decodeStream(inputStream, null, opts);
            if (bitmap == null) return false;

            int maxDim = 3200;
            if (bitmap.getWidth() > maxDim || bitmap.getHeight() > maxDim) {
                float scale = Math.min((float) maxDim / bitmap.getWidth(), (float) maxDim / bitmap.getHeight());
                int newW = Math.round(bitmap.getWidth() * scale);
                int newH = Math.round(bitmap.getHeight() * scale);
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true);
                if (scaled != bitmap) {
                    bitmap.recycle();
                    bitmap = scaled;
                }
            }

            File target = getImageFile(context);
            try (FileOutputStream fos = new FileOutputStream(target)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 95, fos);
            }
            bitmap.recycle();
            setIdleMode(context, "image");
            Log.d(TAG, "Custom image wallpaper saved: " + target.length() + " bytes");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error saving custom image: " + e.getMessage(), e);
            return false;
        }
    }

    public static boolean saveCustomVideo(Context context, InputStream inputStream) {
        try {
            File target = getVideoFile(context);
            try (FileOutputStream fos = new FileOutputStream(target)) {
                byte[] buf = new byte[32768];
                int len;
                while ((len = inputStream.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                }
            }
            setIdleMode(context, "video");
            Log.d(TAG, "Custom video wallpaper saved: " + target.length() + " bytes");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error saving custom video: " + e.getMessage(), e);
            return false;
        }
    }

    public static void resetToStockVideo(Context context) {
        File customVideo = getVideoFile(context);
        if (customVideo.exists()) {
            customVideo.delete();
        }
        setIdleMode(context, "video");
        ensureStockVideoExtracted(context);
    }

    public static String getEffectiveIdleVideoPath(Context context) {
        File customVideo = getVideoFile(context);
        if (customVideo.exists() && customVideo.length() > 10000) {
            return customVideo.getAbsolutePath();
        }
        File stockVideo = ensureStockVideoExtracted(context);
        return (stockVideo != null) ? stockVideo.getAbsolutePath() : null;
    }

    public static File ensureStockVideoExtracted(Context context) {
        File stockFile = new File(context.getFilesDir(), "stock_oppo_find_x9_live.mp4");
        if (!stockFile.exists() || stockFile.length() < 10000) {
            try (InputStream is = context.getResources().openRawResource(R.raw.default_idle_wallpaper);
                 FileOutputStream fos = new FileOutputStream(stockFile)) {
                byte[] buf = new byte[32768];
                int len;
                while ((len = is.read(buf)) != -1) {
                    fos.write(buf, 0, len);
                }
                Log.d(TAG, "Extracted stock Oppo Find X9 live wallpaper: " + stockFile.length() + " bytes");
            } catch (Exception e) {
                Log.e(TAG, "Error extracting stock video: " + e.getMessage(), e);
            }
        }
        return stockFile;
    }

    public static Bitmap loadBackupBitmap(Context context) {
        File file = getImageFile(context);
        if (file.exists() && file.length() > 5000) {
            try {
                Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
                if (bmp != null) return bmp;
            } catch (Exception e) {
                Log.w(TAG, "Error loading custom wallpaper: " + e.getMessage());
            }
        }

        // Return the first/poster frame of the idle video (Oppo Find X9 live wallpaper)
        return getIdleVideoPoster(context);
    }

    public static Bitmap getIdleVideoPoster(Context context) {
        File posterFile = new File(context.getFilesDir(), "idle_video_poster.jpg");
        if (posterFile.exists() && posterFile.length() > 1000) {
            try {
                Bitmap bmp = BitmapFactory.decodeFile(posterFile.getAbsolutePath());
                if (bmp != null) return bmp;
            } catch (Exception ignored) {}
        }

        String videoPath = getEffectiveIdleVideoPath(context);
        if (videoPath != null && new File(videoPath).exists()) {
            try {
                android.media.MediaMetadataRetriever mmr = new android.media.MediaMetadataRetriever();
                mmr.setDataSource(videoPath);
                Bitmap poster = mmr.getFrameAtTime(500000);
                mmr.release();
                if (poster != null) {
                    try (FileOutputStream fos = new FileOutputStream(posterFile)) {
                        poster.compress(Bitmap.CompressFormat.JPEG, 90, fos);
                    } catch (Exception ignored) {}
                    return poster;
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not extract idle video poster: " + e.getMessage());
            }
        }
        return null;
    }
}
