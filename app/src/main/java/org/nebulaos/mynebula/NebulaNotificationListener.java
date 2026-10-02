package org.nebulaos.mynebula;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.app.Notification;
import android.content.Context;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

/**
 * Listens for all incoming phone notifications and mirrors them to the paired
 * NebulaOS laptop via POST /api/notify on the companion daemon.
 *
 * Requires the user to grant Notification Access permission in Android Settings.
 */
public class NebulaNotificationListener extends NotificationListenerService {

    // Packages to suppress (system noise, our own app)
    private static final Set<String> BLOCKED_PACKAGES = new HashSet<>();
    static {
        BLOCKED_PACKAGES.add("org.nebulaos.mynebula");
        BLOCKED_PACKAGES.add("android");
        BLOCKED_PACKAGES.add("com.android.systemui");
        BLOCKED_PACKAGES.add("com.android.phone");
        BLOCKED_PACKAGES.add("com.android.settings");
        BLOCKED_PACKAGES.add("com.google.android.gms");
    }

    private static final String PREFS = "mynebula_prefs";
    private static final String PREF_LAPTOP_IP = "last_seen_ip";   // written by MyNebulaService on beacon discovery
    private static final String PREF_IS_PAIRED = "is_paired";
    private static final int LAPTOP_PORT = 53317;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (BLOCKED_PACKAGES.contains(pkg)) return;

        // Skip ongoing / service notifications (e.g. music player progress bars)
        Notification n = sbn.getNotification();
        if (n == null) return;
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
        if ((n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return;

        // Extract title and text
        String title = "";
        String text = "";
        Bundle extras = n.extras;
        if (extras != null) {
            CharSequence t = extras.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence b = extras.getCharSequence(Notification.EXTRA_TEXT);
            if (t != null) title = t.toString();
            if (b != null) text = b.toString();
        }

        // Get a human-readable app name
        String appLabel = pkg;
        try {
            appLabel = getPackageManager()
                    .getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0))
                    .toString();
        } catch (Exception ignored) {}

        // Forward to laptop (fire-and-forget on background thread)
        final String fTitle = title;
        final String fText = text;
        final String fApp = appLabel;
        new Thread(() -> forwardToLaptop(fApp, fTitle, fText)).start();
    }

    private void forwardToLaptop(String app, String title, String text) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!prefs.getBoolean(PREF_IS_PAIRED, false)) return;
            String laptopIp = prefs.getString(PREF_LAPTOP_IP, null);
            if (laptopIp == null || laptopIp.isEmpty()) return;

            JSONObject payload = new JSONObject();
            payload.put("app", app);
            payload.put("title", title);
            payload.put("text", text);

            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            URL url = new URL("http://" + laptopIp + ":" + LAPTOP_PORT + "/api/notify");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Content-Length", String.valueOf(body.length));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            conn.getResponseCode(); // triggers the request
            conn.disconnect();
        } catch (Exception ignored) {
            // Silently ignore — laptop may be offline
        }
    }
}
