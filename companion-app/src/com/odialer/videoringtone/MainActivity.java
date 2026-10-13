package com.odialer.videoringtone;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.ContactsContract;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * ColorOS 16 UI Kit (COUI) Redesigned MainActivity.
 * Includes:
 * 1. Dedicated ColorOS Background & Battery Diagnostics & Exemption dialog
 * 2. Real Telecom incoming call simulator (TestCallConnectionService) that triggers
 *    the actual Oppo Find X9 incoming call UI & video ringtone live.
 * 3. Video Appearance & Legibility controls (top/bottom gradient & ambient blur card).
 */
public class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 1001;
    private static final int REQ_ROLE_SCREENING = 1002;
    private static final int REQ_PICK_CONTACT = 1003;
    private static final int REQ_PICK_VIDEO = 1004;
    private static final int REQ_PICK_DEFAULT_RINGTONE = 1005;

    private String pendingContactId = "";
    private String pendingLookupKey = "";
    private String pendingDisplayName = "";
    private String pendingPhoneNumber = "";

    private boolean isDark = false;
    private boolean permissionsExpanded = true; // Always expanded so all permissions & battery options are visible

    // ColorOS 16 Dynamic Theme Tokens
    private int couiBgWithCard;
    private int couiCard;
    private int couiCardElevated;
    private int couiLabelPrimary;
    private int couiLabelSecondary;
    private int couiLabelTertiary;
    private int couiDivider;
    private int couiTheme;
    private int couiThemeHalftone;
    private int couiContainer8;
    private int couiSuccess;
    private int couiSuccessHalftone;
    private int couiWarning;
    private int couiWarningHalftone;
    private int couiError;
    private int couiErrorHalftone;
    private int couiPressRipple;
    private int couiCardStroke;
    private int couiContourGlow;

    private TextView modeBadgeView;
    private LinearLayout permissionsCardContainer;
    private TextView fallbackRingtoneValueTv;
    private TextView activeSystemStateSubTv;
    private TextView contactsGroupHeaderCountTv;
    private LinearLayout mappingsCardContainer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initColorOs16ThemeTokens();
        buildColorOs16Ui();
        configureSystemBars();

        // Register Telecom PhoneAccount for real incoming call simulation
        TestCallConnectionService.registerOrGetAccount(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        configureSystemBars();
        refreshAll();
    }

    private void initColorOs16ThemeTokens() {
        int nightModeFlags = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        isDark = (nightModeFlags == Configuration.UI_MODE_NIGHT_YES);

        couiTheme = Color.parseColor("#0066FF");
        couiThemeHalftone = isDark ? Color.parseColor("#2E0066FF") : Color.parseColor("#1F0066FF");
        couiSuccess = Color.parseColor("#00BD13");
        couiSuccessHalftone = isDark ? Color.parseColor("#2900BD13") : Color.parseColor("#1A00BD13");
        couiWarning = Color.parseColor("#FF9900");
        couiWarningHalftone = isDark ? Color.parseColor("#29FF9900") : Color.parseColor("#1AFF9900");
        couiError = Color.parseColor("#DB382C");
        couiErrorHalftone = isDark ? Color.parseColor("#29DB382C") : Color.parseColor("#1ADB382C");

        if (isDark) {
            couiBgWithCard = Color.parseColor("#000000");
            couiCard = Color.parseColor("#1C1C1E");
            couiCardElevated = Color.parseColor("#262629");
            couiCardStroke = Color.parseColor("#24FFFFFF");
            couiContourGlow = Color.parseColor("#44FFFFFF");
            couiLabelPrimary = Color.parseColor("#E5FFFFFF");
            couiLabelSecondary = Color.parseColor("#8AFFFFFF");
            couiLabelTertiary = Color.parseColor("#42FFFFFF");
            couiDivider = Color.parseColor("#1FFFFFFF");
            couiContainer8 = Color.parseColor("#1FFFFFFF");
            couiPressRipple = Color.parseColor("#1FFFFFFF");
        } else {
            couiBgWithCard = Color.parseColor("#F0F1F2");
            couiCard = Color.parseColor("#FFFFFF");
            couiCardElevated = Color.parseColor("#FFFFFF");
            couiCardStroke = Color.parseColor("#0F000000");
            couiContourGlow = Color.parseColor("#1C000000");
            couiLabelPrimary = Color.parseColor("#E5000000");
            couiLabelSecondary = Color.parseColor("#8A000000");
            couiLabelTertiary = Color.parseColor("#42000000");
            couiDivider = Color.parseColor("#14000000");
            couiContainer8 = Color.parseColor("#14000000");
            couiPressRipple = Color.parseColor("#14000000");
        }
    }

    private void configureSystemBars() {
        try {
            Window window = getWindow();
            if (window == null) return;
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(couiBgWithCard);
            window.setNavigationBarColor(couiBgWithCard);
            View decor = window.getDecorView();
            if (decor != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController ic = decor.getWindowInsetsController();
                if (ic != null) {
                    int appearance = isDark ? 0 : (WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                    ic.setSystemBarsAppearance(appearance,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private Typeface fontMedium() {
        return Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    private Typeface fontSemibold() {
        return Typeface.create("sans-serif-medium", Typeface.BOLD);
    }

    private GradientDrawable squircleBg(int fillColor, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(fillColor);
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    private GradientDrawable squircleWithContour(int fillColor, int strokeColor, int strokeWidthDp, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setColor(fillColor);
        gd.setCornerRadius(dp(radiusDp));
        if (strokeWidthDp > 0 && strokeColor != Color.TRANSPARENT) {
            gd.setStroke(dp(strokeWidthDp), strokeColor);
        }
        return gd;
    }

    private RippleDrawable rippleSquircle(int fillColor, int radiusDp) {
        GradientDrawable content = squircleBg(fillColor, radiusDp);
        GradientDrawable mask = squircleBg(Color.WHITE, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(couiPressRipple), content, mask);
    }

    private TextView createCapsuleButton(String text, int bgColor, int textColor, boolean large, View.OnClickListener onClick) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setGravity(Gravity.CENTER);
        btn.setTextColor(textColor);
        btn.setTypeface(fontMedium());
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, large ? 16 : 13);
        int heightDp = large ? 44 : 30;
        int hPadDp = large ? 24 : 14;
        btn.setMinHeight(dp(heightDp));
        btn.setPadding(dp(hPadDp), dp(large ? 10 : 5), dp(hPadDp), dp(large ? 10 : 5));
        btn.setBackground(rippleSquircle(bgColor, 999));
        btn.setClickable(true);
        btn.setFocusable(true);
        if (onClick != null) {
            btn.setOnClickListener(onClick);
        }
        return btn;
    }

    private TextView createTagPill(String text, int bgColor, int textColor) {
        TextView tag = new TextView(this);
        tag.setText(text);
        tag.setTextColor(textColor);
        tag.setTypeface(fontMedium());
        tag.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tag.setPadding(dp(8), dp(2), dp(8), dp(2));
        tag.setBackground(squircleBg(bgColor, 6));
        return tag;
    }

    private LinearLayout createGroupHeader(String titleText, View trailingView) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(18), dp(16), dp(8));

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(couiLabelSecondary);
        title.setTypeface(fontMedium());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (trailingView != null) {
            header.addView(trailingView);
        }
        return header;
    }

    private LinearLayout createGroupedCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(squircleWithContour(couiCard, couiCardStroke, 1, 18));
        return card;
    }

    private View createInsetDivider() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(16), 0, dp(16), 0);
        View line = new View(this);
        line.setBackgroundColor(couiDivider);
        wrap.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
        return wrap;
    }

    private void buildColorOs16Ui() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(couiBgWithCard);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(36));
        scroll.addView(root);

        // App Bar
        LinearLayout appBar = new LinearLayout(this);
        appBar.setOrientation(LinearLayout.VERTICAL);
        appBar.setPadding(dp(8), dp(14), dp(8), dp(8));

        LinearLayout topTitleRow = new LinearLayout(this);
        topTitleRow.setOrientation(LinearLayout.HORIZONTAL);
        topTitleRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView appTitle = new TextView(this);
        appTitle.setText("Video Ringtones");
        appTitle.setTextColor(couiLabelPrimary);
        appTitle.setTypeface(fontSemibold());
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        topTitleRow.addView(appTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        modeBadgeView = createTagPill("Audio Ready", couiSuccessHalftone, couiSuccess);
        topTitleRow.addView(modeBadgeView);
        appBar.addView(topTitleRow);

        TextView appSubtitle = new TextView(this);
        appSubtitle.setText("Assign custom video ringtones to specific contacts on your Find X9");
        appSubtitle.setTextColor(couiLabelSecondary);
        appSubtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        appSubtitle.setPadding(0, dp(4), 0, 0);
        appBar.addView(appSubtitle);
        root.addView(appBar);

        // Group 1: Permissions & Battery
        root.addView(createGroupHeader("System & Permissions", null));
        permissionsCardContainer = createGroupedCard();
        root.addView(permissionsCardContainer);

        // Group 2: Default Fallback Audio Ringtone
        root.addView(createGroupHeader("Default Fallback Ringtone", null));
        LinearLayout fallbackCard = createGroupedCard();

        LinearLayout defRow = new LinearLayout(this);
        defRow.setOrientation(LinearLayout.HORIZONTAL);
        defRow.setGravity(Gravity.CENTER_VERTICAL);
        defRow.setPadding(dp(16), dp(14), dp(16), dp(14));
        defRow.setBackground(rippleSquircle(Color.TRANSPARENT, 16));
        defRow.setOnClickListener(v -> pickDefaultAudioRingtone());

        LinearLayout defTextCol = new LinearLayout(this);
        defTextCol.setOrientation(LinearLayout.VERTICAL);
        TextView defTitle = new TextView(this);
        defTitle.setText("Other Contacts Ringtone");
        defTitle.setTextColor(couiLabelPrimary);
        defTitle.setTypeface(fontMedium());
        defTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        defTextCol.addView(defTitle);

        fallbackRingtoneValueTv = new TextView(this);
        fallbackRingtoneValueTv.setTextColor(couiLabelSecondary);
        fallbackRingtoneValueTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        fallbackRingtoneValueTv.setPadding(0, dp(2), 0, 0);
        defTextCol.addView(fallbackRingtoneValueTv);
        defRow.addView(defTextCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView changeBtn = createCapsuleButton("Change", couiThemeHalftone, couiTheme, false, v -> pickDefaultAudioRingtone());
        LinearLayout.LayoutParams changeLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        changeLp.leftMargin = dp(12);
        defRow.addView(changeBtn, changeLp);
        fallbackCard.addView(defRow);

        fallbackCard.addView(createInsetDivider());

        LinearLayout sysRow = new LinearLayout(this);
        sysRow.setOrientation(LinearLayout.HORIZONTAL);
        sysRow.setGravity(Gravity.CENTER_VERTICAL);
        sysRow.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout sysTextCol = new LinearLayout(this);
        sysTextCol.setOrientation(LinearLayout.VERTICAL);
        TextView sysTitle = new TextView(this);
        sysTitle.setText("Current System Ringtone State");
        sysTitle.setTextColor(couiLabelPrimary);
        sysTitle.setTypeface(fontMedium());
        sysTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sysTextCol.addView(sysTitle);

        activeSystemStateSubTv = new TextView(this);
        activeSystemStateSubTv.setTextColor(couiLabelSecondary);
        activeSystemStateSubTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        activeSystemStateSubTv.setPadding(0, dp(2), 0, 0);
        sysTextCol.addView(activeSystemStateSubTv);
        sysRow.addView(sysTextCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView resetBtn = createCapsuleButton("Restore Audio", couiContainer8, couiLabelPrimary, false, v -> {
            ContactVideoResolver.restoreDefaultAudioRingtone(this);
            Toast.makeText(this, "Fallback audio ringtone active", Toast.LENGTH_SHORT).show();
            refreshAll();
        });
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resetLp.leftMargin = dp(12);
        sysRow.addView(resetBtn, resetLp);
        fallbackCard.addView(sysRow);

        root.addView(fallbackCard);

        // Group 3: Contact Video Ringtones
        TextView testCallerLink = new TextView(this);
        testCallerLink.setText("Simulate Call (Live)");
        testCallerLink.setTextColor(couiTheme);
        testCallerLink.setTypeface(fontMedium());
        testCallerLink.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        testCallerLink.setPadding(dp(8), dp(4), dp(4), dp(4));
        testCallerLink.setOnClickListener(v -> showSimulateCallDialog());

        LinearLayout contactsHeader = createGroupHeader("Assigned Contact Videos", testCallerLink);
        contactsGroupHeaderCountTv = (TextView) contactsHeader.getChildAt(0);
        root.addView(contactsHeader);

        mappingsCardContainer = createGroupedCard();
        root.addView(mappingsCardContainer);

        TextView assignPrimaryBtn = createCapsuleButton(
                "+ Assign Video to Contact",
                couiTheme,
                Color.WHITE,
                true,
                v -> startAssignContactVideoFlow());
        LinearLayout.LayoutParams ctaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        ctaLp.topMargin = dp(16);
        root.addView(assignPrimaryBtn, ctaLp);

        TextView folderCaption = new TextView(this);
        folderCaption.setText("Auto-Scan Folder: /sdcard/" + ContactVideoResolver.FOLDER_REL_PATH + "/\n"
                + "Drop <Contact Name>.mp4 or <Phone Number>.mp4 there for automatic matching.");
        folderCaption.setTextColor(couiLabelTertiary);
        folderCaption.setGravity(Gravity.CENTER_HORIZONTAL);
        folderCaption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        folderCaption.setPadding(dp(12), dp(12), dp(12), dp(8));
        root.addView(folderCaption);

        setContentView(scroll);
    }

    private void refreshAll() {
        ContactVideoResolver.getVideoRingtonesDir();
        refreshPermissionsCard();
        refreshFallbackRingtoneCard();
        refreshMappingsCard();
    }

    private boolean hasRuntimePermissions() {
        boolean contacts = checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED;
        boolean phone = checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
        return contacts && phone;
    }

    private boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasWriteSettings() {
        return Settings.System.canWrite(this);
    }

    private boolean hasCallScreeningRole() {
        RoleManager rm = (RoleManager) getSystemService(ROLE_SERVICE);
        return rm != null && rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING);
    }

    private boolean isIgnoringBatteryOptimizations() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private boolean isBackgroundRestricted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            return am != null && am.isBackgroundRestricted();
        }
        return false;
    }

    private void refreshPermissionsCard() {
        permissionsCardContainer.removeAllViews();

        boolean p1 = hasRuntimePermissions();
        boolean p2 = hasAllFilesAccess();
        boolean p3 = hasWriteSettings();
        boolean p4 = hasCallScreeningRole();
        boolean p5 = isIgnoringBatteryOptimizations();
        int grantedCount = (p1 ? 1 : 0) + (p2 ? 1 : 0) + (p3 ? 1 : 0) + (p4 ? 1 : 0) + (p5 ? 1 : 0);
        boolean allGranted = (grantedCount == 5);

        // Header Summary Row
        LinearLayout summaryRow = new LinearLayout(this);
        summaryRow.setOrientation(LinearLayout.HORIZONTAL);
        summaryRow.setGravity(Gravity.CENTER_VERTICAL);
        summaryRow.setPadding(dp(16), dp(14), dp(16), dp(14));
        summaryRow.setOnClickListener(v -> {
            permissionsExpanded = !permissionsExpanded;
            refreshPermissionsCard();
        });

        TextView badgeIcon = new TextView(this);
        badgeIcon.setText(allGranted ? "✓" : "!");
        badgeIcon.setGravity(Gravity.CENTER);
        badgeIcon.setTextColor(allGranted ? couiSuccess : couiWarning);
        badgeIcon.setTypeface(fontSemibold());
        badgeIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        badgeIcon.setBackground(squircleBg(allGranted ? couiSuccessHalftone : couiWarningHalftone, 999));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        iconLp.rightMargin = dp(12);
        summaryRow.addView(badgeIcon, iconLp);

        LinearLayout sumTextCol = new LinearLayout(this);
        sumTextCol.setOrientation(LinearLayout.VERTICAL);
        TextView sumTitle = new TextView(this);
        sumTitle.setText(allGranted ? "All 5 System Permissions Active" : (grantedCount + " of 5 Permissions Configured"));
        sumTitle.setTextColor(couiLabelPrimary);
        sumTitle.setTypeface(fontMedium());
        sumTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        sumTextCol.addView(sumTitle);

        TextView sumSub = new TextView(this);
        sumSub.setText(allGranted
                ? "Pre-ring call screening & background execution ready"
                : "Tap any required item below to enable");
        sumSub.setTextColor(couiLabelSecondary);
        sumSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sumSub.setPadding(0, dp(2), 0, 0);
        sumTextCol.addView(sumSub);
        summaryRow.addView(sumTextCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView toggleTv = createCapsuleButton(
                permissionsExpanded ? "Collapse" : "Expand",
                couiContainer8,
                couiLabelSecondary,
                false,
                v -> {
                    permissionsExpanded = !permissionsExpanded;
                    refreshPermissionsCard();
                });
        summaryRow.addView(toggleTv);
        permissionsCardContainer.addView(summaryRow);

        if (permissionsExpanded) {
            // Dedicated ColorOS Battery & Background Diagnostic Row
            permissionsCardContainer.addView(createInsetDivider());
            addPermissionItemRow(
                    "Background Activity & Battery (ColorOS)",
                    p5 ? "Unrestricted · ColorOS will not freeze incoming call screening"
                            : "Battery Optimized · Tap 'Check Battery' to set Unrestricted",
                    p5,
                    "Check Battery",
                    v -> showBatteryDiagnosticsDialog());

            permissionsCardContainer.addView(createInsetDivider());
            addPermissionItemRow(
                    "Contacts & Phone State",
                    "Match incoming caller numbers to contacts",
                    p1,
                    "Grant",
                    v -> requestPermissions(new String[]{
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.WRITE_CONTACTS,
                            Manifest.permission.READ_PHONE_STATE,
                            Manifest.permission.READ_CALL_LOG,
                            Manifest.permission.READ_MEDIA_VIDEO,
                            Manifest.permission.READ_MEDIA_AUDIO,
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    }, REQ_PERMISSIONS));

            permissionsCardContainer.addView(createInsetDivider());
            addPermissionItemRow(
                    "All Files Access",
                    "Read & store videos in /sdcard/Ringtones/VideoRingtones",
                    p2,
                    "Allow",
                    v -> {
                        try {
                            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception e) {
                            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                        }
                    });

            permissionsCardContainer.addView(createInsetDivider());
            addPermissionItemRow(
                    "Modify System Settings",
                    "Switch ringtone URI when a matched contact calls",
                    p3,
                    "Allow",
                    v -> {
                        Intent i = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    });

            permissionsCardContainer.addView(createInsetDivider());
            addPermissionItemRow(
                    "Call Screening Service Role",
                    "Intercept calls before ColorOS Phone starts ringing",
                    p4,
                    "Set Role",
                    v -> {
                        RoleManager rm = (RoleManager) getSystemService(ROLE_SERVICE);
                        if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) {
                            Intent intent = rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING);
                            startActivityForResult(intent, REQ_ROLE_SCREENING);
                        } else {
                            Toast.makeText(this, "PhoneStateReceiver fallback active", Toast.LENGTH_LONG).show();
                        }
                    });
        }
    }

    private void addPermissionItemRow(String title, String subtitle, boolean ok, String btnText, View.OnClickListener onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextColor(couiLabelPrimary);
        tvTitle.setTypeface(fontMedium());
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        textCol.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText(subtitle);
        tvSub.setTextColor(couiLabelSecondary);
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tvSub.setPadding(0, dp(2), 0, 0);
        textCol.addView(tvSub);

        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (ok && !"Check Battery".equals(btnText)) {
            TextView tag = createTagPill("Enabled", couiSuccessHalftone, couiSuccess);
            row.addView(tag);
        } else {
            TextView btn = createCapsuleButton(btnText, couiThemeHalftone, couiTheme, false, onClick);
            row.addView(btn);
        }
        permissionsCardContainer.addView(row);
    }

    /**
     * ColorOS 16 Battery & Background Activity Diagnostic Dialog.
     * Checks exact system states and gives 1-tap options to configure ColorOS battery settings.
     */
    private void showBatteryDiagnosticsDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(22), dp(20), dp(20));
        card.setBackground(squircleWithContour(couiCardElevated, couiCardStroke, 1, 24));

        TextView title = new TextView(this);
        title.setText("ColorOS Battery Diagnostics");
        title.setTextColor(couiLabelPrimary);
        title.setTypeface(fontSemibold());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setGravity(Gravity.CENTER);
        card.addView(title);

        boolean ignoringOpt = isIgnoringBatteryOptimizations();
        boolean bgRestricted = isBackgroundRestricted();

        LinearLayout statusBox = new LinearLayout(this);
        statusBox.setOrientation(LinearLayout.VERTICAL);
        statusBox.setPadding(dp(14), dp(12), dp(14), dp(12));
        statusBox.setBackground(squircleBg(couiContainer8, 12));
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbLp.topMargin = dp(14);
        statusBox.setLayoutParams(sbLp);

        TextView s1 = new TextView(this);
        s1.setText("• Battery Optimization: " + (ignoringOpt ? "UNRESTRICTED (Optimal)" : "OPTIMIZED (May delay wakeups)"));
        s1.setTextColor(ignoringOpt ? couiSuccess : couiWarning);
        s1.setTypeface(fontMedium());
        s1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        statusBox.addView(s1);

        TextView s2 = new TextView(this);
        s2.setText("• Background Activity: " + (bgRestricted ? "RESTRICTED by System" : "ALLOWED"));
        s2.setTextColor(bgRestricted ? couiError : couiSuccess);
        s2.setTypeface(fontMedium());
        s2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        s2.setPadding(0, dp(4), 0, 0);
        statusBox.addView(s2);

        card.addView(statusBox);

        TextView desc = new TextView(this);
        desc.setText("On Oppo Find X9 (ColorOS 15/16), make sure 'Allow background activity' is ON in App Info → Battery usage. This ensures phone calls trigger video ringtone switching even when the phone has been idle.");
        desc.setTextColor(couiLabelSecondary);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        desc.setPadding(0, dp(12), 0, dp(16));
        card.addView(desc);

        // Action Buttons
        if (!ignoringOpt) {
            TextView optBtn = createCapsuleButton("Request Battery Exemption Prompt", couiThemeHalftone, couiTheme, false, v -> {
                dialog.dismiss();
                try {
                    Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(i);
                } catch (Exception e) {
                    startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                }
            });
            LinearLayout.LayoutParams obLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
            obLp.bottomMargin = dp(8);
            card.addView(optBtn, obLp);
        }

        TextView appInfoBtn = createCapsuleButton("Open ColorOS App Battery Settings", couiTheme, Color.WHITE, false, v -> {
            dialog.dismiss();
            try {
                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            } catch (Exception e) {
                Toast.makeText(this, "Please open Settings → Apps → ODialer Video Ringtones", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams aibLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        card.addView(appInfoBtn, aibLp);

        TextView closeBtn = createCapsuleButton("Done", couiContainer8, couiLabelPrimary, false, v -> dialog.dismiss());
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        cbLp.topMargin = dp(8);
        card.addView(closeBtn, cbLp);

        dialog.setContentView(card);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dp(328), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private String getRingtoneTitleClean(String uriStr) {
        if (TextUtils.isEmpty(uriStr)) return "System Default Audio";
        try {
            Ringtone r = RingtoneManager.getRingtone(this, Uri.parse(uriStr));
            if (r != null) {
                return r.getTitle(this);
            }
        } catch (Exception ignored) {
        }
        return uriStr;
    }

    private void refreshFallbackRingtoneCard() {
        ContactVideoResolver.ConfigData cfg = ContactVideoResolver.loadConfig(this);
        fallbackRingtoneValueTv.setText(getRingtoneTitleClean(cfg.defaultRingtoneSim1));

        String sysRingtone = Settings.System.getString(getContentResolver(), "ringtone");
        boolean isVideoActive = ContactVideoResolver.isOplusVideoRingtoneUri(sysRingtone);

        if (isVideoActive) {
            modeBadgeView.setText("Video Override Active");
            modeBadgeView.setTextColor(couiTheme);
            modeBadgeView.setBackground(squircleBg(couiThemeHalftone, 6));
            activeSystemStateSubTv.setText("Video URI currently active in Settings.System");
        } else {
            modeBadgeView.setText("Audio Ready");
            modeBadgeView.setTextColor(couiSuccess);
            modeBadgeView.setBackground(squircleBg(couiSuccessHalftone, 6));
            activeSystemStateSubTv.setText("Standby · Switches automatically when assigned contact calls");
        }
    }

    private String getInitials(String name) {
        if (TextUtils.isEmpty(name)) return "#";
        String cleaned = name.replace("Phone:", "").trim();
        if (cleaned.isEmpty()) return "#";
        String[] parts = cleaned.split("\\s+");
        if (parts.length >= 2 && !parts[0].isEmpty() && !parts[1].isEmpty()) {
            return ("" + parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase(Locale.ROOT);
        }
        return ("" + cleaned.charAt(0)).toUpperCase(Locale.ROOT);
    }

    private void refreshMappingsCard() {
        mappingsCardContainer.removeAllViews();
        List<ContactVideoResolver.ContactMapping> all = ContactVideoResolver.getAllMappingsWithFolderScan(this);
        contactsGroupHeaderCountTv.setText("Assigned Contact Videos (" + all.size() + ")");

        if (all.isEmpty()) {
            LinearLayout emptyBox = new LinearLayout(this);
            emptyBox.setOrientation(LinearLayout.VERTICAL);
            emptyBox.setGravity(Gravity.CENTER);
            emptyBox.setPadding(dp(24), dp(28), dp(24), dp(28));

            TextView emptyTitle = new TextView(this);
            emptyTitle.setText("No Contact Videos Assigned");
            emptyTitle.setTextColor(couiLabelPrimary);
            emptyTitle.setTypeface(fontMedium());
            emptyTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            emptyBox.addView(emptyTitle);

            TextView emptyDesc = new TextView(this);
            emptyDesc.setText("Tap '+ Assign Video to Contact' below to pick a contact and video, or place <Contact Name>.mp4 in /sdcard/"
                    + ContactVideoResolver.FOLDER_REL_PATH + "/");
            emptyDesc.setTextColor(couiLabelSecondary);
            emptyDesc.setGravity(Gravity.CENTER);
            emptyDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            emptyDesc.setPadding(0, dp(6), 0, 0);
            emptyBox.addView(emptyDesc);

            mappingsCardContainer.addView(emptyBox);
            return;
        }

        for (int idx = 0; idx < all.size(); idx++) {
            if (idx > 0) {
                mappingsCardContainer.addView(createInsetDivider());
            }
            ContactVideoResolver.ContactMapping m = all.get(idx);

            // Contact Card Item
            LinearLayout cardItem = new LinearLayout(this);
            cardItem.setOrientation(LinearLayout.VERTICAL);
            cardItem.setPadding(dp(16), dp(16), dp(16), dp(16));
            cardItem.setBackground(rippleSquircle(Color.TRANSPARENT, 16));

            // Top Row: Avatar + Contact Name / Phone / File
            LinearLayout topRow = new LinearLayout(this);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);
            topRow.setOnClickListener(v -> showColorOsPreviewDialog(m));

            // Avatar Circle (44x44dp)
            TextView avatar = new TextView(this);
            avatar.setText(getInitials(m.displayName));
            avatar.setGravity(Gravity.CENTER);
            avatar.setTextColor(couiTheme);
            avatar.setTypeface(fontSemibold());
            avatar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            avatar.setBackground(squircleBg(couiThemeHalftone, 999));
            LinearLayout.LayoutParams avLp = new LinearLayout.LayoutParams(dp(44), dp(44));
            avLp.rightMargin = dp(14);
            topRow.addView(avatar, avLp);

            // Contact Info Column (Takes remaining width)
            LinearLayout infoCol = new LinearLayout(this);
            infoCol.setOrientation(LinearLayout.VERTICAL);

            TextView nameTv = new TextView(this);
            nameTv.setText(!TextUtils.isEmpty(m.displayName) ? m.displayName : "Unnamed Contact");
            nameTv.setTextColor(couiLabelPrimary);
            nameTv.setTypeface(fontSemibold());
            nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            nameTv.setSingleLine(true);
            nameTv.setEllipsize(TextUtils.TruncateAt.END);
            infoCol.addView(nameTv);

            String fileName = m.videoPath != null ? new File(m.videoPath).getName() : "video.mp4";
            String subLine = !TextUtils.isEmpty(m.phoneNumber)
                    ? (m.phoneNumber + "  ·  " + fileName)
                    : fileName;
            TextView subTv = new TextView(this);
            subTv.setText(subLine);
            subTv.setTextColor(couiLabelSecondary);
            subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            subTv.setSingleLine(true);
            subTv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            subTv.setPadding(0, dp(2), 0, 0);
            infoCol.addView(subTv);

            // Status indicator row with presentation & vignette tags
            LinearLayout badgeRow = new LinearLayout(this);
            badgeRow.setOrientation(LinearLayout.HORIZONTAL);
            badgeRow.setGravity(Gravity.CENTER_VERTICAL);
            badgeRow.setPadding(0, dp(4), 0, 0);

            boolean validInOplus = ContactVideoResolver.isOplusLocalVideoValid(this, m.videoUri);
            TextView statusTv = new TextView(this);
            statusTv.setText(validInOplus ? "● Ready" : "▲ Needs Index");
            statusTv.setTextColor(validInOplus ? couiSuccess : couiWarning);
            statusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            statusTv.setTypeface(fontMedium());
            badgeRow.addView(statusTv);

            String modeText = (m.displayMode == VideoProcessor.MODE_AMBIENT_CARD) ? "Ambient Card" : "Full Screen";
            TextView modeTag = createTagPill(modeText, couiThemeHalftone, couiTheme);
            LinearLayout.LayoutParams mtLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mtLp.leftMargin = dp(8);
            badgeRow.addView(modeTag, mtLp);

            if (m.vignetteEnabled) {
                TextView vigTag = createTagPill("Vignette", couiContainer8, couiLabelSecondary);
                LinearLayout.LayoutParams vtLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                vtLp.leftMargin = dp(4);
                badgeRow.addView(vigTag, vtLp);
            }

            if (m.displayMode == VideoProcessor.MODE_AMBIENT_CARD && m.contourGlowEnabled) {
                TextView glowTag = createTagPill("Contour Glow", couiThemeHalftone, couiTheme);
                LinearLayout.LayoutParams gtLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                gtLp.leftMargin = dp(4);
                badgeRow.addView(glowTag, gtLp);
            }

            infoCol.addView(badgeRow);

            topRow.addView(infoCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            cardItem.addView(topRow);

            // Action Buttons Row: Distributed horizontally with comfortable touch targets
            LinearLayout actionsRow = new LinearLayout(this);
            actionsRow.setOrientation(LinearLayout.HORIZONTAL);
            actionsRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams actRowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            actRowLp.topMargin = dp(14);
            actionsRow.setLayoutParams(actRowLp);

            // "Ring Live" Button
            TextView ringBtn = createCapsuleButton("Ring Live", couiSuccessHalftone, couiSuccess, false, v -> {
                triggerRealSimulatedCallWithFeedback(m.phoneNumber, m.displayName);
            });
            LinearLayout.LayoutParams ringLp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            ringLp.rightMargin = dp(8);
            actionsRow.addView(ringBtn, ringLp);

            // "Preview" Button
            TextView previewBtn = createCapsuleButton("Preview", couiThemeHalftone, couiTheme, false, v -> showColorOsPreviewDialog(m));
            LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            prevLp.rightMargin = dp(8);
            actionsRow.addView(previewBtn, prevLp);

            // "Remove" Button
            TextView delBtn = createCapsuleButton("Remove", couiErrorHalftone, couiError, false, v -> removeMapping(m));
            LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            actionsRow.addView(delBtn, delLp);

            cardItem.addView(actionsRow);
            mappingsCardContainer.addView(cardItem);
        }
    }

    private void pickDefaultAudioRingtone() {
        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_RINGTONE);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
        ContactVideoResolver.ConfigData cfg = ContactVideoResolver.loadConfig(this);
        if (!TextUtils.isEmpty(cfg.defaultRingtoneSim1)) {
            intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, Uri.parse(cfg.defaultRingtoneSim1));
        }
        startActivityForResult(intent, REQ_PICK_DEFAULT_RINGTONE);
    }

    private void startAssignContactVideoFlow() {
        if (!hasRuntimePermissions()) {
            Toast.makeText(this, "Please grant Contacts & Phone permissions first", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent pickContact = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        startActivityForResult(pickContact, REQ_PICK_CONTACT);
    }

    /**
     * Triggers a real incoming call that rings through Android Telecom and launches
     * ColorOS Phone.apk (com.android.incallui) playing the per-contact video ringtone.
     */
    private void triggerRealSimulatedCallWithFeedback(String phoneNumber, String displayName) {
        String num = !TextUtils.isEmpty(phoneNumber) ? phoneNumber : "9876543210";
        String name = !TextUtils.isEmpty(displayName) ? displayName : "Simulated Caller";

        boolean triggered = TestCallConnectionService.triggerRealIncomingCall(this, num, name, 1200);
        if (triggered) {
            Toast.makeText(this, "Ringing phone in 1.2s from " + name + " (" + num + ")...", Toast.LENGTH_LONG).show();
        } else {
            // PhoneAccount needs to be enabled in Calling Accounts settings
            new Dialog(this) {{
                requestWindowFeature(Window.FEATURE_NO_TITLE);
                if (getWindow() != null) {
                    getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                }
                LinearLayout c = new LinearLayout(MainActivity.this);
                c.setOrientation(LinearLayout.VERTICAL);
                c.setPadding(dp(20), dp(22), dp(20), dp(20));
                c.setBackground(squircleWithContour(couiCardElevated, couiCardStroke, 1, 24));

                TextView t = new TextView(MainActivity.this);
                t.setText("Calling Account Setup");
                t.setTextColor(couiLabelPrimary);
                t.setTypeface(fontSemibold());
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                t.setGravity(Gravity.CENTER);
                c.addView(t);

                TextView d = new TextView(MainActivity.this);
                d.setText("To simulate a real ringing call directly on ColorOS:\n\n"
                        + "1. Tap below to open Phone Settings.\n"
                        + "2. Go to 'Calling accounts' (or Advanced settings) and toggle ON 'ODialer Video Ringtone Simulator'.\n\n"
                        + "Tip: Since your phone is connected to your PC, we also pre-enabled this via ADB so you can tap Ring Live right away!");
                d.setTextColor(couiLabelSecondary);
                d.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                d.setPadding(0, dp(8), 0, dp(16));
                c.addView(d);

                TextView btn = createCapsuleButton("Open Calling Accounts", couiTheme, Color.WHITE, false, v -> {
                    dismiss();
                    try {
                        startActivity(new Intent(TelecomManager.ACTION_CHANGE_PHONE_ACCOUNTS));
                    } catch (Exception e) {
                        try {
                            startActivity(new Intent("android.telecom.action.CHANGE_PHONE_ACCOUNTS"));
                        } catch (Exception ex) {
                            Toast.makeText(MainActivity.this, "Open Phone → Settings → Calling accounts", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
                c.addView(btn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)));

                setContentView(c);
                show();
                if (getWindow() != null) {
                    getWindow().setLayout(dp(328), ViewGroup.LayoutParams.WRAP_CONTENT);
                }
            }};
        }
    }

    /**
     * ColorOS 16 Dialog (Round Corner/XXL = 24dp) for previewing a contact's assigned video ringtone.
     */
    private void showColorOsPreviewDialog(ContactVideoResolver.ContactMapping m) {
        if (!TextUtils.isEmpty(m.videoPath)) {
            File f = new File(m.videoPath);
            if (f.exists() && !ContactVideoResolver.isOplusLocalVideoValid(this, m.videoUri)) {
                m.videoUri = ContactVideoResolver.resolveToExternalPrimaryVideoUri(this, f, null);
                ContactVideoResolver.ConfigData cfg = ContactVideoResolver.loadConfig(this);
                for (ContactVideoResolver.ContactMapping existing : cfg.mappings) {
                    if (f.getAbsolutePath().equalsIgnoreCase(existing.videoPath)) {
                        existing.videoUri = m.videoUri;
                    }
                }
                ContactVideoResolver.saveConfig(this, cfg);
                refreshAll();
            }
        }

        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(20), dp(22), dp(20), dp(20));
        card.setBackground(squircleWithContour(couiCardElevated, couiCardStroke, 1, 24));

        TextView title = new TextView(this);
        title.setText(!TextUtils.isEmpty(m.displayName) ? m.displayName : "Video Preview");
        title.setTextColor(couiLabelPrimary);
        title.setTypeface(fontSemibold());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setGravity(Gravity.CENTER);
        card.addView(title);

        TextView sub = new TextView(this);
        sub.setText(!TextUtils.isEmpty(m.phoneNumber) ? m.phoneNumber : (m.videoPath != null ? new File(m.videoPath).getName() : ""));
        sub.setTextColor(couiLabelSecondary);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(4), 0, dp(14));
        card.addView(sub);

        FrameLayout videoContainer = new FrameLayout(this);
        videoContainer.setBackground(squircleWithContour(Color.BLACK, couiContourGlow, 1, 16));
        videoContainer.setClipToOutline(true);
        videoContainer.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(16));
            }
        });
        LinearLayout.LayoutParams vcLp = new LinearLayout.LayoutParams(dp(240), dp(360));
        videoContainer.setLayoutParams(vcLp);

        VideoView vv = new VideoView(this);
        FrameLayout.LayoutParams vvLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
        vv.setLayoutParams(vvLp);
        if (!TextUtils.isEmpty(m.videoUri)) {
            vv.setVideoURI(Uri.parse(m.videoUri));
        } else if (!TextUtils.isEmpty(m.videoPath)) {
            vv.setVideoPath(m.videoPath);
        }
        vv.setOnPreparedListener(mp -> mp.setLooping(true));
        vv.start();
        videoContainer.addView(vv);
        card.addView(videoContainer);

        TextView note = new TextView(this);
        note.setText("Plays automatically when this contact calls.\nAll other callers hear your fallback audio ringtone.");
        note.setTextColor(couiLabelSecondary);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        note.setGravity(Gravity.CENTER);
        note.setPadding(dp(8), dp(14), dp(8), dp(16));
        card.addView(note);

        // Visual Styling Controls for this Video
        LinearLayout styleBox = new LinearLayout(this);
        styleBox.setOrientation(LinearLayout.VERTICAL);
        styleBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        styleBox.setBackground(squircleWithContour(couiContainer8, couiCardStroke, 1, 14));
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbLp.topMargin = dp(8);
        sbLp.bottomMargin = dp(12);
        styleBox.setLayoutParams(sbLp);

        TextView styleTitle = new TextView(this);
        styleTitle.setText("Textured Visual Effects (ColorOS 16)");
        styleTitle.setTextColor(couiLabelPrimary);
        styleTitle.setTypeface(fontMedium());
        styleTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        styleBox.addView(styleTitle);

        // State holder for dialog
        final boolean[] localVig = new boolean[]{ m.vignetteEnabled };
        final int[] localMode = new int[]{ m.displayMode };
        final boolean[] localContour = new boolean[]{ m.contourGlowEnabled };

        LinearLayout optRow = new LinearLayout(this);
        optRow.setOrientation(LinearLayout.HORIZONTAL);
        optRow.setGravity(Gravity.CENTER_VERTICAL);
        optRow.setPadding(0, dp(8), 0, dp(8));

        TextView vigBtn = createCapsuleButton(
                localVig[0] ? "Gradient ON" : "Gradient OFF",
                localVig[0] ? couiThemeHalftone : couiCard,
                localVig[0] ? couiTheme : couiLabelSecondary,
                false, null);
        vigBtn.setOnClickListener(v -> {
            localVig[0] = !localVig[0];
            vigBtn.setText(localVig[0] ? "Gradient ON" : "Gradient OFF");
            vigBtn.setBackground(rippleSquircle(localVig[0] ? couiThemeHalftone : couiCard, 999));
            vigBtn.setTextColor(localVig[0] ? couiTheme : couiLabelSecondary);
        });
        LinearLayout.LayoutParams vbLp = new LinearLayout.LayoutParams(0, dp(32), 1f);
        vbLp.rightMargin = dp(4);
        optRow.addView(vigBtn, vbLp);

        TextView modeBtn = createCapsuleButton(
                localMode[0] == VideoProcessor.MODE_AMBIENT_CARD ? "Ambient Card" : "Full Fill",
                localMode[0] == VideoProcessor.MODE_AMBIENT_CARD ? couiThemeHalftone : couiCard,
                localMode[0] == VideoProcessor.MODE_AMBIENT_CARD ? couiTheme : couiLabelSecondary,
                false, null);

        TextView contourBtn = createCapsuleButton(
                localContour[0] ? "Contour ON" : "Contour OFF",
                localContour[0] ? couiThemeHalftone : couiCard,
                localContour[0] ? couiTheme : couiLabelSecondary,
                false, null);
        contourBtn.setVisibility(localMode[0] == VideoProcessor.MODE_AMBIENT_CARD ? View.VISIBLE : View.GONE);
        contourBtn.setOnClickListener(v -> {
            localContour[0] = !localContour[0];
            contourBtn.setText(localContour[0] ? "Contour ON" : "Contour OFF");
            contourBtn.setBackground(rippleSquircle(localContour[0] ? couiThemeHalftone : couiCard, 999));
            contourBtn.setTextColor(localContour[0] ? couiTheme : couiLabelSecondary);
        });

        modeBtn.setOnClickListener(v -> {
            localMode[0] = (localMode[0] == VideoProcessor.MODE_AMBIENT_CARD) ? VideoProcessor.MODE_FULL_SCREEN : VideoProcessor.MODE_AMBIENT_CARD;
            modeBtn.setText(localMode[0] == VideoProcessor.MODE_AMBIENT_CARD ? "Ambient Card" : "Full Fill");
            modeBtn.setBackground(rippleSquircle(localMode[0] == VideoProcessor.MODE_AMBIENT_CARD ? couiThemeHalftone : couiCard, 999));
            modeBtn.setTextColor(localMode[0] == VideoProcessor.MODE_AMBIENT_CARD ? couiTheme : couiLabelSecondary);
            contourBtn.setVisibility(localMode[0] == VideoProcessor.MODE_AMBIENT_CARD ? View.VISIBLE : View.GONE);
        });
        LinearLayout.LayoutParams mbLp = new LinearLayout.LayoutParams(0, dp(32), 1f);
        optRow.addView(modeBtn, mbLp);

        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(0, dp(32), 1f);
        cbLp.leftMargin = dp(4);
        optRow.addView(contourBtn, cbLp);
        styleBox.addView(optRow);

        TextView reprocessBtn = createCapsuleButton("Apply Style & Enhance", couiTheme, Color.WHITE, false, v -> {
            vv.stopPlayback();
            Dialog procD = showProcessingModalDialog("Enhancing Video...");
            new Thread(() -> {
                try {
                    VideoProcessor.ProcessingOptions opts = new VideoProcessor.ProcessingOptions();
                    opts.enableVignette = localVig[0];
                    opts.displayMode = localMode[0];
                    opts.enableContourGlow = localContour[0];

                    ContactVideoResolver.reprocessVideoMapping(
                            MainActivity.this,
                            m,
                            opts,
                            pct -> runOnUiThread(() -> updateProcessingModalDialog(procD, pct)));

                    runOnUiThread(() -> {
                        procD.dismiss();
                        Toast.makeText(MainActivity.this, "Visual styling applied!", Toast.LENGTH_SHORT).show();
                        if (!TextUtils.isEmpty(m.videoUri)) {
                            vv.setVideoURI(Uri.parse(m.videoUri));
                        } else {
                            vv.setVideoPath(m.videoPath);
                        }
                        vv.start();
                        refreshAll();
                    });
                } catch (Exception ex) {
                    runOnUiThread(() -> {
                        procD.dismiss();
                        Toast.makeText(MainActivity.this, "Failed to enhance video: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }).start();
        });
        styleBox.addView(reprocessBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));
        card.addView(styleBox);

        // Buttons: "Ring Phone Now" (Real Call) + "Done"
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        TextView ringRealBtn = createCapsuleButton("Ring Phone Now", couiSuccessHalftone, couiSuccess, false, v -> {
            vv.stopPlayback();
            dialog.dismiss();
            triggerRealSimulatedCallWithFeedback(m.phoneNumber, m.displayName);
        });
        LinearLayout.LayoutParams b1Lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        b1Lp.rightMargin = dp(10);
        btnRow.addView(ringRealBtn, b1Lp);

        TextView doneBtn = createCapsuleButton("Done", couiTheme, Color.WHITE, false, v -> {
            vv.stopPlayback();
            dialog.dismiss();
        });
        LinearLayout.LayoutParams b2Lp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        btnRow.addView(doneBtn, b2Lp);

        card.addView(btnRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dialog.setContentView(card);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dp(328), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    private Dialog showProcessingModalDialog(String titleText) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        d.setCancelable(false);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(24), dp(24), dp(24), dp(24));
        c.setBackground(squircleWithContour(couiCardElevated, couiCardStroke, 1, 24));

        TextView tv = new TextView(this);
        tv.setText(titleText);
        tv.setTextColor(couiLabelPrimary);
        tv.setTypeface(fontSemibold());
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tv.setGravity(Gravity.CENTER);
        c.addView(tv);

        TextView pctTv = new TextView(this);
        pctTv.setText("Applying hardware video filters... 0%");
        pctTv.setTextColor(couiLabelSecondary);
        pctTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pctTv.setGravity(Gravity.CENTER);
        pctTv.setPadding(0, dp(8), 0, dp(14));
        pctTv.setTag("progress_text");
        c.addView(pctTv);

        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(false);
        pb.setMax(100);
        pb.setProgress(0);
        pb.setTag("progress_bar");
        c.addView(pb, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(12)));

        d.setContentView(c);
        d.show();
        if (d.getWindow() != null) {
            d.getWindow().setLayout(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return d;
    }

    private void updateProcessingModalDialog(Dialog d, int pct) {
        if (d == null || !d.isShowing()) return;
        View pb = d.findViewById(android.R.id.content).findViewWithTag("progress_bar");
        if (pb instanceof ProgressBar) {
            ((ProgressBar) pb).setProgress(pct);
        }
        View pt = d.findViewById(android.R.id.content).findViewWithTag("progress_text");
        if (pt instanceof TextView) {
            ((TextView) pt).setText("Applying hardware video filters... " + pct + "%");
        }
    }

    private void removeMapping(ContactVideoResolver.ContactMapping m) {
        ContactVideoResolver.ConfigData cfg = ContactVideoResolver.loadConfig(this);
        cfg.mappings.removeIf(item ->
                (!TextUtils.isEmpty(m.videoPath) && m.videoPath.equalsIgnoreCase(item.videoPath))
                        || (!TextUtils.isEmpty(m.contactId) && m.contactId.equals(item.contactId)));
        ContactVideoResolver.saveConfig(this, cfg);
        if (!TextUtils.isEmpty(m.videoPath)) {
            File f = new File(m.videoPath);
            if (f.exists()) {
                f.delete();
            }
        }
        if (!TextUtils.isEmpty(m.originalVideoPath)) {
            File rawF = new File(m.originalVideoPath);
            if (rawF.exists()) {
                rawF.delete();
            }
        }
        refreshAll();
    }

    private void showSimulateCallDialog() {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(22), dp(20), dp(20));
        card.setBackground(squircleWithContour(couiCardElevated, couiCardStroke, 1, 24));

        TextView title = new TextView(this);
        title.setText("Simulate Real Incoming Call");
        title.setTextColor(couiLabelPrimary);
        title.setTypeface(fontSemibold());
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setGravity(Gravity.CENTER);
        card.addView(title);

        TextView desc = new TextView(this);
        desc.setText("Trigger a live incoming call through Android Telecom so you can watch your Oppo Dialer ring with the video ringtone in real-time.");
        desc.setTextColor(couiLabelSecondary);
        desc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, dp(6), 0, dp(14));
        card.addView(desc);

        EditText input = new EditText(this);
        input.setHint("Phone number (e.g. 9876543210)");
        input.setHintTextColor(couiLabelTertiary);
        input.setTextColor(couiLabelPrimary);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setPadding(dp(14), dp(12), dp(14), dp(12));
        input.setBackground(squircleBg(couiContainer8, 12));
        card.addView(input);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(18);
        btnRow.setLayoutParams(rowLp);

        TextView cancelBtn = createCapsuleButton("Cancel", couiContainer8, couiLabelPrimary, false, v -> dialog.dismiss());
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
        cLp.rightMargin = dp(10);
        btnRow.addView(cancelBtn, cLp);

        TextView simBtn = createCapsuleButton("Ring Phone Now", couiSuccess, Color.WHITE, false, v -> {
            String num = input.getText().toString().trim();
            if (TextUtils.isEmpty(num)) {
                num = "9876543210";
            }
            dialog.dismiss();
            ContactVideoResolver.CallerContactInfo ci = ContactVideoResolver.lookupContactByNumber(this, num);
            triggerRealSimulatedCallWithFeedback(num, ci.displayName);
        });
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, dp(42), 1.2f);
        btnRow.addView(simBtn, sLp);

        card.addView(btnRow);
        dialog.setContentView(card);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(dp(328), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_DEFAULT_RINGTONE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            if (uri != null) {
                ContactVideoResolver.ConfigData cfg = ContactVideoResolver.loadConfig(this);
                cfg.defaultRingtoneSim1 = uri.toString();
                cfg.defaultRingtoneSim2 = uri.toString();
                ContactVideoResolver.saveConfig(this, cfg);
                ContactVideoResolver.restoreDefaultAudioRingtone(this);
                Toast.makeText(this, "Fallback audio ringtone saved", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_PICK_CONTACT && resultCode == RESULT_OK && data != null) {
            Uri contactData = data.getData();
            if (contactData != null) {
                String[] proj = new String[]{
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                        ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                };
                try (Cursor c = getContentResolver().query(contactData, proj, null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        pendingContactId = String.valueOf(c.getLong(0));
                        pendingLookupKey = c.getString(1) != null ? c.getString(1) : "";
                        pendingDisplayName = c.getString(2) != null ? c.getString(2) : "";
                        pendingPhoneNumber = c.getString(3) != null ? c.getString(3) : "";

                        Intent pickVideo = new Intent(Intent.ACTION_GET_CONTENT);
                        pickVideo.setType("video/*");
                        pickVideo.addCategory(Intent.CATEGORY_OPENABLE);
                        startActivityForResult(Intent.createChooser(pickVideo, "Select Video for " + pendingDisplayName), REQ_PICK_VIDEO);
                    }
                } catch (Exception e) {
                    Toast.makeText(this, "Error reading contact: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        } else if (requestCode == REQ_PICK_VIDEO && resultCode == RESULT_OK && data != null) {
            Uri videoUri = data.getData();
            if (videoUri != null) {
                final String contactId = pendingContactId;
                final String lookupKey = pendingLookupKey;
                final String displayName = pendingDisplayName;
                final String phoneNumber = pendingPhoneNumber;
                Dialog progressDialog = showProcessingModalDialog("Enhancing Video for ColorOS...");
                new Thread(() -> {
                    try {
                        VideoProcessor.ProcessingOptions opts = new VideoProcessor.ProcessingOptions();
                        opts.enableVignette = true;
                        opts.displayMode = VideoProcessor.MODE_FULL_SCREEN;

                        ContactVideoResolver.ContactMapping m = ContactVideoResolver.importAndAssignVideo(
                                this,
                                contactId,
                                lookupKey,
                                displayName,
                                phoneNumber,
                                videoUri,
                                opts,
                                pct -> runOnUiThread(() -> updateProcessingModalDialog(progressDialog, pct)));

                        runOnUiThread(() -> {
                            progressDialog.dismiss();
                            Toast.makeText(this, "Assigned video to " + m.displayName, Toast.LENGTH_SHORT).show();
                            refreshAll();
                            showColorOsPreviewDialog(m);
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> {
                            progressDialog.dismiss();
                            Toast.makeText(this, "Failed to assign video: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            refreshAll();
                        });
                    }
                }).start();
                return;
            }
        }
        refreshAll();
    }
}
