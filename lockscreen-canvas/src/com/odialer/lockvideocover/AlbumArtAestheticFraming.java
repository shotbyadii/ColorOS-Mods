package com.odialer.lockvideocover;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Aesthetic Lockscreen Album Art Framing Engine.
 * Matches ColorOS 17 State 2 visual language:
 * - Razor-sharp uncropped album art (FIT_CENTER)
 * - Seamless top & bottom ambient gradient padding
 * - Dynamic 1400x1400 high-resolution art fetching
 */
public class AlbumArtAestheticFraming {
    private static final String TAG = "AlbumArtFraming";

    /**
     * Formats album art into a full-screen lockscreen wallpaper
     * with ColorOS 17 style ambient blurred background on top and bottom.
     */
    public static Bitmap formatForLockscreen(Context context, Bitmap srcArt, int screenWidth, int screenHeight) {
        if (srcArt == null || srcArt.isRecycled() || screenWidth <= 0 || screenHeight <= 0) {
            return null;
        }

        try {
            Bitmap result = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(result);

            // 1. Full-screen Ambient Blurred Backdrop (Matches ColorOS State 2 Player)
            Bitmap blurredBg = generateAmbientBlur(srcArt, screenWidth, screenHeight);
            if (blurredBg != null) {
                Paint bgPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
                canvas.drawBitmap(blurredBg, 0, 0, bgPaint);
                blurredBg.recycle();
            } else {
                // Fallback vertical gradient if blur fails
                int topColor = sampleStripColor(srcArt, true);
                int botColor = sampleStripColor(srcArt, false);
                Paint bgPaint = new Paint();
                Shader bgShader = new LinearGradient(
                        0, 0, 0, screenHeight,
                        topColor, botColor,
                        Shader.TileMode.CLAMP
                );
                bgPaint.setShader(bgShader);
                canvas.drawRect(0, 0, screenWidth, screenHeight, bgPaint);
            }

            // 2. Uncropped aspect-fit framing centered in the unobstructed lockscreen viewing zone
            // Prevents clock and widgets (~500px to ~1060px) from obstructing the album artwork
            int topClearance = Math.round(screenHeight * 0.395f); // ~1090px on 2760h (below widgets)
            int bottomClearance = Math.round(screenHeight * 0.840f); // ~2320px on 2760h (above quality info/pill)
            int maxZoneH = bottomClearance - topClearance;
            int maxZoneW = Math.round(screenWidth * 0.94f);

            int artW = maxZoneW;
            int artH = Math.round((float) artW * ((float) srcArt.getHeight() / (float) srcArt.getWidth()));
            if (artH > maxZoneH) {
                artH = maxZoneH;
                artW = Math.round((float) artH * ((float) srcArt.getWidth() / (float) srcArt.getHeight()));
            }

            int artX = (screenWidth - artW) / 2;
            int artY = topClearance + (maxZoneH - artH) / 2;

            // 3. Create a feathered version of the album art so it blends invisibly into the blur
            Bitmap featheredArt = createFeatheredArt(srcArt, artW, artH);
            if (featheredArt != null) {
                canvas.drawBitmap(featheredArt, artX, artY, null);
                featheredArt.recycle();
            } else {
                Rect srcRect = new Rect(0, 0, srcArt.getWidth(), srcArt.getHeight());
                Rect dstRect = new Rect(artX, artY, artX + artW, artY + artH);
                Paint artPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
                canvas.drawBitmap(srcArt, srcRect, dstRect, artPaint);
            }

            // 4. ColorOS State 2 aesthetic vignette for status bar and clock legibility with gradual roll-off
            Paint vignettePaint = new Paint(Paint.DITHER_FLAG);
            int topVignetteH = Math.round(screenHeight * 0.35f);
            int[] topVignetteColors = {
                    Color.argb(125, 0, 0, 0),
                    Color.argb(90, 0, 0, 0),
                    Color.argb(55, 0, 0, 0),
                    Color.argb(22, 0, 0, 0),
                    Color.argb(6, 0, 0, 0),
                    Color.TRANSPARENT
            };
            float[] topVignetteStops = { 0.0f, 0.25f, 0.50f, 0.72f, 0.88f, 1.0f };
            Shader topShader = new LinearGradient(
                    0, 0, 0, topVignetteH,
                    topVignetteColors, topVignetteStops,
                    Shader.TileMode.CLAMP
            );
            vignettePaint.setShader(topShader);
            canvas.drawRect(0, 0, screenWidth, topVignetteH, vignettePaint);

            int botVignetteH = Math.round(screenHeight * 0.40f);
            int[] botVignetteColors = {
                    Color.TRANSPARENT,
                    Color.argb(8, 0, 0, 0),
                    Color.argb(28, 0, 0, 0),
                    Color.argb(65, 0, 0, 0),
                    Color.argb(105, 0, 0, 0),
                    Color.argb(140, 0, 0, 0)
            };
            float[] botVignetteStops = { 0.0f, 0.20f, 0.45f, 0.70f, 0.88f, 1.0f };
            Shader botShader = new LinearGradient(
                    0, screenHeight - botVignetteH, 0, screenHeight,
                    botVignetteColors, botVignetteStops,
                    Shader.TileMode.CLAMP
            );
            vignettePaint.setShader(botShader);
            canvas.drawRect(0, screenHeight - botVignetteH, screenWidth, screenHeight, vignettePaint);

            Log.d(TAG, "Formatted aesthetic lockscreen art with ambient blur: " + screenWidth + "x" + screenHeight);
            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error formatting album art: " + e.getMessage(), e);
            return null;
        }
    }

    private static Bitmap createFeatheredArt(Bitmap src, int targetW, int targetH) {
        try {
            Bitmap scaled = Bitmap.createScaledBitmap(src, targetW, targetH, true);
            Bitmap feathered = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(feathered);

            // Draw original scaled art
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
            c.drawBitmap(scaled, 0, 0, p);
            if (scaled != src) {
                scaled.recycle();
            }

            // Create alpha mask using DST_IN PorterDuff mode
            Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));

            Bitmap mask = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
            Canvas mc = new Canvas(mask);
            mc.drawColor(Color.WHITE); // Solid interior

            // Ultra-gradual top and bottom feathering (35% top, 40% bottom) with smooth multi-stop cubic progression
            int topFade = Math.round(targetH * 0.35f);
            int botFade = Math.round(targetH * 0.40f);

            Paint gradPaint = new Paint(Paint.DITHER_FLAG);
            gradPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));

            // Multi-stop gradual top fade into transparent
            int[] topFadeColors = {
                    Color.argb(0, 255, 255, 255),
                    Color.argb(20, 255, 255, 255),
                    Color.argb(65, 255, 255, 255),
                    Color.argb(135, 255, 255, 255),
                    Color.argb(210, 255, 255, 255),
                    Color.WHITE
            };
            float[] topFadePositions = { 0.0f, 0.20f, 0.45f, 0.70f, 0.88f, 1.0f };
            Shader topShader = new LinearGradient(
                    0, 0, 0, topFade,
                    topFadeColors, topFadePositions,
                    Shader.TileMode.CLAMP
            );
            gradPaint.setShader(topShader);
            mc.drawRect(0, 0, targetW, topFade, gradPaint);

            // Multi-stop gradual bottom fade into transparent
            int[] botFadeColors = {
                    Color.WHITE,
                    Color.argb(210, 255, 255, 255),
                    Color.argb(135, 255, 255, 255),
                    Color.argb(65, 255, 255, 255),
                    Color.argb(20, 255, 255, 255),
                    Color.argb(0, 255, 255, 255)
            };
            float[] botFadePositions = { 0.0f, 0.12f, 0.30f, 0.55f, 0.80f, 1.0f };
            Shader botShader = new LinearGradient(
                    0, targetH - botFade, 0, targetH,
                    botFadeColors, botFadePositions,
                    Shader.TileMode.CLAMP
            );
            gradPaint.setShader(botShader);
            mc.drawRect(0, targetH - botFade, targetW, targetH, gradPaint);

            // Subtle side feathering (left & right) using DST_IN so corners blend gracefully
            int sideFade = Math.round(targetW * 0.10f);
            Paint sidePaint = new Paint(Paint.DITHER_FLAG);
            sidePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));

            Shader leftShader = new LinearGradient(
                    0, 0, sideFade, 0,
                    Color.TRANSPARENT, Color.WHITE,
                    Shader.TileMode.CLAMP
            );
            sidePaint.setShader(leftShader);
            mc.drawRect(0, 0, sideFade, targetH, sidePaint);

            Shader rightShader = new LinearGradient(
                    targetW - sideFade, 0, targetW, 0,
                    Color.WHITE, Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
            );
            sidePaint.setShader(rightShader);
            mc.drawRect(targetW - sideFade, 0, targetW, targetH, sidePaint);

            // Apply mask to feathered bitmap
            c.drawBitmap(mask, 0, 0, maskPaint);
            mask.recycle();

            return feathered;
        } catch (Throwable t) {
            Log.w(TAG, "createFeatheredArt failed: " + t.getMessage());
            return null;
        }
    }

    private static Bitmap generateAmbientBlur(Bitmap src, int targetW, int targetH) {
        try {
            int downW = 100;
            int downH = Math.max(1, Math.round((float) downW * ((float) targetH / (float) targetW)));
            Bitmap downscaled = Bitmap.createScaledBitmap(src, downW, downH, true);
            Bitmap blurred = fastBlur(downscaled, 22);
            if (blurred != downscaled) {
                downscaled.recycle();
            }

            Bitmap fullBlur = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(fullBlur);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            Rect srcR = new Rect(0, 0, blurred.getWidth(), blurred.getHeight());
            Rect dstR = new Rect(0, 0, targetW, targetH);
            c.drawBitmap(blurred, srcR, dstR, p);
            blurred.recycle();

            return fullBlur;
        } catch (Throwable t) {
            Log.w(TAG, "generateAmbientBlur failed: " + t.getMessage());
            return null;
        }
    }

    private static Bitmap fastBlur(Bitmap sentBitmap, int radius) {
        Bitmap bitmap = sentBitmap.copy(Bitmap.Config.ARGB_8888, true);
        if (radius < 1) return bitmap;

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        int[] pix = new int[w * h];
        bitmap.getPixels(pix, 0, w, 0, 0, w, h);

        int wm = w - 1;
        int hm = h - 1;
        int wh = w * h;
        int div = radius + radius + 1;

        int[] r = new int[wh];
        int[] g = new int[wh];
        int[] b = new int[wh];
        int rsum, gsum, bsum, x, y, i, p, yp, yi, yw;
        int[] vmin = new int[Math.max(w, h)];

        int divsum = (div + 1) >> 1;
        divsum *= divsum;
        int[] dv = new int[256 * divsum];
        for (i = 0; i < 256 * divsum; i++) {
            dv[i] = (i / divsum);
        }

        yw = yi = 0;
        int[][] stack = new int[div][3];
        int stackpointer;
        int stackstart;
        int[] sir;
        int rbs;
        int r1 = radius + 1;
        int routsum, goutsum, boutsum;
        int rinsum, ginsum, binsum;

        for (y = 0; y < h; y++) {
            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
            for (i = -radius; i <= radius; i++) {
                p = pix[yi + Math.min(wm, Math.max(i, 0))];
                sir = stack[i + radius];
                sir[0] = (p & 0xff0000) >> 16;
                sir[1] = (p & 0x00ff00) >> 8;
                sir[2] = (p & 0x0000ff);
                rbs = r1 - Math.abs(i);
                rsum += sir[0] * rbs;
                gsum += sir[1] * rbs;
                bsum += sir[2] * rbs;
                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                }
            }
            stackpointer = radius;

            for (x = 0; x < w; x++) {
                r[yi] = dv[rsum];
                g[yi] = dv[gsum];
                b[yi] = dv[bsum];

                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;

                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];

                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];

                if (y == 0) {
                    vmin[x] = Math.min(x + radius + 1, wm);
                }
                p = pix[yw + vmin[x]];

                sir[0] = (p & 0xff0000) >> 16;
                sir[1] = (p & 0x00ff00) >> 8;
                sir[2] = (p & 0x0000ff);

                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];

                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[(stackpointer) % div];

                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];

                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];

                yi++;
            }
            yw += w;
        }

        for (x = 0; x < w; x++) {
            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
            yp = -radius * w;
            for (i = -radius; i <= radius; i++) {
                yi = Math.max(0, yp) + x;

                sir = stack[i + radius];

                sir[0] = r[yi];
                sir[1] = g[yi];
                sir[2] = b[yi];

                rbs = r1 - Math.abs(i);

                rsum += r[yi] * rbs;
                gsum += g[yi] * rbs;
                bsum += b[yi] * rbs;

                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                }

                if (i < hm) {
                    yp += w;
                }
            }
            yi = x;
            stackpointer = radius;
            for (y = 0; y < h; y++) {
                pix[yi] = (0xff000000 & pix[yi]) | (dv[rsum] << 16) | (dv[gsum] << 8) | dv[bsum];

                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;

                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];

                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];

                if (x == 0) {
                    vmin[y] = Math.min(y + r1, hm) * w;
                }
                p = x + vmin[y];

                sir[0] = r[p];
                sir[1] = g[p];
                sir[2] = b[p];

                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];

                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer % div];

                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];

                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];

                yi += w;
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h);
        return bitmap;
    }

    private static int sampleStripColor(Bitmap bmp, boolean isTop) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int startY = isTop ? 0 : Math.max(0, h - 20);
        int endY = isTop ? Math.min(h, 20) : h;

        long rSum = 0, gSum = 0, bSum = 0;
        int count = 0;

        int stepX = Math.max(1, w / 40);
        int stepY = Math.max(1, (endY - startY) / 5);

        for (int y = startY; y < endY; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int c = bmp.getPixel(x, y);
                rSum += Color.red(c);
                gSum += Color.green(c);
                bSum += Color.blue(c);
                count++;
            }
        }

        if (count == 0) return Color.DKGRAY;
        return Color.rgb((int) (rSum / count), (int) (gSum / count), (int) (bSum / count));
    }

    /**
     * Resolves high-resolution album art (up to 1400x1400) from iTunes / HTTP.
     */
    public static Bitmap fetchHighResArtFromWeb(String title, String artist) {
        if (title == null || title.trim().isEmpty()) return null;
        try {
            String query = (title + " " + (artist != null ? artist : "")).trim();
            String urlStr = "https://itunes.apple.com/search?term=" + URLEncoder.encode(query, "UTF-8") + "&entity=song&limit=1";
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)");

            if (conn.getResponseCode() == 200) {
                InputStream is = conn.getInputStream();
                byte[] buf = new byte[32768];
                StringBuilder sb = new StringBuilder();
                int len;
                while ((len = is.read(buf)) != -1) {
                    sb.append(new String(buf, 0, len));
                }
                is.close();

                JSONObject root = new JSONObject(sb.toString());
                JSONArray results = root.optJSONArray("results");
                if (results != null && results.length() > 0) {
                    JSONObject first = results.getJSONObject(0);
                    String art100 = first.optString("artworkUrl100");
                    if (art100 != null && !art100.isEmpty()) {
                        String highResUrl = art100.replace("100x100bb", "1400x1400bb");
                        Log.d(TAG, "Fetching 1400x1400 art: " + highResUrl);
                        return downloadBitmap(highResUrl);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not fetch high-res art: " + e.getMessage());
        }
        return null;
    }

    public static Bitmap downloadBitmap(String urlStr) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android; Mobile)");
            if (conn.getResponseCode() == 200) {
                InputStream is = conn.getInputStream();
                Bitmap bmp = BitmapFactory.decodeStream(is);
                is.close();
                return bmp;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to download bitmap: " + e.getMessage());
        }
        return null;
    }
}
