package org.nebulaos.mynebula;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * ScreenMirrorService captures phone display via MediaProjection and ImageReader,
 * encodes frames to JPEG, and sends them to the NebulaOS Companion daemon at
 * POST /api/mirror/frame.
 */
public class ScreenMirrorService extends Service {
    public static final String CHANNEL_ID = "mynebula_mirror_channel";
    public static final int NOTIF_ID = 2002;
    public static final String ACTION_START = "org.nebulaos.mynebula.START_MIRROR";
    public static final String ACTION_STOP = "org.nebulaos.mynebula.STOP_MIRROR";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";

    private static boolean isMirroring = false;

    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread handlerThread;
    private Handler backgroundHandler;
    private String laptopIp;
    private int screenWidth = 720;
    private int screenHeight = 1280;
    private int screenDensity = 320;
    private long lastFrameTime = 0;
    private static final long MIN_FRAME_INTERVAL_MS = 60; // ~16-20 FPS for smooth wireless streaming

    public static boolean isRunning() {
        return isMirroring;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();

        handlerThread = new HandlerThread("ScreenMirrorWorker");
        handlerThread.start();
        backgroundHandler = new Handler(handlerThread.getLooper());

        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            wm.getDefaultDisplay().getRealMetrics(metrics);
            screenDensity = metrics.densityDpi;
            // Scale down resolution for fast low-latency Wi-Fi streaming
            int maxDim = 960;
            if (metrics.widthPixels > metrics.heightPixels) {
                screenWidth = maxDim;
                screenHeight = (int) (maxDim * ((float) metrics.heightPixels / metrics.widthPixels));
            } else {
                screenHeight = maxDim;
                screenWidth = (int) (maxDim * ((float) metrics.widthPixels / metrics.heightPixels));
            }
            // Ensure even dimensions
            screenWidth = (screenWidth / 2) * 2;
            screenHeight = (screenHeight / 2) * 2;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopMirroring();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);

            SharedPreferences prefs = getSharedPreferences("mynebula_prefs", MODE_PRIVATE);
            laptopIp = prefs.getString("last_seen_ip", null);

            Notification notification = createNotification();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIF_ID, notification);
            }

            if (resultCode != 0 && resultData != null && laptopIp != null) {
                startMirroring(resultCode, resultData);
            }
        }

        return START_NOT_STICKY;
    }

    private void startMirroring(int resultCode, Intent resultData) {
        MediaProjectionManager mpm = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) return;

        mediaProjection = mpm.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) return;

        isMirroring = true;

        // Notify companion daemon on desktop to open the screen mirror viewer window
        new Thread(() -> {
            try {
                JSONObject payload = new JSONObject();
                payload.put("device_name", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
                byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);

                URL url = new URL("http://" + laptopIp + ":53317/api/mirror/start");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(3000);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception ignored) {}
        }).start();

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 3);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "MyNebulaScreenMirror",
                screenWidth, screenHeight, screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, backgroundHandler
        );

        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastFrameTime >= MIN_FRAME_INTERVAL_MS) {
                        lastFrameTime = now;
                        processAndSendFrame(image);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }, backgroundHandler);
    }

    private void processAndSendFrame(Image image) {
        try {
            Image.Plane[] planes = image.getPlanes();
            ByteBuffer buffer = planes[0].getBuffer();
            int width = image.getWidth();
            int height = image.getHeight();
            int pixelStride = planes[0].getPixelStride();
            int rowStride = planes[0].getRowStride();
            int rowPadding = rowStride - pixelStride * width;

            Bitmap bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height,
                    Bitmap.Config.ARGB_8888
            );
            bitmap.copyPixelsFromBuffer(buffer);

            // Crop out padding if necessary
            Bitmap croppedBitmap = bitmap;
            if (rowPadding != 0) {
                croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height);
                bitmap.recycle();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 65, baos);
            croppedBitmap.recycle();
            byte[] jpegBytes = baos.toByteArray();

            // Send frame to companion daemon asynchronously
            new Thread(() -> sendFrameToLaptop(jpegBytes)).start();
        } catch (Exception ignored) {}
    }

    private void sendFrameToLaptop(byte[] jpegBytes) {
        if (!isMirroring || laptopIp == null) return;
        try {
            URL url = new URL("http://" + laptopIp + ":53317/api/mirror/frame");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestProperty("Content-Type", "image/jpeg");
            conn.setRequestProperty("Content-Length", String.valueOf(jpegBytes.length));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jpegBytes);
            }
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception ignored) {}
    }

    private void stopMirroring() {
        isMirroring = false;
        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            if (mediaProjection != null) {
                mediaProjection.stop();
                mediaProjection = null;
            }
        } catch (Exception ignored) {}

        // Notify desktop companion that mirror stopped
        if (laptopIp != null) {
            new Thread(() -> {
                try {
                    URL url = new URL("http://" + laptopIp + ":53317/api/mirror/stop");
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("POST");
                    conn.setDoOutput(true);
                    conn.setConnectTimeout(2000);
                    conn.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write("{}".getBytes(StandardCharsets.UTF_8));
                    }
                    conn.getResponseCode();
                    conn.disconnect();
                } catch (Exception ignored) {}
            }).start();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Screen Mirroring Active",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Streaming screen to NebulaOS laptop");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent stopIntent = new Intent(this, ScreenMirrorService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent pStop = PendingIntent.getService(this, 10, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Screen Mirroring Active")
                .setContentText("Sharing phone screen with NebulaOS")
                .setSmallIcon(android.R.drawable.ic_menu_slideshow)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Sharing", pStop)
                .build();
    }

    @Override
    public void onDestroy() {
        stopMirroring();
        if (handlerThread != null) {
            handlerThread.quitSafely();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
