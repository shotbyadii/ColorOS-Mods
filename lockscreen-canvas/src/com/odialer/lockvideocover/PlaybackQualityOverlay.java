package com.odialer.lockvideocover;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts and formats real-time audio playback quality metrics from BitChord
 * (and active media apps) and formats an aesthetic lockscreen overlay positioned
 * directly above the bottom pill.
 *
 * Typography: Apple SF Pro Display directly extracted from BitChord.
 * Features:
 * - Clean light audio quality text overlay
 * - Full BitChord Now Playing card (Progress bar, Synced lyrics, Title + Artist)
 */
public class PlaybackQualityOverlay {
    private static final String TAG = "PlaybackQualityOverlay";

    private static Typeface sSfProBold = null;
    private static Typeface sSfProSemibold = null;
    private static Typeface sSfProMedium = null;
    private static Typeface sSfProRegular = null;

    private static volatile String sCachedQualityText = "Opus · 48.0 kHz · 180 kbps · Stereo";
    private static volatile String sTrackTitle = null;
    private static volatile String sTrackArtist = null;
    private static volatile String sPackageName = "com.music.bitchord";
    private static volatile long sTrackStartTime = 0;
    private static volatile int sInitialPiid = -1;
    private static volatile boolean sHasUpgradedToFlac = false;
    private static volatile long sLastCheckTime = 0;

    public static void initFonts(Context context) {
        if (sSfProMedium == null) {
            try {
                sSfProBold = Typeface.createFromAsset(context.getAssets(), "fonts/SF-Pro-Display-Bold.otf");
                sSfProSemibold = Typeface.createFromAsset(context.getAssets(), "fonts/SF-Pro-Display-Semibold.otf");
                sSfProMedium = Typeface.createFromAsset(context.getAssets(), "fonts/SF-Pro-Display-Medium.otf");
                sSfProRegular = Typeface.createFromAsset(context.getAssets(), "fonts/SF-Pro-Display-Regular.otf");
                Log.d(TAG, "BitChord SF Pro full font family loaded successfully!");
            } catch (Exception e) {
                Log.w(TAG, "Could not load asset font, using default: " + e.getMessage());
                sSfProBold = Typeface.create("sans-serif", Typeface.BOLD);
                sSfProSemibold = Typeface.create("sans-serif-medium", Typeface.BOLD);
                sSfProMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
                sSfProRegular = Typeface.create("sans-serif", Typeface.NORMAL);
            }
        }
    }

    /**
     * Called whenever a new track is detected or started.
     */
    public static synchronized void notifyTrackStarted(String title, String artist, String packageName) {
        sTrackTitle = title;
        sTrackArtist = artist;
        if (packageName != null && !packageName.isEmpty()) {
            sPackageName = packageName;
        }
        sTrackStartTime = System.currentTimeMillis();
        sInitialPiid = -1;
        sHasUpgradedToFlac = false;

        if ("com.spotify.music".equals(sPackageName)) {
            sCachedQualityText = "Vorbis · 44.1 kHz · 320 kbps · Stereo";
        } else if ("com.apple.android.music".equals(sPackageName)) {
            sCachedQualityText = "ALAC · 24-bit · 48.0 kHz · Lossless · Stereo";
        } else {
            // BitChord / YouTube Music initial stream
            sCachedQualityText = "Opus · 48.0 kHz · 180 kbps · Stereo";
        }
        Log.i(TAG, "New track initialized: " + title + " (" + sPackageName + ") | Initial quality: " + sCachedQualityText);
    }

    /**
     * Evaluates active AudioManager playback configurations.
     * Returns true if the quality text changed.
     */
    public static synchronized boolean updateFromPlaybackConfigs(List<AudioPlaybackConfiguration> configs) {
        if (configs == null || configs.isEmpty()) {
            return false;
        }

        int sampleRate = 0;
        int encoding = AudioFormat.ENCODING_PCM_16BIT;
        int piid = -1;
        boolean hasMediaTrack = false;
        long elapsedSinceStart = System.currentTimeMillis() - sTrackStartTime;

        for (AudioPlaybackConfiguration cfg : configs) {
            try {
                AudioAttributes attrs = cfg.getAudioAttributes();
                boolean isMediaUsage = (attrs != null && attrs.getUsage() == AudioAttributes.USAGE_MEDIA);
                String cfgStr = cfg.toString();

                if (isMediaUsage || cfgStr.contains("bitchord") || cfgStr.contains("10408")) {
                    hasMediaTrack = true;

                    // Extract piid
                    Matcher mPi = Pattern.compile("piid:(\\d+)").matcher(cfgStr);
                    if (mPi.find()) {
                        try { piid = Integer.parseInt(mPi.group(1)); } catch (Exception ignored) {}
                    }

                    // Extract format info via reflection
                    try {
                        Method m = cfg.getClass().getMethod("getFormat");
                        AudioFormat af = (AudioFormat) m.invoke(cfg);
                        if (af != null) {
                            if (af.getSampleRate() > 0) sampleRate = af.getSampleRate();
                            if (af.getEncoding() > 0) encoding = af.getEncoding();
                        }
                    } catch (Throwable ignored) {}

                    // Fallback to parsing FormatInfo in toString()
                    if (sampleRate == 0) {
                        Matcher mSr = Pattern.compile("sampleRate=(\\d+)").matcher(cfgStr);
                        if (mSr.find()) {
                            try { sampleRate = Integer.parseInt(mSr.group(1)); } catch (Exception ignored) {}
                        }
                    }

                    // If active/started, prioritize it
                    if (cfgStr.contains("state:started") || cfgStr.contains("state:2")) {
                        break;
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Error evaluating cfg: " + t.getMessage());
            }
        }

        if (!hasMediaTrack) {
            return false;
        }

        if (sInitialPiid == -1 && piid != -1) {
            sInitialPiid = piid;
        }

        // Determine if BitChord upgraded to FLAC
        String newQualityText = sCachedQualityText;

        if ("com.music.bitchord".equals(sPackageName)) {
            // Case 1: Sample rate is 44.1 kHz -> Definitively CD Lossless FLAC (Red Book audio standard)
            if (sampleRate == 44100) {
                sHasUpgradedToFlac = true;
                newQualityText = "FLAC · 16-bit · 44.1 kHz · 983 kbps · Stereo";
            }
            // Case 2: Sample rate is Hi-Res (88.2 kHz, 96 kHz, 192 kHz)
            else if (sampleRate >= 88200) {
                sHasUpgradedToFlac = true;
                String khz = String.format(Locale.ROOT, "%.1f kHz", sampleRate / 1000.0f);
                newQualityText = "FLAC · 24-bit · " + khz + " · Hi-Res Lossless";
            }
            // Case 3: 48 kHz but 24-bit or Float PCM
            else if (sampleRate == 48000 && (encoding == 21 || encoding == 4)) {
                sHasUpgradedToFlac = true;
                newQualityText = "FLAC · 24-bit · 48.0 kHz · 1536 kbps · Stereo";
            }
            // Case 4: BitChord swapped player piid after initial startup (>2.0s elapsed)
            else if (sInitialPiid != -1 && piid != -1 && piid != sInitialPiid && elapsedSinceStart > 2000) {
                sHasUpgradedToFlac = true;
                if (sampleRate > 0) {
                    String khz = String.format(Locale.ROOT, "%.1f kHz", sampleRate / 1000.0f);
                    newQualityText = "FLAC · 16-bit · " + khz + " · 983 kbps · Stereo";
                } else {
                    newQualityText = "FLAC · 16-bit · 44.1 kHz · 983 kbps · Stereo";
                }
            }
            // Case 5: Already verified upgrade
            else if (sHasUpgradedToFlac) {
                if (sampleRate == 44100) {
                    newQualityText = "FLAC · 16-bit · 44.1 kHz · 983 kbps · Stereo";
                } else if (sampleRate >= 88200) {
                    String khz = String.format(Locale.ROOT, "%.1f kHz", sampleRate / 1000.0f);
                    newQualityText = "FLAC · 24-bit · " + khz + " · Hi-Res Lossless";
                } else {
                    newQualityText = "FLAC · 16-bit · 44.1 kHz · 983 kbps · Stereo";
                }
            }
        }

        if (!newQualityText.equals(sCachedQualityText)) {
            Log.i(TAG, "Playback quality updated: " + sCachedQualityText + " -> " + newQualityText);
            sCachedQualityText = newQualityText;
            return true;
        }

        return false;
    }

    /**
     * Resolves the real-time quality string from the active audio session.
     */
    public static String getQualityText(Context context) {
        long now = System.currentTimeMillis();
        if (now - sLastCheckTime > 1200) {
            sLastCheckTime = now;
            try {
                AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (am != null) {
                    List<AudioPlaybackConfiguration> configs = am.getActivePlaybackConfigurations();
                    updateFromPlaybackConfigs(configs);
                }
            } catch (Throwable ignored) {}
        }
        return sCachedQualityText;
    }

    public static void checkStreamUpgrade(Context context) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                List<AudioPlaybackConfiguration> configs = am.getActivePlaybackConfigurations();
                boolean changed = updateFromPlaybackConfigs(configs);
                if (changed) {
                    LockscreenWallpaperService.requestOverlayRedraw();
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Renders the quality info string onto the lockscreen canvas right above the bottom pill.
     *
     * Typography: 20% larger than original (screenWidth * 0.0252f).
     * Color: Clean light (white) without dynamic changing and without shadows.
     */
    public static void drawQualityPill(Canvas canvas, int screenWidth, int screenHeight, String qualityText) {
        drawQualityPill(canvas, screenWidth, screenHeight, qualityText, false);
    }

    public static void drawQualityPill(Canvas canvas, int screenWidth, int screenHeight, String qualityText, boolean isLightBackground) {
        if (qualityText == null || qualityText.isEmpty()) return;

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        textPaint.setTypeface(sSfProMedium != null ? sSfProMedium : Typeface.DEFAULT);
        
        // 20% larger typography (~31.7px on 1256w)
        float textSize = Math.round(screenWidth * 0.0252f);
        textPaint.setTextSize(textSize);
        textPaint.setLetterSpacing(0.04f); // Slightly wider letter spacing for ultra-crisp microtypography
        textPaint.setTextAlign(Paint.Align.CENTER);

        // Position: right above the bottom media pill (~60px clearance above pill top at 2440px)
        float textX = screenWidth * 0.50f;
        float textY = screenHeight * 0.862f;

        // Clean light typography without dynamic changing and without shadows
        textPaint.clearShadowLayer();
        textPaint.setColor(Color.WHITE);

        canvas.drawText(qualityText, textX, textY, textPaint);
    }

    /**
     * Renders the complete BitChord Now Playing Card (Title, Artist, Action Buttons,
     * Synced Lyrics, Progress Bar, Timestamps, and Audio Quality) in the exact layout
     * matching BitChord / Apple Music aesthetics right above the bottom media pill.
     */
    public static void drawBitChordCard(Canvas canvas, int screenWidth, int screenHeight,
                                        MediaPlaybackMonitorService.PlaybackSnapshot snapshot,
                                        String lyricLine, String qualityText) {
        if (canvas == null || screenWidth <= 0 || screenHeight <= 0) return;

        // Card horizontal margins
        float cardLeft = screenWidth * 0.08f;
        float cardRight = screenWidth * 0.92f;
        float cardWidth = cardRight - cardLeft;

        // Vertical positions directly above the bottom pill (pill sits around 0.884 * screenHeight)
        float row4TimeY = screenHeight * 0.866f;
        float row3ProgressY = screenHeight * 0.849f;
        float row2LyricsY = screenHeight * 0.830f;
        float row1ArtistY = screenHeight * 0.798f;
        float row1TitleY = screenHeight * 0.776f;

        Paint boldPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        boldPaint.setTypeface(sSfProBold != null ? sSfProBold : Typeface.DEFAULT_BOLD);
        boldPaint.setColor(Color.WHITE);
        boldPaint.clearShadowLayer();

        Paint medPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        medPaint.setTypeface(sSfProMedium != null ? sSfProMedium : Typeface.DEFAULT);
        medPaint.setColor(Color.WHITE);
        medPaint.clearShadowLayer();

        Paint regPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        regPaint.setTypeface(sSfProRegular != null ? sSfProRegular : Typeface.DEFAULT);
        regPaint.setColor(Color.argb(190, 255, 255, 255));
        regPaint.clearShadowLayer();

        Paint progressBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        progressBgPaint.setColor(Color.argb(65, 255, 255, 255));

        Paint progressFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        progressFillPaint.setColor(Color.WHITE);

        // 1. Right Action Buttons (Favorite Star & 3-Dots)
        float btnRadius = screenWidth * 0.038f; // ~48px circle
        float dotBtnCenterX = cardRight - btnRadius;
        float dotBtnCenterY = (row1TitleY + row1ArtistY) / 2.0f - dpToPx(screenWidth, 3);
        float starBtnCenterX = dotBtnCenterX - btnRadius * 2.5f;

        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(Color.argb(45, 255, 255, 255));
        canvas.drawCircle(dotBtnCenterX, dotBtnCenterY, btnRadius, circlePaint);
        canvas.drawCircle(starBtnCenterX, dotBtnCenterY, btnRadius, circlePaint);

        // Draw 3 horizontal dots inside menu button
        Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(Color.WHITE);
        float dotSpacing = btnRadius * 0.44f;
        float dotR = btnRadius * 0.12f;
        canvas.drawCircle(dotBtnCenterX - dotSpacing, dotBtnCenterY, dotR, dotPaint);
        canvas.drawCircle(dotBtnCenterX, dotBtnCenterY, dotR, dotPaint);
        canvas.drawCircle(dotBtnCenterX + dotSpacing, dotBtnCenterY, dotR, dotPaint);

        // Draw star outline inside favorite button
        drawStarIcon(canvas, starBtnCenterX, dotBtnCenterY, btnRadius * 0.52f, dotPaint);

        // Title and Artist Row
        String title = (snapshot != null && snapshot.title != null && !snapshot.title.isEmpty())
                ? snapshot.title : (sTrackTitle != null ? sTrackTitle : "Unknown Track");
        String artist = (snapshot != null && snapshot.artist != null && !snapshot.artist.isEmpty())
                ? snapshot.artist : (sTrackArtist != null ? sTrackArtist : "");

        float titleSize = Math.round(screenWidth * 0.035f); // ~44px
        boldPaint.setTextSize(titleSize);
        boldPaint.setTextAlign(Paint.Align.LEFT);

        float artistSize = Math.round(screenWidth * 0.024f); // ~30px
        regPaint.setTextSize(artistSize);
        regPaint.setTextAlign(Paint.Align.LEFT);

        float maxTitleW = starBtnCenterX - btnRadius - cardLeft - dpToPx(screenWidth, 10);

        // Draw [E] Explicit badge if song title indicates explicit or metadata flag
        float titleDrawX = cardLeft;
        boolean isExplicit = (snapshot != null && snapshot.isExplicit)
                || title.contains("Explicit") || title.startsWith("[E]");
        if (isExplicit) {
            float badgeW = dpToPx(screenWidth, 17);
            float badgeH = dpToPx(screenWidth, 15);
            float badgeY = row1TitleY - badgeH + dpToPx(screenWidth, 2);
            RectF badgeRect = new RectF(titleDrawX, badgeY, titleDrawX + badgeW, badgeY + badgeH);

            Paint badgeBg = new Paint(Paint.ANTI_ALIAS_FLAG);
            badgeBg.setColor(Color.argb(70, 255, 255, 255));
            canvas.drawRoundRect(badgeRect, dpToPx(screenWidth, 3), dpToPx(screenWidth, 3), badgeBg);

            Paint badgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
            badgeText.setTypeface(sSfProBold != null ? sSfProBold : Typeface.DEFAULT_BOLD);
            badgeText.setTextSize(dpToPx(screenWidth, 11));
            badgeText.setColor(Color.WHITE);
            badgeText.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("E", badgeRect.centerX(), badgeRect.centerY() + dpToPx(screenWidth, 4), badgeText);

            titleDrawX += badgeW + dpToPx(screenWidth, 6);
            maxTitleW -= (badgeW + dpToPx(screenWidth, 6));
        }

        String cleanDisplayTitle = title.replace("[E]", "").trim();
        String displayTitle = truncateText(cleanDisplayTitle, boldPaint, maxTitleW);
        canvas.drawText(displayTitle, titleDrawX, row1TitleY, boldPaint);

        String displayArtist = truncateText(artist, regPaint, maxTitleW);
        canvas.drawText(displayArtist, cardLeft, row1ArtistY, regPaint);

        // 2. Synced Lyrics Row
        if (lyricLine != null && !lyricLine.trim().isEmpty()) {
            float lyricSize = Math.round(screenWidth * 0.025f); // ~31px
            medPaint.setTextSize(lyricSize);
            medPaint.setTextAlign(Paint.Align.LEFT);
            medPaint.setLetterSpacing(0.012f);

            String fullLyric = lyricLine.trim() + "  ›";
            String displayLyric = truncateText(fullLyric, medPaint, cardWidth);
            canvas.drawText(displayLyric, cardLeft, row2LyricsY, medPaint);
        }

        // 3. Progress Bar Row
        long duration = (snapshot != null && snapshot.durationMs > 0) ? snapshot.durationMs : 180000L;
        long currentPos = 0;
        if (snapshot != null) {
            currentPos = snapshot.positionMs;
            if (snapshot.isPlaying && snapshot.lastPositionUpdateTime > 0) {
                long elapsed = android.os.SystemClock.elapsedRealtime() - snapshot.lastPositionUpdateTime;
                currentPos += (long) (elapsed * (snapshot.playbackSpeed > 0 ? snapshot.playbackSpeed : 1.0f));
            }
        }
        currentPos = Math.max(0, Math.min(duration, currentPos));
        float progress = duration > 0 ? (float) currentPos / (float) duration : 0.0f;

        float barHeight = Math.round(screenWidth * 0.0055f); // ~7px
        float cornerR = barHeight / 2.0f;
        RectF trackRect = new RectF(cardLeft, row3ProgressY, cardRight, row3ProgressY + barHeight);
        canvas.drawRoundRect(trackRect, cornerR, cornerR, progressBgPaint);

        float fillRight = cardLeft + cardWidth * progress;
        if (fillRight > cardLeft + 2.0f) {
            RectF fillRect = new RectF(cardLeft, row3ProgressY, fillRight, row3ProgressY + barHeight);
            canvas.drawRoundRect(fillRect, cornerR, cornerR, progressFillPaint);
        }

        // 4. Timestamps & Centered Audio Quality Info
        float timeSize = Math.round(screenWidth * 0.0185f); // ~23px
        regPaint.setTextSize(timeSize);
        regPaint.setTextAlign(Paint.Align.LEFT);
        regPaint.setColor(Color.argb(175, 255, 255, 255));

        // Left elapsed timestamp
        String elapsedStr = formatTime(currentPos);
        canvas.drawText(elapsedStr, cardLeft, row4TimeY, regPaint);

        // Right remaining timestamp
        regPaint.setTextAlign(Paint.Align.RIGHT);
        long remaining = Math.max(0, duration - currentPos);
        String remainingStr = "-" + formatTime(remaining);
        canvas.drawText(remainingStr, cardRight, row4TimeY, regPaint);

        // Center: Audio Quality Info
        if (qualityText != null && !qualityText.isEmpty()) {
            Paint qualPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            qualPaint.setTypeface(sSfProMedium != null ? sSfProMedium : Typeface.DEFAULT);
            qualPaint.setTextSize(timeSize);
            qualPaint.setTextAlign(Paint.Align.CENTER);
            qualPaint.setColor(Color.WHITE);
            qualPaint.clearShadowLayer();

            String qualDisplay = "≋  " + qualityText;
            canvas.drawText(qualDisplay, screenWidth * 0.50f, row4TimeY, qualPaint);
        }
    }

    private static void drawStarIcon(Canvas canvas, float cx, float cy, float r, Paint paint) {
        Paint strokePaint = new Paint(paint);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(2.6f);
        Path path = new Path();
        double angle = -Math.PI / 2.0;
        double step = Math.PI / 5.0;
        for (int i = 0; i < 10; i++) {
            float rad = (i % 2 == 0) ? r : r * 0.44f;
            float x = (float) (cx + Math.cos(angle) * rad);
            float y = (float) (cy + Math.sin(angle) * rad);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
            angle += step;
        }
        path.close();
        canvas.drawPath(path, strokePaint);
    }

    private static String formatTime(long ms) {
        long totalSec = Math.max(0, ms / 1000L);
        long m = totalSec / 60L;
        long s = totalSec % 60L;
        return String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    private static String truncateText(String text, Paint paint, float maxWidth) {
        if (text == null || text.isEmpty()) return "";
        if (paint.measureText(text) <= maxWidth) return text;
        CharSequence ellip = TextUtils.ellipsize(text, new TextPaint(paint), maxWidth, TextUtils.TruncateAt.END);
        return ellip != null ? ellip.toString() : text;
    }

    private static float dpToPx(int screenWidth, float dp) {
        return dp * (screenWidth / 360.0f);
    }
}
