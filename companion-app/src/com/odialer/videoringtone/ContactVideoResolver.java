package com.odialer.videoringtone;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Environment;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ContactVideoResolver {
    public static final String TAG = "ODialerVideoRingtone";
    public static final String FOLDER_REL_PATH = "Ringtones/VideoRingtones";
    public static final String CONFIG_FILE_NAME = "mappings.json";
    private static final String[] VIDEO_EXTENSIONS = {".mp4", ".mkv", ".webm", ".mov", ".3gp"};

    public static class ContactMapping {
        public String contactId = "";
        public String lookupKey = "";
        public String displayName = "";
        public String phoneNumber = "";
        public String normalizedNumber = "";
        public String videoUri = "";
        public String videoPath = "";
        public String originalVideoPath = "";
        public boolean vignetteEnabled = true;
        public int displayMode = VideoProcessor.MODE_FULL_SCREEN;
        public String savedCustomRingtone = "";
        public boolean fromFolderAuto = false;

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("contactId", contactId);
            o.put("lookupKey", lookupKey);
            o.put("displayName", displayName);
            o.put("phoneNumber", phoneNumber);
            o.put("normalizedNumber", normalizedNumber);
            o.put("videoUri", videoUri);
            o.put("videoPath", videoPath);
            o.put("originalVideoPath", originalVideoPath);
            o.put("vignetteEnabled", vignetteEnabled);
            o.put("displayMode", displayMode);
            o.put("savedCustomRingtone", savedCustomRingtone);
            return o;
        }

        public static ContactMapping fromJson(JSONObject o) {
            ContactMapping m = new ContactMapping();
            m.contactId = o.optString("contactId", "");
            m.lookupKey = o.optString("lookupKey", "");
            m.displayName = o.optString("displayName", "");
            m.phoneNumber = o.optString("phoneNumber", "");
            m.normalizedNumber = o.optString("normalizedNumber", normalizeNumber(m.phoneNumber));
            m.videoUri = o.optString("videoUri", "");
            m.videoPath = o.optString("videoPath", "");
            m.originalVideoPath = o.optString("originalVideoPath", "");
            m.vignetteEnabled = o.optBoolean("vignetteEnabled", true);
            m.displayMode = o.optInt("displayMode", VideoProcessor.MODE_FULL_SCREEN);
            m.savedCustomRingtone = o.optString("savedCustomRingtone", "");
            return m;
        }
    }

    public static class ConfigData {
        public String defaultRingtoneSim1 = "";
        public String defaultRingtoneSim2 = "";
        public String lastRestoredContactId = "";
        public String lastRestoredCustomRingtone = "";
        public boolean globalVignetteEnabled = true;
        public int globalDisplayMode = VideoProcessor.MODE_FULL_SCREEN;
        public List<ContactMapping> mappings = new ArrayList<>();
    }

    public static File getVideoRingtonesDir() {
        File dir = new File(Environment.getExternalStorageDirectory(), FOLDER_REL_PATH);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File getConfigFile() {
        return new File(getVideoRingtonesDir(), CONFIG_FILE_NAME);
    }

    public static String normalizeNumber(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static boolean numbersMatch(String numA, String numB) {
        String a = normalizeNumber(numA);
        String b = normalizeNumber(numB);
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        if (a.length() >= 10 && b.length() >= 10) {
            String tailA = a.substring(a.length() - 10);
            String tailB = b.substring(b.length() - 10);
            return tailA.equals(tailB);
        }
        if (a.length() >= 7 && b.length() >= 7) {
            String tailA = a.substring(a.length() - 7);
            String tailB = b.substring(b.length() - 7);
            return tailA.equals(tailB);
        }
        return false;
    }

    private static final String PREFS_NAME = "odialer_video_ringtone_prefs";
    private static final String PREF_KEY_CONFIG_JSON = "config_json_backup";

    public static synchronized ConfigData loadConfig(Context context) {
        ConfigData data = new ConfigData();
        File file = getConfigFile();
        String jsonStr = null;
        boolean externalReadFailed = false;

        if (file.exists()) {
            try (FileInputStream fis = new FileInputStream(file);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                jsonStr = sb.toString();
            } catch (Exception e) {
                externalReadFailed = true;
                Log.w(TAG, "Could not read external mappings.json yet (permissions?)", e);
            }
        }

        // Fall back to SharedPreferences backup if external file couldn't be read or didn't exist
        if (TextUtils.isEmpty(jsonStr) && context != null) {
            try {
                jsonStr = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(PREF_KEY_CONFIG_JSON, null);
            } catch (Exception ignored) {
            }
        }

        if (!TextUtils.isEmpty(jsonStr)) {
            try {
                JSONObject root = new JSONObject(jsonStr);
                data.defaultRingtoneSim1 = root.optString("defaultRingtoneSim1", "");
                data.defaultRingtoneSim2 = root.optString("defaultRingtoneSim2", "");
                data.lastRestoredContactId = root.optString("lastRestoredContactId", "");
                data.lastRestoredCustomRingtone = root.optString("lastRestoredCustomRingtone", "");
                data.globalVignetteEnabled = root.optBoolean("globalVignetteEnabled", true);
                data.globalDisplayMode = root.optInt("globalDisplayMode", VideoProcessor.MODE_FULL_SCREEN);
                JSONArray arr = root.optJSONArray("mappings");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.optJSONObject(i);
                        if (item != null) {
                            data.mappings.add(ContactMapping.fromJson(item));
                        }
                    }
                }
                // Mirror into SharedPreferences if we read from external file
                if (context != null && !externalReadFailed) {
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putString(PREF_KEY_CONFIG_JSON, jsonStr)
                            .apply();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to parse mappings config JSON", e);
            }
        }

        if (context != null && !externalReadFailed) {
            ensureDefaultAudioRingtoneSaved(context, data);
        }
        return data;
    }

    public static synchronized void saveConfig(ConfigData data) {
        saveConfig(null, data);
    }

    public static synchronized void saveConfig(Context context, ConfigData data) {
        try {
            JSONObject root = new JSONObject();
            root.put("defaultRingtoneSim1", data.defaultRingtoneSim1 == null ? "" : data.defaultRingtoneSim1);
            root.put("defaultRingtoneSim2", data.defaultRingtoneSim2 == null ? "" : data.defaultRingtoneSim2);
            root.put("lastRestoredContactId", data.lastRestoredContactId == null ? "" : data.lastRestoredContactId);
            root.put("lastRestoredCustomRingtone", data.lastRestoredCustomRingtone == null ? "" : data.lastRestoredCustomRingtone);
            root.put("globalVignetteEnabled", data.globalVignetteEnabled);
            root.put("globalDisplayMode", data.globalDisplayMode);
            JSONArray arr = new JSONArray();
            for (ContactMapping m : data.mappings) {
                if (!m.fromFolderAuto) {
                    arr.put(m.toJson());
                }
            }
            root.put("mappings", arr);
            String serialized = root.toString(2);

            if (context != null) {
                try {
                    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putString(PREF_KEY_CONFIG_JSON, serialized)
                            .apply();
                } catch (Exception ignored) {
                }
            }

            File file = getConfigFile();
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(serialized.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save mappings.json", e);
        }
    }

    public static void ensureDefaultAudioRingtoneSaved(Context context, ConfigData data) {
        try {
            ContentResolver cr = context.getContentResolver();
            String currentSim1 = Settings.System.getString(cr, "ringtone");
            String currentSim2 = Settings.System.getString(cr, "ringtone_sim2");
            boolean changed = false;
            if (TextUtils.isEmpty(data.defaultRingtoneSim1) && !TextUtils.isEmpty(currentSim1)
                    && !isOplusVideoRingtoneUri(currentSim1)) {
                data.defaultRingtoneSim1 = currentSim1;
                changed = true;
            }
            if (TextUtils.isEmpty(data.defaultRingtoneSim2) && !TextUtils.isEmpty(currentSim2)
                    && !isOplusVideoRingtoneUri(currentSim2)) {
                data.defaultRingtoneSim2 = currentSim2;
                changed = true;
            }
            if (changed) {
                saveConfig(context, data);
            }
        } catch (Exception e) {
            Log.w(TAG, "ensureDefaultAudioRingtoneSaved error", e);
        }
    }

    /**
     * Exact check performed by Phone.apk's LocalVideoUtils.d(String):
     * Strips userId@ from authority and checks startsWith("content://media/external_primary/video").
     */
    public static boolean isOplusVideoRingtoneUri(String uriStr) {
        if (TextUtils.isEmpty(uriStr)) return false;
        try {
            Uri uri = Uri.parse(uriStr);
            String auth = uri.getAuthority();
            if (auth != null && auth.contains("@")) {
                auth = auth.substring(auth.lastIndexOf('@') + 1);
                uri = uri.buildUpon().authority(auth).build();
            }
            return uri.toString().startsWith("content://media/external_primary/video");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Exact check performed by Phone.apk's LocalVideoUtils.f(Uri):
     * Queries ContentResolver for _data and verifies it is non-empty and not in .trash.
     */
    public static boolean isOplusLocalVideoValid(Context context, String uriStr) {
        if (!isOplusVideoRingtoneUri(uriStr)) return false;
        try (Cursor c = context.getContentResolver().query(
                Uri.parse(uriStr), new String[]{"_data"}, null, null, null)) {
            if (c != null && c.moveToNext()) {
                int idx = c.getColumnIndex("_data");
                if (idx >= 0) {
                    String path = c.getString(idx);
                    return !TextUtils.isEmpty(path) && !path.contains(".trash");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "isOplusLocalVideoValid error", e);
        }
        return false;
    }

    /**
     * Resolves a local File or arbitrary content:// Uri into a canonical
     * content://media/external_primary/video/media/<id> URI required by Phone.apk.
     */
    public static String resolveToExternalPrimaryVideoUri(Context context, File videoFile, Uri fallbackUri) {
        ContentResolver cr = context.getContentResolver();
        Uri externalPrimaryVideo = MediaStore.Video.Media.getContentUri("external_primary");

        // 1. If fallbackUri is already a valid external_primary video URI, check it first
        if (fallbackUri != null) {
            String s = fallbackUri.toString();
            if (isOplusLocalVideoValid(context, s)) {
                return s;
            }
            // Handle com.android.providers.media.documents video:<id>
            String docId = fallbackUri.getLastPathSegment();
            if (docId != null && docId.startsWith("video:")) {
                String idStr = docId.substring("video:".length());
                try {
                    long id = Long.parseLong(idStr);
                    Uri candidate = ContentUris.withAppendedId(externalPrimaryVideo, id);
                    if (isOplusLocalVideoValid(context, candidate.toString())) {
                        return candidate.toString();
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            // Handle content://media/external/video/media/<id>
            if (s.startsWith("content://media/external/video/media/")) {
                String candidate = s.replace("content://media/external/video/media/",
                        "content://media/external_primary/video/media/");
                if (isOplusLocalVideoValid(context, candidate)) {
                    return candidate;
                }
            }
        }

        // 2. If we have a local file, query MediaStore external_primary by _data or _display_name
        if (videoFile != null && videoFile.exists()) {
            String absPath = videoFile.getAbsolutePath();
            try (Cursor c = cr.query(
                    externalPrimaryVideo,
                    new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DATA},
                    MediaStore.Video.Media.DATA + "=?",
                    new String[]{absPath},
                    null)) {
                if (c != null && c.moveToFirst()) {
                    long id = c.getLong(0);
                    return ContentUris.withAppendedId(externalPrimaryVideo, id).toString();
                }
            } catch (Exception e) {
                Log.w(TAG, "Query by _data failed", e);
            }

            // Trigger synchronous MediaScanner scan so MediaStore indexes the file into external_primary
            try {
                final CountDownLatch latch = new CountDownLatch(1);
                final String[] scannedUri = new String[1];
                MediaScannerConnection.scanFile(
                        context,
                        new String[]{absPath},
                        new String[]{"video/mp4"},
                        (path, uri) -> {
                            if (uri != null) {
                                scannedUri[0] = uri.toString();
                            }
                            latch.countDown();
                        });
                latch.await(2500, TimeUnit.MILLISECONDS);
                if (!TextUtils.isEmpty(scannedUri[0])) {
                    String u = scannedUri[0].replace(
                            "content://media/external/video/media/",
                            "content://media/external_primary/video/media/");
                    if (isOplusVideoRingtoneUri(u)) {
                        return u;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "MediaScannerConnection failed", e);
            }

            // Query again after scan by display name & size
            try (Cursor c = cr.query(
                    externalPrimaryVideo,
                    new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DATA},
                    MediaStore.Video.Media.DISPLAY_NAME + "=?",
                    new String[]{videoFile.getName()},
                    MediaStore.Video.Media.DATE_ADDED + " DESC")) {
                if (c != null && c.moveToFirst()) {
                    long id = c.getLong(0);
                    return ContentUris.withAppendedId(externalPrimaryVideo, id).toString();
                }
            } catch (Exception e) {
                Log.w(TAG, "Fallback query by _display_name failed", e);
            }
        }

        return fallbackUri != null ? fallbackUri.toString() : "";
    }

    /**
     * Overload for importAndAssignVideo using global/default options.
     */
    public static ContactMapping importAndAssignVideo(
            Context context,
            String contactId,
            String lookupKey,
            String displayName,
            String phoneNumber,
            Uri sourceVideoUri) throws Exception {
        ConfigData cfg = loadConfig(context);
        VideoProcessor.ProcessingOptions opts = new VideoProcessor.ProcessingOptions();
        opts.enableVignette = cfg.globalVignetteEnabled;
        opts.displayMode = cfg.globalDisplayMode;
        return importAndAssignVideo(context, contactId, lookupKey, displayName, phoneNumber, sourceVideoUri, opts, null);
    }

    /**
     * Copies a picked video URI, processes it with VideoProcessor for legibility & aspect ratio styling,
     * and indexes it in MediaStore so Phone.apk always has direct external_primary access.
     */
    public static ContactMapping importAndAssignVideo(
            Context context,
            String contactId,
            String lookupKey,
            String displayName,
            String phoneNumber,
            Uri sourceVideoUri,
            VideoProcessor.ProcessingOptions options,
            VideoProcessor.IntConsumer progressListener) throws Exception {

        File dir = getVideoRingtonesDir();
        String safeName = (displayName != null && !displayName.trim().isEmpty() ? displayName.trim() : "Contact")
                .replaceAll("[^a-zA-Z0-9_\\- ]", "_");
        String normNum = normalizeNumber(phoneNumber);
        String fileName = safeName + (normNum.isEmpty() ? "" : ("_" + normNum)) + ".mp4";
        File destFile = new File(dir, fileName);
        File rawFile = new File(dir, ".raw_" + fileName);

        // 1. Copy source stream to raw backup file
        try (InputStream in = context.getContentResolver().openInputStream(sourceVideoUri);
             OutputStream out = new FileOutputStream(rawFile)) {
            if (in == null) {
                throw new IllegalArgumentException("Cannot open input stream for selected video");
            }
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            out.flush();
        }

        // 2. Hardware transcode with VideoProcessor if options provided
        boolean procSuccess = false;
        if (options != null) {
            try {
                VideoProcessor.processVideoSync(context, Uri.fromFile(rawFile), destFile, options, progressListener);
                procSuccess = true;
            } catch (Exception e) {
                Log.w(TAG, "Hardware VideoProcessor failed; falling back to direct copy", e);
            }
        }

        if (!procSuccess) {
            copyFile(rawFile, destFile);
        }

        String mediaUri = resolveToExternalPrimaryVideoUri(context, destFile, sourceVideoUri);

        ConfigData config = loadConfig(context);
        // Update existing mapping for same contact or number if present
        ContactMapping target = null;
        for (ContactMapping existing : config.mappings) {
            if ((!TextUtils.isEmpty(contactId) && contactId.equals(existing.contactId))
                    || (!TextUtils.isEmpty(normNum) && numbersMatch(normNum, existing.normalizedNumber))) {
                target = existing;
                break;
            }
        }
        if (target == null) {
            target = new ContactMapping();
            config.mappings.add(target);
        }
        target.contactId = contactId != null ? contactId : "";
        target.lookupKey = lookupKey != null ? lookupKey : "";
        target.displayName = displayName != null ? displayName : "";
        target.phoneNumber = phoneNumber != null ? phoneNumber : "";
        target.normalizedNumber = normNum;
        target.videoPath = destFile.getAbsolutePath();
        target.originalVideoPath = rawFile.getAbsolutePath();
        target.vignetteEnabled = (options != null) ? options.enableVignette : true;
        target.displayMode = (options != null) ? options.displayMode : VideoProcessor.MODE_FULL_SCREEN;
        target.videoUri = mediaUri;
        target.fromFolderAuto = false;

        saveConfig(context, config);
        return target;
    }

    /**
     * Re-processes an existing contact's video ringtone with updated visual styling (e.g. toggling vignette or ambient blur mode).
     */
    public static ContactMapping reprocessVideoMapping(
            Context context,
            ContactMapping target,
            VideoProcessor.ProcessingOptions options,
            VideoProcessor.IntConsumer progressListener) throws Exception {

        File dir = getVideoRingtonesDir();
        File sourceFile = null;
        if (!TextUtils.isEmpty(target.originalVideoPath) && new File(target.originalVideoPath).exists()) {
            sourceFile = new File(target.originalVideoPath);
        } else if (!TextUtils.isEmpty(target.videoPath) && new File(target.videoPath).exists()) {
            sourceFile = new File(target.videoPath);
        }

        if (sourceFile == null || !sourceFile.exists()) {
            throw new IOException("Source video file not found for reprocessing");
        }

        File tempOutput = new File(dir, "temp_proc_" + System.currentTimeMillis() + ".mp4");
        VideoProcessor.processVideoSync(context, Uri.fromFile(sourceFile), tempOutput, options, progressListener);

        File finalDest = new File(target.videoPath);
        if (finalDest.exists()) {
            finalDest.delete();
        }
        if (!tempOutput.renameTo(finalDest)) {
            copyFile(tempOutput, finalDest);
            tempOutput.delete();
        }

        String mediaUri = resolveToExternalPrimaryVideoUri(context, finalDest, null);
        target.videoUri = mediaUri;
        target.vignetteEnabled = options.enableVignette;
        target.displayMode = options.displayMode;

        ConfigData config = loadConfig(context);
        for (int i = 0; i < config.mappings.size(); i++) {
            if (config.mappings.get(i).contactId.equals(target.contactId)) {
                config.mappings.set(i, target);
                break;
            }
        }
        saveConfig(context, config);
        return target;
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int r;
            while ((r = in.read(buf)) != -1) {
                out.write(buf, 0, r);
            }
            out.flush();
        }
    }

    /**
     * Scans /sdcard/Ringtones/VideoRingtones/ for standalone video files dropped by the user
     * (e.g. "John Doe.mp4", "9876543210.mp4", or "Name_9876543210.mp4") that aren't already in mappings.json.
     */
    public static List<ContactMapping> getAllMappingsWithFolderScan(Context context) {
        ConfigData config = loadConfig(context);
        List<ContactMapping> result = new ArrayList<>(config.mappings);

        File dir = getVideoRingtonesDir();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                boolean isVideo = false;
                String baseName = name;
                for (String ext : VIDEO_EXTENSIONS) {
                    if (lower.endsWith(ext)) {
                        isVideo = true;
                        baseName = name.substring(0, name.length() - ext.length());
                        break;
                    }
                }
                if (!isVideo) continue;

                boolean alreadyMapped = false;
                for (ContactMapping m : config.mappings) {
                    if (f.getAbsolutePath().equalsIgnoreCase(m.videoPath)) {
                        alreadyMapped = true;
                        break;
                    }
                }
                if (alreadyMapped) continue;

                ContactMapping auto = new ContactMapping();
                auto.fromFolderAuto = true;
                auto.videoPath = f.getAbsolutePath();
                auto.videoUri = resolveToExternalPrimaryVideoUri(context, f, null);
                String digits = normalizeNumber(baseName);
                int lastUnderscore = baseName.lastIndexOf('_');
                String suffixDigits = lastUnderscore > 0 ? normalizeNumber(baseName.substring(lastUnderscore + 1)) : "";

                if (digits.length() >= 7 && digits.length() == baseName.replaceAll("[+\\-() ]", "").length()) {
                    auto.phoneNumber = baseName;
                    auto.normalizedNumber = digits;
                    auto.displayName = "Phone: " + baseName;
                } else if (lastUnderscore > 0 && suffixDigits.length() >= 7
                        && suffixDigits.length() == baseName.substring(lastUnderscore + 1).length()) {
                    auto.displayName = baseName.substring(0, lastUnderscore).replace('_', ' ');
                    auto.phoneNumber = suffixDigits;
                    auto.normalizedNumber = suffixDigits;
                } else {
                    auto.displayName = baseName;
                }
                result.add(auto);
            }
        }
        return result;
    }

    public static class CallerContactInfo {
        public String contactId = "";
        public String lookupKey = "";
        public String displayName = "";
        public String customRingtone = "";
    }

    public static CallerContactInfo lookupContactByNumber(Context context, String rawNumber) {
        CallerContactInfo info = new CallerContactInfo();
        if (TextUtils.isEmpty(rawNumber)) return info;
        Uri uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(rawNumber));
        String[] proj = new String[]{
                ContactsContract.PhoneLookup.CONTACT_ID,
                ContactsContract.PhoneLookup.LOOKUP_KEY,
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.CUSTOM_RINGTONE
        };
        try (Cursor c = context.getContentResolver().query(uri, proj, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                info.contactId = String.valueOf(c.getLong(0));
                info.lookupKey = c.getString(1) != null ? c.getString(1) : "";
                info.displayName = c.getString(2) != null ? c.getString(2) : "";
                info.customRingtone = c.getString(3) != null ? c.getString(3) : "";
            }
        } catch (Exception e) {
            Log.w(TAG, "lookupContactByNumber error", e);
        }
        return info;
    }

    /**
     * Resolves the video ringtone mapping (if any) for an incoming caller.
     */
    public static ContactMapping resolveForCaller(Context context, String rawNumber, String knownDisplayName) {
        CallerContactInfo cInfo = lookupContactByNumber(context, rawNumber);
        String effectiveName = !TextUtils.isEmpty(cInfo.displayName) ? cInfo.displayName : knownDisplayName;
        List<ContactMapping> all = getAllMappingsWithFolderScan(context);

        for (ContactMapping m : all) {
            boolean match = false;
            if (!TextUtils.isEmpty(cInfo.contactId) && cInfo.contactId.equals(m.contactId)) {
                match = true;
            } else if (!TextUtils.isEmpty(rawNumber) && !TextUtils.isEmpty(m.normalizedNumber)
                    && numbersMatch(rawNumber, m.normalizedNumber)) {
                match = true;
            } else if (!TextUtils.isEmpty(effectiveName) && !TextUtils.isEmpty(m.displayName)) {
                String cleanTarget = effectiveName.trim();
                String cleanMap = m.displayName.trim();
                if (cleanTarget.equalsIgnoreCase(cleanMap)) {
                    match = true;
                }
            }
            if (match) {
                // Ensure videoUri is resolved and valid
                if (!isOplusLocalVideoValid(context, m.videoUri) && !TextUtils.isEmpty(m.videoPath)) {
                    File f = new File(m.videoPath);
                    if (f.exists()) {
                        m.videoUri = resolveToExternalPrimaryVideoUri(context, f, null);
                    }
                }
                if (isOplusLocalVideoValid(context, m.videoUri)) {
                    return m;
                }
            }
        }
        return null;
    }

    /**
     * Called synchronously by VideoRingtoneScreeningService (before Telecom starts ringing or notifies Phone.apk).
     */
    public static synchronized boolean handleIncomingCall(Context context, String rawNumber) {
        if (!Settings.System.canWrite(context)) {
            Log.w(TAG, "WRITE_SETTINGS not granted; cannot switch ringtone");
            return false;
        }
        ConfigData config = loadConfig(context);
        CallerContactInfo cInfo = lookupContactByNumber(context, rawNumber);
        ContactMapping matched = resolveForCaller(context, rawNumber, cInfo.displayName);
        ContentResolver cr = context.getContentResolver();

        if (matched != null && !TextUtils.isEmpty(matched.videoUri)) {
            Log.i(TAG, "Matched per-contact video ringtone for " + rawNumber
                    + " (" + cInfo.displayName + ") -> " + matched.videoUri);

            // If this contact has a custom audio ringtone in Contacts, save & clear it during the call
            // so Telecom plays the video's audio track rather than the audio ringtone.
            if (!TextUtils.isEmpty(cInfo.contactId) && !TextUtils.isEmpty(cInfo.customRingtone)
                    && !cInfo.customRingtone.equals(matched.videoUri)) {
                try {
                    config.lastRestoredContactId = cInfo.contactId;
                    config.lastRestoredCustomRingtone = cInfo.customRingtone;
                    saveConfig(context, config);
                    ContentValues cv = new ContentValues();
                    cv.putNull(ContactsContract.Contacts.CUSTOM_RINGTONE);
                    cr.update(
                            ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, Long.parseLong(cInfo.contactId)),
                            cv, null, null);
                } catch (Exception e) {
                    Log.w(TAG, "Could not temporarily clear contact CUSTOM_RINGTONE", e);
                }
            }

            Settings.System.putString(cr, "ringtone", matched.videoUri);
            Settings.System.putString(cr, "ringtone_sim2", matched.videoUri);
            return true;
        } else {
            Log.i(TAG, "No video ringtone for " + rawNumber + "; restoring default audio ringtone");
            restoreDefaultAudioRingtone(context);
            return false;
        }
    }

    public static synchronized void restoreDefaultAudioRingtone(Context context) {
        if (!Settings.System.canWrite(context)) return;
        ConfigData config = loadConfig(context);
        ContentResolver cr = context.getContentResolver();

        // Restore any temporarily cleared contact custom audio ringtone
        if (!TextUtils.isEmpty(config.lastRestoredContactId)
                && !TextUtils.isEmpty(config.lastRestoredCustomRingtone)) {
            try {
                ContentValues cv = new ContentValues();
                cv.put(ContactsContract.Contacts.CUSTOM_RINGTONE, config.lastRestoredCustomRingtone);
                cr.update(
                        ContentUris.withAppendedId(
                                ContactsContract.Contacts.CONTENT_URI,
                                Long.parseLong(config.lastRestoredContactId)),
                        cv, null, null);
            } catch (Exception e) {
                Log.w(TAG, "Failed to restore contact CUSTOM_RINGTONE", e);
            }
            config.lastRestoredContactId = "";
            config.lastRestoredCustomRingtone = "";
            saveConfig(context, config);
        }

        String sim1 = config.defaultRingtoneSim1;
        String sim2 = config.defaultRingtoneSim2;
        if (TextUtils.isEmpty(sim1) || isOplusVideoRingtoneUri(sim1)) {
            Uri def = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE);
            if (def != null && !isOplusVideoRingtoneUri(def.toString())) {
                sim1 = def.toString();
            }
        }
        if (TextUtils.isEmpty(sim2) || isOplusVideoRingtoneUri(sim2)) {
            sim2 = sim1;
        }
        if (!TextUtils.isEmpty(sim1)) {
            Settings.System.putString(cr, "ringtone", sim1);
        }
        if (!TextUtils.isEmpty(sim2)) {
            Settings.System.putString(cr, "ringtone_sim2", sim2);
        }
    }
}
