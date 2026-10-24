package com.odialer.lockvideocover;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class CanvasCacheManager {
    private static final String TAG = "CanvasCacheManager";
    private static final String DIR_NAME = "canvas_videos";

    public static File getCacheDir(Context context) {
        File dir = new File(context.getCacheDir(), DIR_NAME);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File getCachedFileForTrack(Context context, String trackKey) {
        String safeName = sanitizeFilename(trackKey) + ".cnvs.mp4";
        File file = new File(getCacheDir(context), safeName);
        if (file.exists() && file.length() > 5000) {
            return file;
        }
        return null;
    }

    public static File downloadAndCache(Context context, String videoUrl, String trackKey) {
        try {
            File cached = getCachedFileForTrack(context, trackKey);
            if (cached != null) {
                Log.d(TAG, "Cache hit for track: " + trackKey + " -> " + cached.getAbsolutePath());
                return cached;
            }

            Log.d(TAG, "Downloading canvas from: " + videoUrl);
            URL url = new URL(videoUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.connect();

            if (conn.getResponseCode() != 200) {
                Log.w(TAG, "Download failed with HTTP " + conn.getResponseCode());
                return null;
            }

            File targetFile = new File(getCacheDir(context), sanitizeFilename(trackKey) + ".cnvs.mp4");
            File tempFile = new File(getCacheDir(context), sanitizeFilename(trackKey) + ".tmp");

            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }

            if (tempFile.renameTo(targetFile)) {
                Log.d(TAG, "Downloaded and saved canvas: " + targetFile.length() + " bytes");
                return targetFile;
            } else {
                return tempFile;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error downloading canvas video: " + e.getMessage(), e);
            return null;
        }
    }

    private static String sanitizeFilename(String input) {
        return input.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
