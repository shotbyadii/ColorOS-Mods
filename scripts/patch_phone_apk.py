import hashlib
import os
import shutil
import struct
import subprocess
import zipfile
import zlib

ROOT_DIR = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ORIG_APK = r"C:\Users\anush\Downloads\Mobile Devices\Phone.apk"
BUILD_DIR = os.path.join(ROOT_DIR, "build", "phone_mod")
DIST_DIR = os.path.join(ROOT_DIR, "dist")
KEYSTORE = os.path.join(ROOT_DIR, "build", "release.keystore")

SDK_DIR = os.path.join(os.environ["LOCALAPPDATA"], "Android", "Sdk")
BUILD_TOOLS = os.path.join(SDK_DIR, "build-tools", "36.0.0")
ANDROID_JAR = os.path.join(SDK_DIR, "platforms", "android-36", "android.jar")
D8_BAT = os.path.join(BUILD_TOOLS, "d8.bat")
ZIPALIGN_EXE = os.path.join(BUILD_TOOLS, "zipalign.exe")
APKSIGNER_BAT = os.path.join(BUILD_TOOLS, "apksigner.bat")
DEXDUMP_EXE = os.path.join(BUILD_TOOLS, "dexdump.exe")


def update_dex_headers(dex_bytes: bytearray) -> bytes:
    """Recompute SHA-1 signature (offset 12..32) and Adler32 checksum (offset 8..12)."""
    sha1 = hashlib.sha1(dex_bytes[32:]).digest()
    dex_bytes[12:32] = sha1
    checksum = zlib.adler32(dex_bytes[12:]) & 0xFFFFFFFF
    struct.pack_into("<I", dex_bytes, 8, checksum)
    return bytes(dex_bytes)


def patch_classes_dex(dex_path: str) -> bytes:
    """
    Patch classes.dex:
    In VideoBackgroundUseCase$mVideoBackgroundType$1.a() (code_off = 0x2c5a08):
    Move the Lxa/a;.e() check to the start of the incoming-call branch [003a..0041]
    using register v0, shift [003a..0050] down by 8 words to [0042..0058] with
    adjusted goto-0072 offsets, and nop out the 6-word Lj2/e;.a check at [0059..005e].
    """
    data = bytearray(open(dex_path, "rb").read())
    code_off = 0x2C5A08
    registers_size, ins_size, outs_size, tries_size, debug_info_off, insns_size = struct.unpack_from(
        "<HHHHII", data, code_off
    )
    assert insns_size == 119, f"Unexpected insns_size: {insns_size}"

    insns_start = code_off + 16
    words = list(struct.unpack_from(f"<{insns_size}H", data, insns_start))

    # Verify original instructions at key offsets before patching
    assert words[0x37] == 0x0039 and words[0x38] == 0x0003, "Unexpected instruction at 0x37"
    assert words[0x57] == 0x0071 and words[0x58] == 0xCA8C, "Unexpected Lxa/a;.e() call at 0x57"

    # Extract original [003a..0050] (23 words)
    orig_block = list(words[0x3A:0x51])
    assert len(orig_block) == 23

    # Adjust the three `goto 0072` instructions inside orig_block (indices 7, 10, 22) by -8 words
    # Original indices: 0x41 (idx 7), 0x44 (idx 10), 0x50 (idx 22)
    assert orig_block[0x41 - 0x3A] == 0x3128
    assert orig_block[0x44 - 0x3A] == 0x2E28
    assert orig_block[0x50 - 0x3A] == 0x2228
    orig_block[0x41 - 0x3A] = 0x2928  # 0x49 + 0x29 = 0x72
    orig_block[0x44 - 0x3A] = 0x2628  # 0x4c + 0x26 = 0x72
    orig_block[0x50 - 0x3A] = 0x1A28  # 0x58 + 0x1a = 0x72

    # New 8-word Lxa/a;.e() check at [003a..0041] using v0
    new_check = [
        0x0071,
        0xCA8C,
        0x0000,  # invoke-static {}, Lxa/a;.e()Z
        0x000A,  # move-result v0
        0x0038,
        0x0004,  # if-eqz v0, +4 (-> 0x42)
        0x3312,  # const/4 v3, #int 3 (LOCAL_VIDEO)
        0x3128,  # goto +0x31 (0x41 + 0x31 = 0x72)
    ]

    # 6 words of nop at [0059..005e]
    nops = [0x0000] * 6

    patched_region = new_check + orig_block + nops
    assert len(patched_region) == (0x5F - 0x3A)

    words[0x3A:0x5F] = patched_region

    # Clear debug_info_off for this method so line/local tables don't mismatch shifted instructions
    struct.pack_into("<I", data, code_off + 8, 0)
    struct.pack_into(f"<{insns_size}H", data, insns_start, *words)

    return update_dex_headers(data)


def patch_classes2_dex(dex_path: str) -> bytes:
    """
    Patch classes2.dex:
    1. Rename class descriptor string 'Lxa/a;' -> 'Lxa/z;' in the string table so
       our custom Lxa/a; in classes3.dex takes precedence while original methods remain
       accessible at Lxa/z;.
    2. In Lwa/g;.<init> (VideoShowControllerImpl, code_off = 0x383f30), change
       `const/4 v4, 1` (0x12 0x14) before `iput-boolean v4, v2, Lwa/g;.d:Z` (mVideoOnly)
       to `const/4 v4, 0` (0x12 0x04) so the video's embedded audio track plays on STREAM_RING.
    """
    data = bytearray(open(dex_path, "rb").read())

    # 1. Rename 'Lxa/a;' -> 'Lxa/z;' in string table
    string_ids_size, string_ids_off = struct.unpack_from("<II", data, 56)
    renamed = False
    for idx in range(string_ids_size):
        s_off = struct.unpack_from("<I", data, string_ids_off + idx * 4)[0]
        # Expect uleb128 length = 6, followed by b"Lxa/a;\x00"
        if data[s_off : s_off + 8] == b"\x06Lxa/a;\x00":
            data[s_off : s_off + 8] = b"\x06Lxa/z;\x00"
            renamed = True
            print(f"Renamed string id {idx} at 0x{s_off:x} from 'Lxa/a;' to 'Lxa/z;'")
            break
    assert renamed, "Could not find 'Lxa/a;' in classes2.dex string table"

    # 2. Patch Lwa/g;.<init> mVideoOnly = false
    code_off = 0x383F30
    insns_start = code_off + 16
    target_off = insns_start + 30  # instruction word [000f] before [0010] iput-boolean
    assert data[target_off : target_off + 6] == bytes.fromhex("12145c24617a"), (
        f"Unexpected bytes at Lwa/g;.<init>: {data[target_off:target_off+6].hex()}"
    )
    data[target_off : target_off + 2] = bytes.fromhex("1204")  # const/4 v4, 0
    print(f"Patched Lwa/g;.<init> mVideoOnly=false at 0x{target_off:x}")

    return update_dex_headers(data)


def write_classes3_sources(src_dir: str):
    """Write stub classes for compile-time linking and custom Lxa/a; implementation."""
    # 1. Stubs (excluded from classes3.dex after javac)
    stubs = {
        "com/android/incallui/OplusInCallApp.java": """package com.android.incallui;
import android.content.Context;
public class OplusInCallApp {
    public static Context getAppContext() { return null; }
}
""",
        "com/android/incallui/ContactInfoCache.java": """package com.android.incallui;
import android.net.Uri;
public class ContactInfoCache {
    public static class ContactCacheEntry {
        public String name;
        public String number;
        public String lookupKey;
        public Uri contactUri;
        public Uri lookupUri;
    }
}
""",
        "com/android/incallui/Call.java": """package com.android.incallui;
public class Call {
    public String getNumber() { return null; }
    public int getSlotId() { return 0; }
    public ContactInfoCache.ContactCacheEntry getContactInfo() { return null; }
}
""",
        "com/android/incallui/CallList.java": """package com.android.incallui;
public class CallList {
    public static CallList getInstance() { return null; }
    public Call getIncomingCall() { return null; }
    public Call getFirstCall() { return null; }
}
""",
        "xa/z.java": """package xa;
import android.net.Uri;
public final class z {
    public static final z a = new z();
    public static final String a(String s) { return null; }
    public static final String b() { return ""; }
    public static final Uri c(Uri u) { return null; }
    public static final boolean d(String s) { return false; }
    public static final boolean e() { return false; }
    public static final boolean f(Uri u) { return false; }
}
""",
    }

    # 2. Actual Lxa/a; implementation (packaged into classes3.dex)
    impl = {
        "xa/a.java": """package xa;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.android.incallui.Call;
import com.android.incallui.CallList;
import com.android.incallui.ContactInfoCache;
import com.android.incallui.OplusInCallApp;
import com.odialer.videoringtone.ContactVideoResolver;

import java.io.File;

/**
 * Patched replacement for com.android.incallui's LocalVideoUtils (Lxa/a;).
 * Resolves per-contact video ringtones for incoming callers and falls back to
 * default/assigned audio ringtone when no per-contact video is assigned.
 */
public final class a {
    private static final String TAG = "ODialerVideoMod";
    public static final a a = new a();

    private a() {}

    public static final String a(String s) {
        return z.a(s);
    }

    /**
     * Returns the video ringtone URI string for the current incoming caller,
     * or empty string if the caller has no assigned video ringtone.
     */
    public static final String b() {
        try {
            String customUri = resolveCurrentCallerVideoUri();
            if (!TextUtils.isEmpty(customUri)) {
                Log.i(TAG, "Resolved per-contact video ringtone: " + customUri);
                return customUri;
            }
            if (!hasAnyConfiguredPerContactVideos(OplusInCallApp.getAppContext())) {
                return z.b();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in Lxa/a;.b()", t);
        }
        return "";
    }

    public static final Uri c(Uri u) {
        return z.c(u);
    }

    public static final boolean d(String s) {
        if (TextUtils.isEmpty(s)) {
            return false;
        }
        if (s.startsWith("content://") || s.startsWith("file://") || s.startsWith("/")) {
            return true;
        }
        return z.d(s);
    }

    /**
     * Returns true if the current incoming caller should play a local video ringtone.
     */
    public static final boolean e() {
        try {
            String customUri = resolveCurrentCallerVideoUri();
            if (!TextUtils.isEmpty(customUri)) {
                return true;
            }
            if (!hasAnyConfiguredPerContactVideos(OplusInCallApp.getAppContext())) {
                return z.e();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in Lxa/a;.e()", t);
        }
        return false;
    }

    public static final boolean f(Uri u) {
        if (u == null) {
            return false;
        }
        String scheme = u.getScheme();
        if ("file".equalsIgnoreCase(scheme)) {
            String path = u.getPath();
            return path != null && new File(path).exists();
        }
        return z.f(u);
    }

    private static boolean hasAnyConfiguredPerContactVideos(Context context) {
        try {
            return !ContactVideoResolver.getAllMappingsWithFolderScan(context).isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String resolveCurrentCallerVideoUri() {
        Context context = OplusInCallApp.getAppContext();
        if (context == null) {
            return null;
        }
        CallList callList = CallList.getInstance();
        if (callList == null) {
            return null;
        }
        Call call = callList.getIncomingCall();
        if (call == null) {
            call = callList.getFirstCall();
        }
        if (call == null) {
            return null;
        }

        String number = call.getNumber();
        String name = null;
        ContactInfoCache.ContactCacheEntry info = call.getContactInfo();
        if (info != null) {
            if (!TextUtils.isEmpty(info.name)) {
                name = info.name;
            }
            if (TextUtils.isEmpty(number) && !TextUtils.isEmpty(info.number)) {
                number = info.number;
            }
        }

        ContactVideoResolver.ContactMapping matched =
                ContactVideoResolver.resolveForCaller(context, number, name);
        if (matched != null) {
            if (!TextUtils.isEmpty(matched.videoUri)) {
                return matched.videoUri;
            }
            if (!TextUtils.isEmpty(matched.videoPath)) {
                File f = new File(matched.videoPath);
                if (f.exists()) {
                    return Uri.fromFile(f).toString();
                }
            }
        }
        return null;
    }
}
"""
    }

    for rel_path, content in {**stubs, **impl}.items():
        full_path = os.path.join(src_dir, rel_path)
        os.makedirs(os.path.dirname(full_path), exist_ok=True)
        with open(full_path, "w", encoding="utf-8") as f:
            f.write(content)


def build_classes3_dex() -> str:
    src_dir = os.path.join(BUILD_DIR, "src")
    cls_dir = os.path.join(BUILD_DIR, "classes")
    dex_dir = os.path.join(BUILD_DIR, "dex")
    for d in (src_dir, cls_dir, dex_dir):
        if os.path.exists(d):
            shutil.rmtree(d)
        os.makedirs(d, exist_ok=True)

    write_classes3_sources(src_dir)

    # Copy ContactVideoResolver.java from companion-app so classes3.dex is self-contained
    comp_resolver = os.path.join(
        ROOT_DIR, "companion-app", "src", "com", "odialer", "videoringtone", "ContactVideoResolver.java"
    )
    dst_resolver = os.path.join(src_dir, "com", "odialer", "videoringtone", "ContactVideoResolver.java")
    os.makedirs(os.path.dirname(dst_resolver), exist_ok=True)
    shutil.copy2(comp_resolver, dst_resolver)

    java_files = []
    for root, _, files in os.walk(src_dir):
        for f in files:
            if f.endswith(".java"):
                java_files.append(os.path.join(root, f))

    subprocess.run(
        ["javac", "--release", "11", "-cp", ANDROID_JAR, "-d", cls_dir] + java_files,
        check=True,
    )

    # Only package xa/a.class and com/odialer/videoringtone/* into classes3.dex (exclude stubs)
    dex_classes = [
        os.path.join(cls_dir, "xa", "a.class"),
    ]
    vr_dir = os.path.join(cls_dir, "com", "odialer", "videoringtone")
    for f in os.listdir(vr_dir):
        if f.endswith(".class"):
            dex_classes.append(os.path.join(vr_dir, f))

    subprocess.run(
        [D8_BAT, "--lib", ANDROID_JAR, "--output", dex_dir] + dex_classes,
        check=True,
    )
    return os.path.join(dex_dir, "classes.dex")


def main():
    os.makedirs(BUILD_DIR, exist_ok=True)
    os.makedirs(DIST_DIR, exist_ok=True)

    ext_dir = os.path.join(BUILD_DIR, "extracted")
    os.makedirs(ext_dir, exist_ok=True)
    with zipfile.ZipFile(ORIG_APK, "r") as zf:
        zf.extract("classes.dex", ext_dir)
        zf.extract("classes2.dex", ext_dir)

    print("1. Patching classes.dex...")
    patched_dex1 = patch_classes_dex(os.path.join(ext_dir, "classes.dex"))
    patched_dex1_path = os.path.join(BUILD_DIR, "classes.dex")
    with open(patched_dex1_path, "wb") as f:
        f.write(patched_dex1)

    print("2. Patching classes2.dex...")
    patched_dex2 = patch_classes2_dex(os.path.join(ext_dir, "classes2.dex"))
    patched_dex2_path = os.path.join(BUILD_DIR, "classes2.dex")
    with open(patched_dex2_path, "wb") as f:
        f.write(patched_dex2)

    print("3. Compiling classes3.dex...")
    classes3_path = build_classes3_dex()

    print("4. Repackaging Phone-mod.apk...")
    unaligned_apk = os.path.join(BUILD_DIR, "Phone-unaligned.apk")
    aligned_apk = os.path.join(BUILD_DIR, "Phone-aligned.apk")
    final_apk = os.path.join(DIST_DIR, "Phone-mod.apk")

    with zipfile.ZipFile(ORIG_APK, "r") as zin, zipfile.ZipFile(unaligned_apk, "w") as zout:
        for item in zin.infolist():
            fn = item.filename
            # Strip original META-INF signatures
            if fn.startswith("META-INF/") and (
                fn.endswith(".SF") or fn.endswith(".RSA") or fn.endswith(".DSA") or fn == "META-INF/MANIFEST.MF"
            ):
                continue
            if fn == "classes.dex":
                info = zipfile.ZipInfo("classes.dex")
                info.compress_type = item.compress_type
                zout.writestr(info, patched_dex1)
            elif fn == "classes2.dex":
                info = zipfile.ZipInfo("classes2.dex")
                info.compress_type = item.compress_type
                zout.writestr(info, patched_dex2)
            else:
                raw_data = zin.read(fn)
                info = zipfile.ZipInfo(fn)
                info.compress_type = item.compress_type
                info.external_attr = item.external_attr
                zout.writestr(info, raw_data)

        # Add classes3.dex
        info3 = zipfile.ZipInfo("classes3.dex")
        info3.compress_type = zipfile.ZIP_DEFLATED
        with open(classes3_path, "rb") as f3:
            zout.writestr(info3, f3.read())

    print("5. Aligning and signing Phone-mod.apk...")
    subprocess.run([ZIPALIGN_EXE, "-p", "-f", "4", unaligned_apk, aligned_apk], check=True)
    subprocess.run(
        [
            APKSIGNER_BAT,
            "sign",
            "--v1-signing-enabled",
            "true",
            "--v2-signing-enabled",
            "true",
            "--v3-signing-enabled",
            "true",
            "--ks",
            KEYSTORE,
            "--ks-key-alias",
            "odialer",
            "--ks-pass",
            "pass:android123",
            "--key-pass",
            "pass:android123",
            "--out",
            final_apk,
            aligned_apk,
        ],
        check=True,
    )

    print("6. Verifying Phone-mod.apk signature...")
    subprocess.run([APKSIGNER_BAT, "verify", "-v", final_apk], check=True)
    print(f"Successfully built {final_apk}")


if __name__ == "__main__":
    main()
