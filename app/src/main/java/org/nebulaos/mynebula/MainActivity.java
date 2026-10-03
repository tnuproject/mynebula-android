package org.nebulaos.mynebula;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import android.Manifest;
import android.content.pm.PackageManager;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private ValueCallback<Uri[]> fileUploadCallback;
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int SCREEN_CAPTURE_REQUEST = 1003;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        requestAppPermissions();
        requestIgnoreBatteryOptimizations();

        try {
            Intent serviceIntent = new Intent(this, MyNebulaService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        webView = findViewById(R.id.webview);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")) {
                    view.loadUrl(url);
                    return true;
                }
                return false;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                MainActivity.this.runOnUiThread(() -> {
                    request.grant(request.getResources());
                });
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                } catch (Exception e) {
                    fileUploadCallback = null;
                    return false;
                }
                return true;
            }
        });

        startUdpDiscoveryListener();

        Intent intent = getIntent();
        Uri data = intent != null ? intent.getData() : null;
        if (data != null && data.toString().contains(":53317")) {
            webView.loadUrl(data.toString());
        } else {
            webView.loadUrl("file:///android_asset/web/index.html");
        }
        checkMirrorIntent(intent);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        checkMirrorIntent(intent);
    }

    private void checkMirrorIntent(Intent intent) {
        if (intent != null && MyNebulaService.ACTION_ALLOW_MIRROR.equals(intent.getAction())) {
            android.app.NotificationManager manager = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.cancel(MyNebulaService.MIRROR_PROMPT_NOTIF_ID);

            new android.app.AlertDialog.Builder(this)
                    .setTitle("Allow Screen Sharing?")
                    .setMessage("NebulaOS PC is requesting permission to view your screen.")
                    .setPositiveButton("Allow", (d, w) -> {
                        android.media.projection.MediaProjectionManager mpm =
                                (android.media.projection.MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                        if (mpm != null) {
                            startActivityForResult(mpm.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST);
                        }
                    })
                    .setNegativeButton("Deny", (d, w) -> {
                        notifyMirrorResponse(false);
                    })
                    .setCancelable(false)
                    .show();
        }
    }

    private void notifyMirrorResponse(boolean allowed) {
        new Thread(() -> {
            try {
                SharedPreferences prefs = getSharedPreferences("mynebula_prefs", MODE_PRIVATE);
                String hostIp = prefs.getString("last_seen_ip", null);
                if (hostIp != null && !hostIp.isEmpty()) {
                    java.net.URL url = new java.net.URL("http://" + hostIp + ":53317/api/mirror/response");
                    java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(3000);
                    conn.setRequestProperty("Content-Type", "application/json");
                    byte[] data = ("{\"allowed\":" + allowed + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    try (java.io.OutputStream os = conn.getOutputStream()) {
                        os.write(data);
                    }
                    conn.getResponseCode();
                    conn.disconnect();
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void requestAppPermissions() {
        List<String> list = new ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                list.add(Manifest.permission.READ_MEDIA_IMAGES);
            }
            if (ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                list.add("android.permission.POST_NOTIFICATIONS");
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                list.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        if (!list.isEmpty()) {
            ActivityCompat.requestPermissions(this, list.toArray(new String[0]), 1002);
        }
    }

    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent intent = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } catch (Exception ignored) {}
        }
        // Also request Notification Listener access if not already granted
        requestNotificationListenerAccess();
    }

    private void requestNotificationListenerAccess() {
        try {
            android.content.ComponentName cn = new android.content.ComponentName(this, NebulaNotificationListener.class);
            String flat = android.provider.Settings.Secure.getString(
                    getContentResolver(), "enabled_notification_listeners");
            boolean enabled = flat != null && flat.contains(cn.flattenToString());
            if (!enabled) {
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Enable Notification Mirroring")
                    .setMessage("To mirror phone notifications on your NebulaOS laptop, grant MyNebula access to Notification Listener. Tap OK to open Settings.")
                    .setPositiveButton("Open Settings", (d, w) -> {
                        startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
                    })
                    .setNegativeButton("Not Now", null)
                    .show();
            }
        } catch (Exception ignored) {}
    }

    private void startUdpDiscoveryListener() {
        Thread thread = new Thread(() -> {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new java.net.InetSocketAddress(53318));
                socket.setBroadcast(true);
                byte[] buffer = new byte[2048];
                while (!isFinishing()) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String message = new String(packet.getData(), 0, packet.getLength());
                    if (message.contains("petaldrop_beacon")) {
                        runOnUiThread(() -> {
                            if (webView != null) {
                                webView.evaluateJavascript("if (typeof onPeerDiscovered === 'function') onPeerDiscovered(" + message + ");", null);
                            }
                        });
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST) {
            if (fileUploadCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    } else if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    }
                }
                fileUploadCallback.onReceiveValue(results);
                fileUploadCallback = null;
            }
        } else if (requestCode == SCREEN_CAPTURE_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                Intent mirrorIntent = new Intent(this, ScreenMirrorService.class);
                mirrorIntent.setAction(ScreenMirrorService.ACTION_START);
                mirrorIntent.putExtra(ScreenMirrorService.EXTRA_RESULT_CODE, resultCode);
                mirrorIntent.putExtra(ScreenMirrorService.EXTRA_RESULT_DATA, data);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(mirrorIntent);
                } else {
                    startService(mirrorIntent);
                }
                notifyMirrorResponse(true);
                Toast.makeText(this, "Screen mirroring started", Toast.LENGTH_SHORT).show();
                if (webView != null) {
                    webView.evaluateJavascript("if (typeof onScreenMirrorChanged === 'function') onScreenMirrorChanged(true);", null);
                }
            } else {
                notifyMirrorResponse(false);
                Toast.makeText(this, "Screen mirroring cancelled", Toast.LENGTH_SHORT).show();
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    public class WebAppInterface {
        @JavascriptInterface
        public void showToast(String toast) {
            Toast.makeText(MainActivity.this, toast, Toast.LENGTH_SHORT).show();
        }

        @JavascriptInterface
        public String getDeviceModel() {
            return android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL;
        }

        @JavascriptInterface
        public String getDeviceId() {
            return MyNebulaService.getOrGenerateDeviceId(MainActivity.this);
        }

        @JavascriptInterface
        public void savePairingState(String hostName, String hostIp, String secret) {
            SharedPreferences prefs = getSharedPreferences("mynebula_prefs", MODE_PRIVATE);
            prefs.edit()
                    .putBoolean("is_paired", true)
                    .putString("paired_device_name", hostName)
                    .putString("last_seen_ip", hostIp)
                    .putString("paired_secret", secret)
                    .commit(); // synchronous save
            showToast("Paired with " + hostName);

            // Notify foreground service to update notification immediately
            Intent serviceIntent = new Intent(MainActivity.this, MyNebulaService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }

        @JavascriptInterface
        public void clearPairingState() {
            SharedPreferences prefs = getSharedPreferences("mynebula_prefs", MODE_PRIVATE);
            prefs.edit()
                    .putBoolean("is_paired", false)
                    .remove("paired_device_name")
                    .remove("paired_secret")
                    .commit();
            showToast("Device unpaired");

            Intent serviceIntent = new Intent(MainActivity.this, MyNebulaService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        }

        @JavascriptInterface
        public String getSavedPairingState() {
            SharedPreferences prefs = getSharedPreferences("mynebula_prefs", MODE_PRIVATE);
            JSONObject obj = new JSONObject();
            try {
                obj.put("is_paired", prefs.getBoolean("is_paired", false));
                obj.put("host_name", prefs.getString("paired_device_name", ""));
                obj.put("host_ip", prefs.getString("last_seen_ip", ""));
                obj.put("secret", prefs.getString("paired_secret", ""));
                obj.put("device_id", MyNebulaService.getOrGenerateDeviceId(MainActivity.this));
            } catch (Exception ignored) {}
            return obj.toString();
        }

        @JavascriptInterface
        public boolean isGallerySyncEnabled() {
            return GallerySyncManager.isSyncEnabled(MainActivity.this);
        }

        @JavascriptInterface
        public void setGallerySyncEnabled(boolean enabled) {
            if (enabled) {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.READ_MEDIA_IMAGES}, 1002);
                    }
                } else {
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 1002);
                    }
                }
            }
            GallerySyncManager.setSyncEnabled(MainActivity.this, enabled);
            showToast(enabled ? "Gallery sync enabled" : "Gallery sync disabled");
        }

        @JavascriptInterface
        public void startScreenMirror() {
            runOnUiThread(() -> {
                SharedPreferences prefs = getSharedPreferences("mynebula_prefs", MODE_PRIVATE);
                String host = prefs.getString("last_seen_ip", null);
                if (host == null || host.isEmpty()) {
                    showToast("Connect to NebulaOS first");
                    return;
                }

                android.media.projection.MediaProjectionManager mpm =
                        (android.media.projection.MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                if (mpm != null) {
                    startActivityForResult(mpm.createScreenCaptureIntent(), SCREEN_CAPTURE_REQUEST);
                }
            });
        }

        @JavascriptInterface
        public void stopScreenMirror() {
            Intent mirrorIntent = new Intent(MainActivity.this, ScreenMirrorService.class);
            mirrorIntent.setAction(ScreenMirrorService.ACTION_STOP);
            startService(mirrorIntent);
            showToast("Screen mirroring stopped");
            runOnUiThread(() -> {
                if (webView != null) {
                    webView.evaluateJavascript("if (typeof onScreenMirrorChanged === 'function') onScreenMirrorChanged(false);", null);
                }
            });
        }

        @JavascriptInterface
        public boolean isScreenMirroring() {
            return ScreenMirrorService.isRunning();
        }
    }
}
