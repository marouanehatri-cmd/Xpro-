package com.xpro.lite;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Shared helper: every download goes to Download/XPro_Lite/ through DownloadManager. */
final class Downloads {

    static final String SUBDIR = "XPro_Lite";

    /** Ids of downloads started by this app (MainActivity's receiver reports them). */
    static final Set<Long> ACTIVE = ConcurrentHashMap.newKeySet();

    private Downloads() { }

    static boolean isTwimgHttps(Uri u) {
        if (u == null || !"https".equals(u.getScheme())) return false;
        String host = u.getHost();
        return host != null && (host.equals("twimg.com") || host.endsWith(".twimg.com"));
    }

    static boolean isMp4Url(Uri u) {
        String path = u == null ? null : u.getPath();
        return isTwimgHttps(u) && path != null && path.endsWith(".mp4");
    }

    /** Returns null on success, otherwise a short error message for a Toast. */
    @SuppressWarnings("deprecation")
    static String enqueue(Context c, String url, String fileName, String mime, String userAgent) {
        try {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                    && c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                return "Storage permission needed";
            }
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(url));
            req.setTitle(fileName);
            req.setDescription("X Pro Lite");
            req.setMimeType(mime);
            if (userAgent != null) req.addRequestHeader("User-Agent", userAgent);
            req.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS, SUBDIR + "/" + fileName);
            DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
            ACTIVE.add(dm.enqueue(req));
            return null;
        } catch (Exception e) {
            return "Download failed: " + e.getMessage();
        }
    }
}
