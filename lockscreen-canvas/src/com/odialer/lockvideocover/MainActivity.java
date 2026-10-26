package com.odialer.lockvideocover;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * ColorOS 17 Aquamorphic Redesigned MainActivity for Lock Video Cover.
 * 60FPS Hardware Video Engine (OpenGL ES 2.0) with ColorOS 17 Aquamorphic Transitions.
 * Supports Oppo Find X9 stock live wallpaper, custom video wallpaper, or custom photo wallpaper.
 */
public class MainActivity extends Activity {
    public static final String ACTION_STATUS_UPDATE = "com.odialer.lockvideocover.STATUS_UPDATE";
    public static final String EXTRA_TITLE = "extra_title";
    public static final String EXTRA_ARTIST = "extra_artist";
    public static final String EXTRA_STATUS = "extra_status";

    // ColorOS Aquamorphic Tokens
    private final int couiBg = 0xFF000000;
    private final int couiCard = 0xFF141414;
    private final int couiCardElevated = 0xFF1C1C1E;
    private final int couiLabelPrimary = 0xFFFFFFFF;
    private final int couiLabelSecondary = 0x99FFFFFF;
    private final int couiLabelTertiary = 0x4DFFFFFF;
    private final int couiTheme = 0xFF00C070; // ColorOS Green
    private final int couiThemeHalftone = 0x2600C070;
    private final int couiContainer8 = 0x14FFFFFF;
    private final int couiCardStroke = 0x1AFFFFFF;
    private final int couiError = 0xFFFF453A;
    private final int couiSuccess = 0xFF30D158;

    private static final int REQ_PICK_PHOTO = 2001;
    private static final int REQ_PICK_VIDEO = 2002;

    private TextView mStatusPill;
    private TextView mNowPlayingTitle;
    private TextView mNowPlayingArtist;
    private TextView mCanvasStatePill;
    private Switch mEngineToggle;
    private EditText mEtSpDc;

    private final BroadcastReceiver mStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && ACTION_STATUS_UPDATE.equals(intent.getAction())) {
                String title = intent.getStringExtra(EXTRA_TITLE);
                String artist = intent.getStringExtra(EXTRA_ARTIST);
                String status = intent.getStringExtra(EXTRA_STATUS);

                if (title != null) mNowPlayingTitle.setText(title);
                if (artist != null) mNowPlayingArtist.setText(artist);
                if (status != null) {
                    mCanvasStatePill.setText(status);
                    if (status.contains("Playing") || status.contains("Found")) {
                        mCanvasStatePill.setTextColor(couiSuccess);
                        mCanvasStatePill.setBackground(squircleBg(0x2630D158, 8, couiSuccess));
                    } else if (status.contains("Error") || status.contains("No canvas")) {
                        mCanvasStatePill.setTextColor(couiLabelSecondary);
                        mCanvasStatePill.setBackground(squircleBg(couiContainer8, 8, 0));
                    } else {
                        mCanvasStatePill.setTextColor(couiTheme);
                        mCanvasStatePill.setBackground(squircleBg(couiThemeHalftone, 8, couiTheme));
                    }
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(couiBg);
        getWindow().setNavigationBarColor(couiBg);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(couiBg);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(36), dp(20), dp(32));
        scroll.addView(root);

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView appTitle = new TextView(this);
        appTitle.setText("Lock Video Cover");
        appTitle.setTextColor(couiLabelPrimary);
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        appTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        titleCol.addView(appTitle);

        TextView subTitle = new TextView(this);
        subTitle.setText("60FPS Hardware Canvas Engine");
        subTitle.setTextColor(couiLabelSecondary);
        subTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subTitle.setPadding(0, dp(2), 0, 0);
        titleCol.addView(subTitle);

        header.addView(titleCol);

        mStatusPill = new TextView(this);
        mStatusPill.setText("ACTIVE");
        mStatusPill.setTextColor(couiTheme);
        mStatusPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        mStatusPill.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mStatusPill.setPadding(dp(10), dp(4), dp(10), dp(4));
        mStatusPill.setBackground(squircleBg(couiThemeHalftone, 12, couiTheme));
        header.addView(mStatusPill);

        root.addView(header);

        // 1. Now Playing Card
        root.addView(createSectionHeader("NOW PLAYING"));
        LinearLayout nowPlayingCard = createCard();

        LinearLayout npHeaderRow = new LinearLayout(this);
        npHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        npHeaderRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView npLabel = new TextView(this);
        npLabel.setText("Active Music Source");
        npLabel.setTextColor(couiLabelSecondary);
        npLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        npLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        npHeaderRow.addView(npLabel);

        mCanvasStatePill = new TextView(this);
        mCanvasStatePill.setText("Listening...");
        mCanvasStatePill.setTextColor(couiLabelSecondary);
        mCanvasStatePill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        mCanvasStatePill.setPadding(dp(8), dp(3), dp(8), dp(3));
        mCanvasStatePill.setBackground(squircleBg(couiContainer8, 8, 0));
        npHeaderRow.addView(mCanvasStatePill);

        nowPlayingCard.addView(npHeaderRow);

        mNowPlayingTitle = new TextView(this);
        mNowPlayingTitle.setText("Waiting for BitChord...");
        mNowPlayingTitle.setTextColor(couiLabelPrimary);
        mNowPlayingTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        mNowPlayingTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        mNowPlayingTitle.setPadding(0, dp(10), 0, dp(2));
        nowPlayingCard.addView(mNowPlayingTitle);

        mNowPlayingArtist = new TextView(this);
        mNowPlayingArtist.setText("Play any track to start 60fps edge-to-edge canvas");
        mNowPlayingArtist.setTextColor(couiLabelSecondary);
        mNowPlayingArtist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        nowPlayingCard.addView(mNowPlayingArtist);

        root.addView(nowPlayingCard);

        // 2. Engine & Wallpaper Setup Card
        root.addView(createSectionHeader("60FPS VIDEO ENGINE & SETUP"));
        LinearLayout engineCard = createCard();

        // Main Toggle Row
        LinearLayout toggleRow = new LinearLayout(this);
        toggleRow.setOrientation(LinearLayout.HORIZONTAL);
        toggleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout toggleTextCol = new LinearLayout(this);
        toggleTextCol.setOrientation(LinearLayout.VERTICAL);
        toggleTextCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView toggleTitle = new TextView(this);
        toggleTitle.setText("Hardware Video Engine (60 FPS)");
        toggleTitle.setTextColor(couiLabelPrimary);
        toggleTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        toggleTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        toggleTextCol.addView(toggleTitle);

        TextView toggleSub = new TextView(this);
        toggleSub.setText("Plays full-screen Canvas video while music runs. Shows idle wallpaper when paused.");
        toggleSub.setTextColor(couiLabelSecondary);
        toggleSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        toggleSub.setPadding(0, dp(2), 0, 0);
        toggleTextCol.addView(toggleSub);

        toggleRow.addView(toggleTextCol);

        boolean initialEnabled = MediaPlaybackMonitorService.isInjectionEnabled(this);
        mEngineToggle = new Switch(this);
        mEngineToggle.setChecked(initialEnabled);
        mEngineToggle.setOnCheckedChangeListener((b, isChecked) -> {
            MediaPlaybackMonitorService.setInjectionEnabled(this, isChecked);
            if (!isChecked) {
                LockscreenWallpaperService.notifyPlaybackState(false);
            }
            updateStatus();
            Toast.makeText(this, isChecked ? "60FPS Engine Enabled" : "Engine Paused", Toast.LENGTH_SHORT).show();
        });
        toggleRow.addView(mEngineToggle);

        engineCard.addView(toggleRow);

        // Divider
        View div0 = new View(this);
        div0.setBackgroundColor(couiContainer8);
        LinearLayout.LayoutParams dLp0 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dLp0.setMargins(0, dp(14), 0, dp(14));
        div0.setLayoutParams(dLp0);
        engineCard.addView(div0);

        // Album Art Fallback Toggle Row
        LinearLayout artToggleRow = new LinearLayout(this);
        artToggleRow.setOrientation(LinearLayout.HORIZONTAL);
        artToggleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout artTextCol = new LinearLayout(this);
        artTextCol.setOrientation(LinearLayout.VERTICAL);
        artTextCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView artTitle = new TextView(this);
        artTitle.setText("Album Art Background (No Canvas)");
        artTitle.setTextColor(couiLabelPrimary);
        artTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        artTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        artTextCol.addView(artTitle);

        TextView artSub = new TextView(this);
        artSub.setText("Fills lockscreen with static album art if a song has no Canvas video.");
        artSub.setTextColor(couiLabelSecondary);
        artSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        artSub.setPadding(0, dp(2), 0, 0);
        artTextCol.addView(artSub);

        artToggleRow.addView(artTextCol);

        Switch artToggle = new Switch(this);
        artToggle.setChecked(MediaPlaybackMonitorService.isAlbumArtOnNoCanvasEnabled(this));
        artToggle.setOnCheckedChangeListener((b, isChecked) -> {
            MediaPlaybackMonitorService.setAlbumArtOnNoCanvasEnabled(this, isChecked);
            Toast.makeText(this, isChecked ? "Album Art fallback enabled" : "Idle background will show if no Canvas", Toast.LENGTH_SHORT).show();
        });
        artToggleRow.addView(artToggle);
        engineCard.addView(artToggleRow);

        // Divider
        View divTopGrad = new View(this);
        divTopGrad.setBackgroundColor(couiContainer8);
        LinearLayout.LayoutParams dLpTopGrad = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dLpTopGrad.setMargins(0, dp(14), 0, dp(14));
        divTopGrad.setLayoutParams(dLpTopGrad);
        engineCard.addView(divTopGrad);

        // Top Wallpaper Gradient Blend Toggle Row (Image 2)
        LinearLayout topGradRow = new LinearLayout(this);
        topGradRow.setOrientation(LinearLayout.HORIZONTAL);
        topGradRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout topGradTextCol = new LinearLayout(this);
        topGradTextCol.setOrientation(LinearLayout.VERTICAL);
        topGradTextCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView topGradTitle = new TextView(this);
        topGradTitle.setText("Top Wallpaper Gradient Reveal");
        topGradTitle.setTextColor(couiLabelPrimary);
        topGradTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        topGradTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        topGradTextCol.addView(topGradTitle);

        TextView topGradSub = new TextView(this);
        topGradSub.setText("Softly fades the canvas near the top to reveal your stock lockscreen wallpaper behind clock & widgets.");
        topGradSub.setTextColor(couiLabelSecondary);
        topGradSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        topGradSub.setPadding(0, dp(2), 0, 0);
        topGradTextCol.addView(topGradSub);

        topGradRow.addView(topGradTextCol);

        Switch topGradToggle = new Switch(this);
        topGradToggle.setChecked(MediaPlaybackMonitorService.isTopGradientBlendEnabled(this));
        topGradToggle.setOnCheckedChangeListener((b, isChecked) -> {
            MediaPlaybackMonitorService.setTopGradientBlendEnabled(this, isChecked);
            LockscreenWallpaperService.requestOverlayRedraw();
            Toast.makeText(this, isChecked ? "Top Wallpaper Reveal enabled" : "Full-bleed canvas enabled", Toast.LENGTH_SHORT).show();
        });
        topGradRow.addView(topGradToggle);

        engineCard.addView(topGradRow);

        // Divider
        View divCard = new View(this);
        divCard.setBackgroundColor(couiContainer8);
        LinearLayout.LayoutParams dLpCard = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dLpCard.setMargins(0, dp(14), 0, dp(14));
        divCard.setLayoutParams(dLpCard);
        engineCard.addView(divCard);

        // BitChord Now Playing Card & Synced Lyrics Toggle Row (Image 1)
        LinearLayout cardToggleRow = new LinearLayout(this);
        cardToggleRow.setOrientation(LinearLayout.HORIZONTAL);
        cardToggleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout cardTextCol = new LinearLayout(this);
        cardTextCol.setOrientation(LinearLayout.VERTICAL);
        cardTextCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView cardTitle = new TextView(this);
        cardTitle.setText("Synced Lyrics Preview");
        cardTitle.setTextColor(couiLabelPrimary);
        cardTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cardTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        cardTextCol.addView(cardTitle);

        TextView cardSub = new TextView(this);
        cardSub.setText("Show one-line center-aligned synced lyrics preview right above audio quality details.");
        cardSub.setTextColor(couiLabelSecondary);
        cardSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        cardSub.setPadding(0, dp(2), 0, 0);
        cardTextCol.addView(cardSub);

        cardToggleRow.addView(cardTextCol);

        Switch cardToggle = new Switch(this);
        cardToggle.setChecked(MediaPlaybackMonitorService.isNowPlayingCardEnabled(this));
        cardToggle.setOnCheckedChangeListener((b, isChecked) -> {
            MediaPlaybackMonitorService.setNowPlayingCardEnabled(this, isChecked);
            LockscreenWallpaperService.requestOverlayRedraw();
            Toast.makeText(this, isChecked ? "Synced lyrics preview enabled" : "Audio quality details only", Toast.LENGTH_SHORT).show();
        });
        cardToggleRow.addView(cardToggle);

        engineCard.addView(cardToggleRow);

        // Divider
        View divWake = new View(this);
        divWake.setBackgroundColor(couiContainer8);
        LinearLayout.LayoutParams dLpWake = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dLpWake.setMargins(0, dp(14), 0, dp(14));
        divWake.setLayoutParams(dLpWake);
        engineCard.addView(divWake);

        // Wake Screen on Song Shift Row
        LinearLayout wakeToggleRow = new LinearLayout(this);
        wakeToggleRow.setOrientation(LinearLayout.HORIZONTAL);
        wakeToggleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout wakeTextCol = new LinearLayout(this);
        wakeTextCol.setOrientation(LinearLayout.VERTICAL);
        wakeTextCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView wakeTitle = new TextView(this);
        wakeTitle.setText("Wake Screen on Song Shift");
        wakeTitle.setTextColor(couiLabelPrimary);
        wakeTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        wakeTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        wakeTextCol.addView(wakeTitle);

        TextView wakeSub = new TextView(this);
        wakeSub.setText("Turn screen on when a new track plays while phone is in off state.");
        wakeSub.setTextColor(couiLabelSecondary);
        wakeSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        wakeSub.setPadding(0, dp(2), 0, 0);
        wakeTextCol.addView(wakeSub);

        wakeToggleRow.addView(wakeTextCol);

        Switch wakeToggle = new Switch(this);
        wakeToggle.setChecked(MediaPlaybackMonitorService.isWakeOnTrackChangeEnabled(this));
        wakeToggle.setOnCheckedChangeListener((b, isChecked) -> {
            MediaPlaybackMonitorService.setWakeOnTrackChangeEnabled(this, isChecked);
            Toast.makeText(this, isChecked ? "Screen wake on song shift enabled" : "Screen wake on song shift disabled", Toast.LENGTH_SHORT).show();
        });
        wakeToggleRow.addView(wakeToggle);

        engineCard.addView(wakeToggleRow);

        // Divider
        View div1 = new View(this);
        div1.setBackgroundColor(couiContainer8);
        LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        dLp.setMargins(0, dp(14), 0, dp(14));
        div1.setLayoutParams(dLp);
        engineCard.addView(div1);

        // Apply Live Wallpaper Row
        LinearLayout wpRow = new LinearLayout(this);
        wpRow.setOrientation(LinearLayout.HORIZONTAL);
        wpRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout wpTextCol = new LinearLayout(this);
        wpTextCol.setOrientation(LinearLayout.VERTICAL);
        wpTextCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView wpTitle = new TextView(this);
        wpTitle.setText("Apply Lock Screen Wallpaper");
        wpTitle.setTextColor(couiLabelPrimary);
        wpTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        wpTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        wpTextCol.addView(wpTitle);

        TextView wpSub = new TextView(this);
        wpSub.setText("Tap 'Apply' -> Select 'Lock screen'. Home screen is 100% untouched.");
        wpSub.setTextColor(couiLabelSecondary);
        wpSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        wpSub.setPadding(0, dp(2), 0, 0);
        wpTextCol.addView(wpSub);

        wpRow.addView(wpTextCol);

        TextView wpBtn = createCapsuleButton("Apply", couiTheme, Color.BLACK, v -> {
            Intent intent = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
            intent.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    new ComponentName(this, LockscreenWallpaperService.class));
            startActivity(intent);
        });
        wpRow.addView(wpBtn);

        engineCard.addView(wpRow);
        root.addView(engineCard);

        // Canvas Lockscreen Timeout Card
        root.addView(createSectionHeader("CANVAS LOCKSCREEN TIMEOUT"));
        LinearLayout timeoutCard = createCard();

        // 1. Master Toggle Row
        LinearLayout toToggleRow = new LinearLayout(this);
        toToggleRow.setOrientation(LinearLayout.HORIZONTAL);
        toToggleRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout toTextCol = new LinearLayout(this);
        toTextCol.setOrientation(LinearLayout.VERTICAL);
        toTextCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView toTitle = new TextView(this);
        toTitle.setText("Canvas Screen Timeout");
        toTitle.setTextColor(couiLabelPrimary);
        toTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        toTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        toTextCol.addView(toTitle);

        TextView toSub = new TextView(this);
        toSub.setText("Keep lock screen on for a custom duration while canvas video is playing, then automatically turn off.");
        toSub.setTextColor(couiLabelSecondary);
        toSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        toSub.setPadding(0, dp(2), 0, 0);
        toTextCol.addView(toSub);

        toToggleRow.addView(toTextCol);

        Switch toToggle = new Switch(this);
        toToggle.setChecked(CanvasScreenTimeoutManager.isTimeoutEnabled(this));
        toToggleRow.addView(toToggle);

        timeoutCard.addView(toToggleRow);

        // Divider
        View toDiv0 = new View(this);
        toDiv0.setBackgroundColor(couiContainer8);
        LinearLayout.LayoutParams toDivLp0 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        toDivLp0.setMargins(0, dp(14), 0, dp(14));
        toDiv0.setLayoutParams(toDivLp0);
        timeoutCard.addView(toDiv0);

        // 2. Duration Selector Row
        TextView durHeader = new TextView(this);
        durHeader.setText("Screen On Duration");
        durHeader.setTextColor(couiLabelPrimary);
        durHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        durHeader.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        timeoutCard.addView(durHeader);

        TextView durSub = new TextView(this);
        durSub.setText("Select how long the lock screen stays awake when canvas is playing:");
        durSub.setTextColor(couiLabelSecondary);
        durSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        durSub.setPadding(0, dp(2), 0, dp(10));
        timeoutCard.addView(durSub);

        // Segmented Duration Chips (5s, 7s, 10s, 15s, 30s)
        LinearLayout chipsRow = new LinearLayout(this);
        chipsRow.setOrientation(LinearLayout.HORIZONTAL);
        chipsRow.setGravity(Gravity.CENTER_VERTICAL);

        int[] durations = { 5, 7, 10, 15, 30 };
        String[] labels = { "5s", "7s", "10s", "15s", "30s" };
        List<TextView> chipViews = new ArrayList<>();

        int currentSec = CanvasScreenTimeoutManager.getTimeoutSeconds(this);

        for (int i = 0; i < durations.length; i++) {
            final int sec = durations[i];
            final TextView chip = new TextView(this);
            chip.setText(labels[i]);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            chip.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(10), dp(10), dp(10), dp(10));

            LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) cLp.leftMargin = dp(6);
            chip.setLayoutParams(cLp);

            boolean isSelected = (sec == currentSec);
            chip.setBackground(squircleBg(isSelected ? couiTheme : couiContainer8, 12, isSelected ? 0 : couiCardStroke));
            chip.setTextColor(isSelected ? Color.BLACK : couiLabelPrimary);

            chip.setOnClickListener(v -> {
                CanvasScreenTimeoutManager.setTimeoutSeconds(this, sec);
                for (int j = 0; j < durations.length; j++) {
                    TextView cv = chipViews.get(j);
                    boolean sel = (durations[j] == sec);
                    cv.setBackground(squircleBg(sel ? couiTheme : couiContainer8, 12, sel ? 0 : couiCardStroke));
                    cv.setTextColor(sel ? Color.BLACK : couiLabelPrimary);
                }
                Toast.makeText(this, "Canvas timeout set to " + sec + " seconds", Toast.LENGTH_SHORT).show();
            });

            chipViews.add(chip);
            chipsRow.addView(chip);
        }

        timeoutCard.addView(chipsRow);

        toToggle.setOnCheckedChangeListener((b, isChecked) -> {
            CanvasScreenTimeoutManager.setTimeoutEnabled(this, isChecked);
            chipsRow.setAlpha(isChecked ? 1.0f : 0.4f);
            for (TextView cv : chipViews) {
                cv.setEnabled(isChecked);
            }
            Toast.makeText(this, isChecked ? "Canvas timeout enabled" : "Canvas timeout disabled (using system default)", Toast.LENGTH_SHORT).show();
        });
        chipsRow.setAlpha(toToggle.isChecked() ? 1.0f : 0.4f);
        for (TextView cv : chipViews) {
            cv.setEnabled(toToggle.isChecked());
        }

        // Divider
        View toDiv1 = new View(this);
        toDiv1.setBackgroundColor(couiContainer8);
        LinearLayout.LayoutParams toDivLp1 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        toDivLp1.setMargins(0, dp(14), 0, dp(14));
        toDiv1.setLayoutParams(toDivLp1);
        timeoutCard.addView(toDiv1);

        // 3. Status & Instant Test Row
        LinearLayout testRow = new LinearLayout(this);
        testRow.setOrientation(LinearLayout.HORIZONTAL);
        testRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout testTextCol = new LinearLayout(this);
        testTextCol.setOrientation(LinearLayout.VERTICAL);
        testTextCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView testTitle = new TextView(this);
        testTitle.setText("Lock Service Status");
        testTitle.setTextColor(couiLabelPrimary);
        testTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        testTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        testTextCol.addView(testTitle);

        TextView testSub = new TextView(this);
        testSub.setText("Active (Accessibility & Device Admin enabled)");
        testSub.setTextColor(couiSuccess);
        testSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        testSub.setPadding(0, dp(2), 0, 0);
        testTextCol.addView(testSub);

        testRow.addView(testTextCol);

        TextView testBtn = createCapsuleButton("Test (2s)", couiContainer8, couiLabelPrimary, v -> {
            Toast.makeText(this, "Turning screen off in 2 seconds...", Toast.LENGTH_SHORT).show();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                CanvasScreenTimeoutManager.turnScreenOff(this);
            }, 2000);
        });
        testRow.addView(testBtn);

        timeoutCard.addView(testRow);
        root.addView(timeoutCard);

        // 3. Idle Lockscreen Wallpaper Card (Video or Photo)
        root.addView(createSectionHeader("IDLE LOCKSCREEN WALLPAPER"));
        LinearLayout idleCard = createCard();

        TextView idleDesc = new TextView(this);
        idleDesc.setText("Displayed on your lockscreen whenever music is idle or paused. Smoothly crossfades when music plays:");
        idleDesc.setTextColor(couiLabelSecondary);
        idleDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        idleDesc.setPadding(0, 0, 0, dp(14));
        idleCard.addView(idleDesc);

        LinearLayout previewRow = new LinearLayout(this);
        previewRow.setOrientation(LinearLayout.HORIZONTAL);
        previewRow.setGravity(Gravity.CENTER_VERTICAL);

        ImageView ivThumb = new ImageView(this);
        ivThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int thumbW = dp(60);
        int thumbH = dp(106);
        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(thumbW, thumbH);
        thumbLp.setMargins(0, 0, dp(14), 0);
        ivThumb.setLayoutParams(thumbLp);
        ivThumb.setBackground(squircleBg(couiContainer8, 12, couiCardStroke));
        ivThumb.setClipToOutline(true);

        Bitmap previewBmp = null;
        boolean isVideoMode = WallpaperBackupManager.isIdleVideoMode(this);
        if (isVideoMode) {
            String vPath = WallpaperBackupManager.getEffectiveIdleVideoPath(this);
            if (vPath != null && new File(vPath).exists()) {
                try {
                    MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                    mmr.setDataSource(vPath);
                    previewBmp = mmr.getFrameAtTime(500000);
                    mmr.release();
                } catch (Exception ignored) {}
            }
        }
        if (previewBmp == null) {
            previewBmp = WallpaperBackupManager.loadBackupBitmap(this);
        }
        if (previewBmp != null) {
            ivThumb.setImageBitmap(previewBmp);
        }
        previewRow.addView(ivThumb);

        LinearLayout btnCol = new LinearLayout(this);
        btnCol.setOrientation(LinearLayout.VERTICAL);
        btnCol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        boolean hasCustomVid = WallpaperBackupManager.hasCustomVideo(this);
        boolean hasCustomImg = WallpaperBackupManager.hasCustomImage(this);

        String currentTitle = "Stock Oppo Find X9 Live Wallpaper";
        String currentSub = "60FPS looping ambient animation";
        if (isVideoMode && hasCustomVid) {
            currentTitle = "Custom Video Wallpaper";
            currentSub = "Active 60FPS video loop from gallery";
        } else if (!isVideoMode && hasCustomImg) {
            currentTitle = "Custom Photo Wallpaper";
            currentSub = "Active personal photo from gallery";
        } else if (!isVideoMode) {
            currentTitle = "Stock Oppo Find X9 Frame";
            currentSub = "Stock Oppo Find X9 ambient theme";
        }

        TextView stateTitle = new TextView(this);
        stateTitle.setText(currentTitle);
        stateTitle.setTextColor(couiLabelPrimary);
        stateTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        stateTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btnCol.addView(stateTitle);

        TextView stateSub = new TextView(this);
        stateSub.setText(currentSub);
        stateSub.setTextColor(couiLabelSecondary);
        stateSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        stateSub.setPadding(0, dp(2), 0, dp(10));
        btnCol.addView(stateSub);

        // Buttons Row 1: Pick Video & Pick Photo
        LinearLayout actBtnRow = new LinearLayout(this);
        actBtnRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView pickVidBtn = createCapsuleButton("Pick Video", couiTheme, Color.BLACK, v -> {
            Intent pickIntent = new Intent(Intent.ACTION_PICK);
            pickIntent.setType("video/*");
            startActivityForResult(pickIntent, REQ_PICK_VIDEO);
        });
        actBtnRow.addView(pickVidBtn);

        TextView pickPhotoBtn = createCapsuleButton("Pick Photo", couiContainer8, couiLabelPrimary, v -> {
            Intent pickIntent = new Intent(Intent.ACTION_PICK);
            pickIntent.setType("image/*");
            startActivityForResult(pickIntent, REQ_PICK_PHOTO);
        });
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pLp.setMarginStart(dp(8));
        pickPhotoBtn.setLayoutParams(pLp);
        actBtnRow.addView(pickPhotoBtn);

        btnCol.addView(actBtnRow);

        // Buttons Row 2: Reset to Oppo Find X9 Stock Video
        if (hasCustomVid || !isVideoMode) {
            TextView resetStockBtn = createCapsuleButton("Reset to Oppo Stock Video", couiContainer8, couiTheme, v -> {
                WallpaperBackupManager.resetToStockVideo(this);
                LockscreenWallpaperService.notifyWallpaperChanged();
                Toast.makeText(this, "Restored Oppo Find X9 live wallpaper!", Toast.LENGTH_SHORT).show();
                recreate();
            });
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.topMargin = dp(8);
            resetStockBtn.setLayoutParams(rLp);
            btnCol.addView(resetStockBtn);
        }

        previewRow.addView(btnCol);
        idleCard.addView(previewRow);

        // ColorOS Wallpapers Browser
        TextView colorOsWpBtn = createCapsuleButton("Browse ColorOS Wallpapers", couiContainer8, couiLabelSecondary, v -> {
            try {
                Intent intent = new Intent();
                intent.setComponent(new ComponentName("com.oplus.wallpapers", "com.oplus.wallpapers.home.HomeActivity"));
                startActivity(intent);
            } catch (Exception e) {
                try {
                    Intent intent = new Intent(Intent.ACTION_SET_WALLPAPER);
                    startActivity(Intent.createChooser(intent, "Wallpapers"));
                } catch (Exception ignored) {}
            }
        });
        LinearLayout.LayoutParams cosLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cosLp.topMargin = dp(14);
        colorOsWpBtn.setLayoutParams(cosLp);
        idleCard.addView(colorOsWpBtn);

        root.addView(idleCard);

        // 4. Spotify Token Card
        root.addView(createSectionHeader("SPOTIFY SESSION CONFIGURATION"));
        LinearLayout tokenCard = createCard();

        TextView tokenDesc = new TextView(this);
        tokenDesc.setText("Extracted sp_dc cookie used to fetch high-framerate Spotify Canvas video clips:");
        tokenDesc.setTextColor(couiLabelSecondary);
        tokenDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        tokenCard.addView(tokenDesc);

        mEtSpDc = new EditText(this);
        mEtSpDc.setText(SpotifyTokenManager.getSpDcToken(this));
        mEtSpDc.setTextColor(couiLabelPrimary);
        mEtSpDc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        mEtSpDc.setBackground(squircleBg(couiContainer8, 12, couiCardStroke));
        mEtSpDc.setPadding(dp(12), dp(10), dp(12), dp(10));
        mEtSpDc.setMaxLines(3);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etLp.topMargin = dp(10);
        etLp.bottomMargin = dp(12);
        mEtSpDc.setLayoutParams(etLp);
        tokenCard.addView(mEtSpDc);

        TextView saveBtn = createCapsuleButton("Save & Update Token", couiTheme, Color.BLACK, v -> {
            String token = mEtSpDc.getText().toString().trim();
            SpotifyTokenManager.setSpDcToken(this, token);
            Toast.makeText(this, "Spotify sp_dc token saved!", Toast.LENGTH_SHORT).show();
        });
        tokenCard.addView(saveBtn);

        root.addView(tokenCard);

        setContentView(scroll);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri mediaUri = data.getData();
            if (requestCode == REQ_PICK_PHOTO) {
                Toast.makeText(this, "Saving photo...", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    try (InputStream is = getContentResolver().openInputStream(mediaUri)) {
                        if (is != null && WallpaperBackupManager.saveCustomImage(MainActivity.this, is)) {
                            LockscreenWallpaperService.notifyWallpaperChanged();
                            runOnUiThread(() -> {
                                Toast.makeText(MainActivity.this, "Lockscreen photo applied!", Toast.LENGTH_SHORT).show();
                                recreate();
                            });
                        }
                    } catch (Exception e) {
                        Log.e("MainActivity", "Failed saving photo: " + e.getMessage(), e);
                    }
                }).start();
            } else if (requestCode == REQ_PICK_VIDEO) {
                Toast.makeText(this, "Saving video...", Toast.LENGTH_SHORT).show();
                new Thread(() -> {
                    try (InputStream is = getContentResolver().openInputStream(mediaUri)) {
                        if (is != null && WallpaperBackupManager.saveCustomVideo(MainActivity.this, is)) {
                            LockscreenWallpaperService.notifyWallpaperChanged();
                            runOnUiThread(() -> {
                                Toast.makeText(MainActivity.this, "Lockscreen video applied!", Toast.LENGTH_SHORT).show();
                                recreate();
                            });
                        }
                    } catch (Exception e) {
                        Log.e("MainActivity", "Failed saving video: " + e.getMessage(), e);
                    }
                }).start();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
        IntentFilter filter = new IntentFilter(ACTION_STATUS_UPDATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mStatusReceiver, filter);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(mStatusReceiver);
        } catch (Exception ignored) {}
    }

    private void updateStatus() {
        boolean permOk = isNotificationServiceEnabled();
        boolean injectionEnabled = MediaPlaybackMonitorService.isInjectionEnabled(this);

        if (!permOk) {
            mStatusPill.setText("ACTION REQUIRED");
            mStatusPill.setTextColor(couiError);
            mStatusPill.setBackground(squircleBg(0x26FF453A, 12, couiError));
        } else if (!injectionEnabled) {
            mStatusPill.setText("PAUSED");
            mStatusPill.setTextColor(0xFFFF9500);
            mStatusPill.setBackground(squircleBg(0x26FF9500, 12, 0xFFFF9500));
        } else {
            mStatusPill.setText("ACTIVE");
            mStatusPill.setTextColor(couiTheme);
            mStatusPill.setBackground(squircleBg(couiThemeHalftone, 12, couiTheme));
        }

        if (MediaPlaybackMonitorService.sCurrentTitle != null && mNowPlayingTitle != null) {
            mNowPlayingTitle.setText(MediaPlaybackMonitorService.sCurrentTitle);
            if (MediaPlaybackMonitorService.sCurrentArtist != null && mNowPlayingArtist != null) {
                mNowPlayingArtist.setText(MediaPlaybackMonitorService.sCurrentArtist);
            }
            if (MediaPlaybackMonitorService.sCurrentStatus != null && mCanvasStatePill != null) {
                mCanvasStatePill.setText(MediaPlaybackMonitorService.sCurrentStatus);
                if (MediaPlaybackMonitorService.sCurrentStatus.contains("Playing") || MediaPlaybackMonitorService.sCurrentStatus.contains("Found")) {
                    mCanvasStatePill.setTextColor(couiSuccess);
                    mCanvasStatePill.setBackground(squircleBg(0x2630D158, 8, couiSuccess));
                }
            }
        }
    }

    private TextView createSectionHeader(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(couiLabelTertiary);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tv.setLetterSpacing(0.08f);
        tv.setPadding(dp(4), dp(22), dp(4), dp(8));
        return tv;
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(squircleBg(couiCard, 20, couiCardStroke));
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        return card;
    }

    private TextView createCapsuleButton(String text, int bgColor, int textColor, View.OnClickListener click) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(textColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(16), dp(10), dp(16), dp(10));
        btn.setBackground(squircleBg(bgColor, 14, 0));
        btn.setOnClickListener(click);
        return btn;
    }

    private GradientDrawable squircleBg(int color, int radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) {
            d.setStroke(dp(1), strokeColor);
        }
        return d;
    }

    private boolean isNotificationServiceEnabled() {
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (flat != null && !flat.isEmpty()) {
            final String[] names = flat.split(":");
            for (String name : names) {
                final ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null && pkgName.equals(cn.getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private int dp(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }
}
