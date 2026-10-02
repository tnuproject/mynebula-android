package org.nebulaos.mynebula;

import android.Manifest;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.UUID;

public class MyNebulaService extends Service {
    public static final String CHANNEL_ID = "mynebula_service_channel";
    public static final int NOTIF_ID = 2001;
    public static final int HTTP_PUSH_PORT = 53319;
    public static final int UDP_BEACON_PORT = 53318;

    private boolean isRunning = false;
    private Thread beaconThread;
    private Thread pollThread;
    private Thread httpPushThread;
    private ServerSocket pushServerSocket;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private long lastContactsSyncTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();

        // 1. Acquire partial WakeLock and WifiLock so background sync continues 24/7
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyNebula:ServiceWakeLock");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(10 * 365 * 24 * 3600 * 1000L); // Keep alive
            }
        } catch (Exception ignored) {}

        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MyNebula:WifiLock");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) {}

        // 2. Setup ongoing foreground notification
        try {
            createNotificationChannel();
            Notification notification = createNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIF_ID, notification);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        isRunning = true;
        startBeaconListener();
        startEventPoller();
        startPushHttpServer();
        setupTelephonyListener();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "MyNebula Background Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps your Android device paired and synchronized with NebulaOS");
            channel.setShowBadge(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    public Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        SharedPreferences prefs = getSharedPreferences("mynebula_prefs", MODE_PRIVATE);
        String pairedHost = prefs.getString("paired_device_name", "NebulaOS Desktop");
        boolean isPaired = prefs.getBoolean("is_paired", false);

        String contentText = isPaired
                ? "Connected to " + pairedHost + " • Ready for PetalDrop & Calls"
                : "Active and searching for NebulaOS devices";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("MyNebula Companion")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    public void updateNotification() {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIF_ID, createNotification());
            }
        } catch (Exception ignored) {}
    }

    public static String getOrGenerateDeviceId(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("mynebula_prefs", MODE_PRIVATE);
        String id = prefs.getString("device_id", null);
        if (id == null || id.isEmpty()) {
            id = "android-" + UUID.randomUUID().toString().substring(0, 8);
            prefs.edit().putString("device_id", id).commit();
        }
        return id;
    }

    private void startBeaconListener() {
        beaconThread = new Thread(() -> {
            DatagramSocket socket = null;
            try {
                socket = new DatagramSocket(null);
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(UDP_BEACON_PORT));
                socket.setBroadcast(true);
                byte[] buffer = new byte[2048];
                while (isRunning) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    String message = new String(packet.getData(), 0, packet.getLength());
                    if (message.contains("petaldrop_beacon")) {
                        String hostIp = packet.getAddress().getHostAddress();
                        SharedPreferences prefs = getSharedPreferences("mynebula_prefs", MODE_PRIVATE);

                        // Parse beacon payload
                        String devName = "NebulaOS PC";
                        try {
                            JSONObject obj = new JSONObject(message);
                            if (obj.has("device_name")) {
                                devName = obj.getString("device_name");
                            }
                        } catch (Exception ignored) {}

                        boolean isPaired = prefs.getBoolean("is_paired", false);
                        String savedHost = prefs.getString("paired_device_name", "");

                        if (isPaired) {
                            // Update IP if host name matches or we have only one paired laptop
                            prefs.edit().putString("last_seen_ip", hostIp).commit();
                            updateNotification();

                            // Auto-sync contacts periodically in background
                            long now = System.currentTimeMillis();
                            if (now - lastContactsSyncTime > 1800000) { // every 30 mins
                                lastContactsSyncTime = now;
                                syncContacts(MyNebulaService.this, hostIp);
                            }
                        } else {
                            // Remember discovered host IP
                            prefs.edit().putString("last_seen_ip", hostIp).commit();
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (socket != null && !socket.isClosed()) {
                    socket.close();
                }
            }
        });
        beaconThread.setDaemon(true);
        beaconThread.start();
    }

    private void startEventPoller() {
        pollThread = new Thread(() -> {
            while (isRunning) {
                try {
                    SharedPreferences prefs = getSharedPreferences("mynebula_prefs", MODE_PRIVATE);
                    boolean isPaired = prefs.getBoolean("is_paired", false);
                    String hostIp = prefs.getString("last_seen_ip", null);

                    if (isPaired && hostIp != null && !hostIp.isEmpty()) {
                        URL url = new URL("http://" + hostIp + ":53317/api/phone/events");
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(2500);
                        conn.setReadTimeout(2500);
                        if (conn.getResponseCode() == 200) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                            StringBuilder sb = new StringBuilder();
                            String line;
                            while ((line = reader.readLine()) != null) {
                                sb.append(line);
                            }
                            reader.close();

                            JSONObject resp = new JSONObject(sb.toString());
                            JSONArray events = resp.optJSONArray("events");
                            if (events != null) {
                                for (int i = 0; i < events.length(); i++) {
                                    JSONObject ev = events.getJSONObject(i);
                                    handlePhoneEvent(ev);
                                }
                            }
                        }
                        conn.disconnect();
                    }
                } catch (Exception ignored) {
                }

                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        });
        pollThread.setDaemon(true);
        pollThread.start();
    }

    private void startPushHttpServer() {
        httpPushThread = new Thread(() -> {
            try {
                pushServerSocket = new ServerSocket(HTTP_PUSH_PORT);
                while (isRunning) {
                    Socket client = pushServerSocket.accept();
                    new Thread(() -> handleHttpClient(client)).start();
                }
            } catch (Exception ignored) {
            }
        });
        httpPushThread.setDaemon(true);
        httpPushThread.start();
    }

    private void handleHttpClient(Socket client) {
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
            String firstLine = in.readLine();
            if (firstLine != null) {
                int contentLength = 0;
                String headerLine;
                while ((headerLine = in.readLine()) != null && !headerLine.isEmpty()) {
                    if (headerLine.toLowerCase().startsWith("content-length:")) {
                        contentLength = Integer.parseInt(headerLine.substring(15).trim());
                    }
                }

                StringBuilder body = new StringBuilder();
                if (contentLength > 0) {
                    char[] buf = new char[contentLength];
                    int read = in.read(buf, 0, contentLength);
                    if (read > 0) {
                        body.append(buf, 0, read);
                    }
                }

                if (firstLine.contains("/dial")) {
                    String num = "";
                    try {
                        JSONObject json = new JSONObject(body.toString());
                        num = json.optString("number", "");
                    } catch (Exception ignored) {}
                    if (!num.isEmpty()) {
                        executeDial(num);
                    }
                } else if (firstLine.contains("/accept")) {
                    executeAccept();
                } else if (firstLine.contains("/hangup")) {
                    executeHangup();
                }

                OutputStream out = client.getOutputStream();
                out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 16\r\n\r\n{\"success\":true}".getBytes());
                out.flush();
            }
            client.close();
        } catch (Exception ignored) {
        }
    }

    private void handlePhoneEvent(JSONObject ev) {
        String action = ev.optString("action", "");
        if ("dial".equals(action)) {
            String num = ev.optString("number", "");
            if (!num.isEmpty()) {
                executeDial(num);
            }
        } else if ("accept".equals(action)) {
            executeAccept();
        } else if ("hangup".equals(action)) {
            executeHangup();
        }
    }

    private void executeDial(String number) {
        try {
            Intent callIntent = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)));
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(callIntent);
        } catch (Exception e) {
            try {
                Intent dialIntent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(number)));
                dialIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(dialIntent);
            } catch (Exception ignored) {}
        }
    }

    private void executeAccept() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                if (tm != null && ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    tm.acceptRingingCall();
                }
            } catch (Exception ignored) {}
        }
    }

    private void executeHangup() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                TelecomManager tm = (TelecomManager) getSystemService(Context.TELECOM_SERVICE);
                if (tm != null && ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    tm.endCall();
                }
            } catch (Exception ignored) {}
        }
    }

    @SuppressWarnings("deprecation")
    private void setupTelephonyListener() {
        try {
            TelephonyManager tm = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) return;
            tm.listen(new PhoneStateListener() {
                @Override
                public void onCallStateChanged(int state, String phoneNumber) {
                    notifyDesktopCallState(state, phoneNumber);
                }
            }, PhoneStateListener.LISTEN_CALL_STATE);
        } catch (Exception ignored) {}
    }

    private void notifyDesktopCallState(int state, String number) {
        SharedPreferences prefs = getSharedPreferences("mynebula_prefs", MODE_PRIVATE);
        boolean isPaired = prefs.getBoolean("is_paired", false);
        String hostIp = prefs.getString("last_seen_ip", null);
        if (!isPaired || hostIp == null || hostIp.isEmpty()) return;

        new Thread(() -> {
            try {
                if (state == TelephonyManager.CALL_STATE_RINGING) {
                    JSONObject payload = new JSONObject();
                    payload.put("number", number != null && !number.isEmpty() ? number : "Incoming Call");
                    payload.put("name", "Incoming Call");
                    sendPost(hostIp, "/api/call/incoming", payload.toString());
                } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                    sendPost(hostIp, "/api/call/hangup", "{}");
                }
            } catch (Exception ignored) {}
        }).start();
    }

    private void sendPost(String hostIp, String path, String body) {
        try {
            URL url = new URL("http://" + hostIp + ":53317" + path);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception ignored) {}
    }

    public static void syncContacts(Context context, String hostIp) {
        if (hostIp == null || hostIp.isEmpty()) return;
        new Thread(() -> {
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                JSONArray arr = new JSONArray();
                Cursor cursor = context.getContentResolver().query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        new String[]{
                                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                ContactsContract.CommonDataKinds.Phone.NUMBER
                        },
                        null, null,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
                );
                if (cursor != null) {
                    HashSet<String> seen = new HashSet<>();
                    while (cursor.moveToNext() && arr.length() < 500) {
                        String name = cursor.getString(0);
                        String number = cursor.getString(1);
                        if (name != null && number != null && !seen.contains(number)) {
                            seen.add(number);
                            JSONObject c = new JSONObject();
                            c.put("name", name);
                            c.put("phone", number);
                            arr.put(c);
                        }
                    }
                    cursor.close();
                }

                JSONObject payload = new JSONObject();
                payload.put("contacts", arr);

                URL url = new URL("http://" + hostIp + ":53317/api/contacts");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(4000);
                byte[] out = payload.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(out);
                }
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        super.onTaskRemoved(rootIntent);
        // CRUCIAL: App was swiped away from recent apps — revive service immediately!
        Intent restartServiceIntent = new Intent(getApplicationContext(), MyNebulaService.class);
        restartServiceIntent.setPackage(getPackageName());
        PendingIntent restartPendingIntent = PendingIntent.getService(
                getApplicationContext(), 1, restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
        );
        AlarmManager alarmService = (AlarmManager) getApplicationContext().getSystemService(Context.ALARM_SERVICE);
        if (alarmService != null) {
            alarmService.set(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + 1000,
                    restartPendingIntent
            );
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        try {
            if (pushServerSocket != null && !pushServerSocket.isClosed()) {
                pushServerSocket.close();
            }
        } catch (Exception ignored) {}

        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            try { wifiLock.release(); } catch (Exception ignored) {}
        }

        // Send broadcast to restart service if killed unexpectedly
        try {
            Intent broadcastIntent = new Intent();
            broadcastIntent.setAction("org.nebulaos.mynebula.RESTART_SERVICE");
            broadcastIntent.setClass(this, BootReceiver.class);
            sendBroadcast(broadcastIntent);
        } catch (Exception ignored) {}

        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
