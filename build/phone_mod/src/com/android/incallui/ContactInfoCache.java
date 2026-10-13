package com.android.incallui;
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
