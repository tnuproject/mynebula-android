package org.nebulaos.mynebula;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * GallerySyncManager manages bidirectional photo synchronization with NebulaOS PC.
 * It reads phone photos via MediaStore, computes MD5 hashes, imports photos from PC into MediaStore,
 * and handles deletion requests.
 */
public class GallerySyncManager {
    private static final String TAG = "GallerySyncManager";
    private static final String PREFS_NAME = "mynebula_gallery_sync";
    private static final String PREF_ENABLED = "gallery_sync_enabled";
    private static final String HASH_CACHE_PREFS = "mynebula_hash_cache";

    private static final Map<String, String> memoryHashCache = new HashMap<>();

    public static boolean isSyncEnabled(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(PREF_ENABLED, false);
    }

    public static void setSyncEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(PREF_ENABLED, enabled).apply();
    }

    /**
     * Scans MediaStore.Images for local photos and builds a JSON manifest with MD5 hashes.
     */
    public static JSONObject getPhoneManifest(Context context) {
        JSONObject result = new JSONObject();
        JSONArray array = new JSONArray();

        ContentResolver resolver = context.getContentResolver();
        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        String[] projection = new String[]{
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.DATE_MODIFIED
        };

        SharedPreferences cachePrefs = context.getSharedPreferences(HASH_CACHE_PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor cacheEditor = cachePrefs.edit();

        try (Cursor cursor = resolver.query(
                collection,
                projection,
                null,
                null,
                MediaStore.Images.Media.DATE_MODIFIED + " DESC"
        )) {
            if (cursor != null) {
                int idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                int nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME);
                int sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE);
                int dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED);

                // Limit to recent 500 photos for quick sync cycles
                int count = 0;
                while (cursor.moveToNext() && count < 500) {
                    long id = cursor.getLong(idCol);
                    String name = cursor.getString(nameCol);
                    long size = cursor.getLong(sizeCol);
                    long mtime = cursor.getLong(dateCol);

                    if (size <= 0) continue;

                    String cacheKey = id + "_" + size + "_" + mtime;
                    String hash = memoryHashCache.get(cacheKey);
                    if (hash == null) {
                        hash = cachePrefs.getString(cacheKey, null);
                    }

                    if (hash == null) {
                        // Compute MD5
                        Uri imageUri = ContentUris.withAppendedId(collection, id);
                        hash = computeMd5(resolver, imageUri);
                        if (hash != null) {
                            memoryHashCache.put(cacheKey, hash);
                            cacheEditor.putString(cacheKey, hash);
                        }
                    }

                    if (hash != null) {
                        JSONObject pic = new JSONObject();
                        pic.put("id", id);
                        pic.put("name", name != null ? name : ("photo_" + id + ".jpg"));
                        pic.put("size", size);
                        pic.put("mtime", mtime);
                        pic.put("hash", hash);
                        array.put(pic);
                        count++;
                    }
                }
                cacheEditor.apply();
            }
            result.put("pictures", array);
            result.put("count", array.length());
        } catch (Exception e) {
            Log.e(TAG, "Error generating manifest", e);
            try {
                result.put("pictures", new JSONArray());
                result.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return result;
    }

    /**
     * Opens an InputStream for the image with the specified MediaStore ID.
     */
    public static InputStream openImageStream(Context context, long id) {
        try {
            Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                    : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
            Uri imageUri = ContentUris.withAppendedId(collection, id);
            return context.getContentResolver().openInputStream(imageUri);
        } catch (Exception e) {
            Log.e(TAG, "Error opening image stream: " + id, e);
            return null;
        }
    }

    /**
     * Saves an image transferred from NebulaOS PC into the phone's MediaStore gallery.
     */
    public static boolean saveImage(Context context, String filename, String hash, long mtime, byte[] data) {
        if (data == null || data.length == 0) return false;

        // Verify checksum if provided
        if (hash != null && !hash.isEmpty()) {
            String computed = computeMd5FromBytes(data);
            if (!hash.equalsIgnoreCase(computed)) {
                Log.w(TAG, "Checksum mismatch on upload: expected " + hash + ", got " + computed);
                return false;
            }
        }

        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);

        String mime = "image/jpeg";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) mime = "image/png";
        else if (lower.endsWith(".webp")) mime = "image/webp";
        else if (lower.endsWith(".svg")) mime = "image/svg+xml";
        else if (lower.endsWith(".gif")) mime = "image/gif";

        values.put(MediaStore.Images.Media.MIME_TYPE, mime);
        if (mtime > 0) {
            values.put(MediaStore.Images.Media.DATE_MODIFIED, mtime);
            values.put(MediaStore.Images.Media.DATE_ADDED, mtime);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NebulaOS");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        Uri insertedUri = null;
        try {
            insertedUri = resolver.insert(collection, values);
            if (insertedUri == null) return false;

            try (OutputStream os = resolver.openOutputStream(insertedUri)) {
                if (os == null) return false;
                os.write(data);
                os.flush();
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(insertedUri, values, null, null);
            }

            Log.i(TAG, "Saved photo from PC: " + filename + " -> " + insertedUri);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to save photo to MediaStore: " + filename, e);
            if (insertedUri != null) {
                try {
                    resolver.delete(insertedUri, null, null);
                } catch (Exception ignored) {}
            }
            return false;
        }
    }

    /**
     * Deletes an image from MediaStore matching the given name or id.
     */
    public static boolean deleteImage(Context context, long id, String filename, String hash) {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

        try {
            if (id > 0) {
                Uri itemUri = ContentUris.withAppendedId(collection, id);
                int deleted = resolver.delete(itemUri, null, null);
                if (deleted > 0) return true;
            }

            if (filename != null && !filename.isEmpty()) {
                String selection = MediaStore.Images.Media.DISPLAY_NAME + "=?";
                String[] args = new String[]{filename};
                int deleted = resolver.delete(collection, selection, args);
                if (deleted > 0) return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error deleting image: " + filename, e);
        }
        return false;
    }

    private static final char[] HEX_ARRAY = "0123456789abcdef".toCharArray();

    private static String bytesToHex(byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    private static String computeMd5(ContentResolver resolver, Uri uri) {
        try (InputStream is = resolver.openInputStream(uri)) {
            if (is == null) return null;
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buf = new byte[32768];
            int read;
            while ((read = is.read(buf)) != -1) {
                md.update(buf, 0, read);
            }
            return bytesToHex(md.digest());
        } catch (Exception e) {
            return null;
        }
    }

    private static String computeMd5FromBytes(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(data);
            return bytesToHex(digest);
        } catch (Exception e) {
            return "";
        }
    }
}
