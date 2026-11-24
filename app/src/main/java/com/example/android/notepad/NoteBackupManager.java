package com.example.android.notepad;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * 备份文件格式、校验、本地历史、云同步与恢复逻辑。
 *
 * <p>备份文件是带版本和 CRC 校验的 JSON，包含标题、正文以及创建/修改时间；
 * 恢复时默认按「标题 + 正文 + 创建时间」去重，避免重复导入。</p>
 */
public final class NoteBackupManager {

    private static final String TAG = "NoteBackupManager";

    public static final String FORMAT = "notepad-backup";
    public static final int VERSION = 1;
    public static final String KEY_CLOUD_URL = "cloud_url";
    public static final String KEY_CLOUD_TOKEN = "cloud_token";

    private static final String[] PROJECTION = new String[] {
            NotePad.Notes._ID,
            NotePad.Notes.COLUMN_NAME_TITLE,
            NotePad.Notes.COLUMN_NAME_NOTE,
            NotePad.Notes.COLUMN_NAME_CREATE_DATE,
            NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE,
            NotePad.Notes.COLUMN_NAME_DONE
    };
    private static final int MAX_LOCAL_BACKUPS = 10;
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 20000;

    private NoteBackupManager() {
    }

    /** 备份数据中的一个笔记条目。 */
    public static class NoteItem {
        public String title;
        public String content;
        public long created;
        public long modified;
        public boolean done;
    }

    /** 解析后的备份数据。 */
    public static class BackupData {
        public long createdAt;
        public List<NoteItem> notes = new ArrayList<NoteItem>();
    }

    /** 本地备份记录。 */
    public static class BackupInfo {
        public File file;
        public String name;
        public long time;
        public int count;
        public long size;
    }

    /** 用于展示友好错误信息的异常。 */
    public static class BackupException extends Exception {
        public BackupException(String message) {
            super(message);
        }
    }

    /** 把当前数据库中的全部笔记序列化为带校验的备份 JSON。 */
    public static String createBackupJson(Context context) throws BackupException {
        ContentResolver resolver = context.getContentResolver();
        Cursor cursor = resolver.query(NotePad.Notes.CONTENT_URI, PROJECTION,
                null, null, NotePad.Notes.DEFAULT_SORT_ORDER);
        JSONArray notes = new JSONArray();
        try {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    JSONObject item = new JSONObject();
                    put(item, "title", text(cursor.getString(1)));
                    put(item, "note", text(cursor.getString(2)));
                    put(item, "created", cursor.getLong(3));
                    put(item, "modified", cursor.getLong(4));
                    put(item, "done", cursor.getInt(5));
                    notes.put(item);
                } while (cursor.moveToNext());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        if (notes.length() == 0) {
            throw new BackupException(context.getString(R.string.export_empty));
        }

        JSONObject data = new JSONObject();
        put(data, "format", FORMAT);
        put(data, "version", VERSION);
        put(data, "createdAt", System.currentTimeMillis());
        put(data, "device", Build.MODEL);
        put(data, "notes", notes);

        JSONObject root = new JSONObject();
        put(root, "data", data);
        put(root, "checksum", checksum(notes));
        try {
            return root.toString(2);
        } catch (Exception e) {
            throw new BackupException("生成备份文件失败");
        }
    }

    /** 解析并校验备份 JSON。 */
    public static BackupData parseBackup(String json) throws BackupException {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject data = root.optJSONObject("data");
            if (data == null) {
                // 导出文件（notepad-export）顶层直接是 notes，没有 data 包装层
                if (root.has("notes") && root.has("format")) {
                    throw new BackupException("选中的是「导出笔记」文件，不是备份文件");
                }
                throw new BackupException("不是 NotePad 备份文件");
            }
            if (!FORMAT.equals(data.optString("format"))) {
                throw new BackupException("不是 NotePad 备份文件");
            }
            int version = data.optInt("version", -1);
            if (version < 1 || version > VERSION) {
                throw new BackupException("备份版本不受支持");
            }
            JSONArray notes = data.optJSONArray("notes");
            if (notes == null) {
                throw new BackupException("备份内容缺失");
            }
            String expected = root.optString("checksum", "");
            if (expected.length() == 0 || !expected.equals(checksum(notes))) {
                throw new BackupException("备份校验失败，文件可能已损坏");
            }

            BackupData result = new BackupData();
            result.createdAt = data.optLong("createdAt", 0L);
            for (int i = 0; i < notes.length(); i++) {
                JSONObject obj = notes.getJSONObject(i);
                NoteItem item = new NoteItem();
                item.title = obj.optString("title", "");
                item.content = obj.optString("note", "");
                item.created = obj.optLong("created", 0L);
                item.modified = obj.optLong("modified", 0L);
                item.done = obj.optInt("done", 0) != 0;
                result.notes.add(item);
            }
            return result;
        } catch (BackupException e) {
            throw e;
        } catch (Exception e) {
            Log.e(TAG, "parse backup failed", e);
            throw new BackupException("备份文件格式错误");
        }
    }

    /** 写入带时间戳的本机备份，并保留最近 10 份。 */
    public static File writeLocalBackup(Context context, String json) throws BackupException {
        File dir = getBackupDir(context);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());
        File target = new File(dir, "backup_" + stamp + ".json");
        File temp = new File(dir, target.getName() + ".tmp");
        try {
            FileOutputStream fos = new FileOutputStream(temp);
            try {
                OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8");
                try {
                    writer.write(json);
                    writer.flush();
                } finally {
                    writer.close();
                }
            } finally {
                fos.close();
            }
            if (!temp.renameTo(target)) {
                throw new BackupException("无法写入备份文件");
            }
        } catch (BackupException e) {
            throw e;
        } catch (Exception e) {
            Log.e(TAG, "write backup failed", e);
            throw new BackupException(e.getMessage());
        }
        pruneLocalBackups(dir);
        return target;
    }

    /** 列出本机备份，按时间从新到旧。 */
    public static List<BackupInfo> listLocalBackups(Context context) {
        File dir;
        try {
            dir = getBackupDir(context);
        } catch (BackupException e) {
            return new ArrayList<BackupInfo>();
        }
        File[] files = dir.listFiles();
        List<BackupInfo> result = new ArrayList<BackupInfo>();
        if (files == null) {
            return result;
        }
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".json")) {
                BackupInfo info = new BackupInfo();
                info.file = file;
                info.name = file.getName();
                info.size = file.length();
                info.time = file.lastModified();
                try {
                    BackupData data = parseBackup(readFile(file));
                    info.count = data.notes.size();
                    if (data.createdAt > 0) {
                        info.time = data.createdAt;
                    }
                } catch (Exception e) {
                    info.count = -1;
                }
                result.add(info);
            }
        }
        Collections.sort(result, new Comparator<BackupInfo>() {
            @Override
            public int compare(BackupInfo a, BackupInfo b) {
                return Long.valueOf(b.time).compareTo(a.time);
            }
        });
        return result;
    }

    public static String readFile(File file) throws BackupException {
        StringBuilder builder = new StringBuilder();
        try {
            FileInputStream fis = new FileInputStream(file);
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        builder.append(line).append('\n');
                    }
                } finally {
                    reader.close();
                }
            } finally {
                fis.close();
            }
        } catch (Exception e) {
            throw new BackupException("读取备份文件失败");
        }
        return builder.toString();
    }

    public static String readUri(Context context, Uri uri) throws BackupException {
        StringBuilder builder = new StringBuilder();
        try {
            InputStream input = context.getContentResolver().openInputStream(uri);
            if (input == null) {
                throw new BackupException("无法打开所选文件");
            }
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        builder.append(line).append('\n');
                    }
                } finally {
                    reader.close();
                }
            } finally {
                input.close();
            }
        } catch (BackupException e) {
            throw e;
        } catch (Exception e) {
            throw new BackupException("读取所选文件失败");
        }
        return builder.toString();
    }

    /**
     * 恢复笔记。
     *
     * @param clearFirst true 表示先清空现有笔记（用户需二次确认）
     * @return {导入条数, 跳过条数}
     */
    public static int[] restore(Context context, BackupData data, boolean clearFirst) {
        ContentResolver resolver = context.getContentResolver();
        if (clearFirst) {
            resolver.delete(NotePad.Notes.CONTENT_URI, null, null);
        }

        Set<String> existing = new HashSet<String>();
        if (!clearFirst) {
            Cursor cursor = resolver.query(NotePad.Notes.CONTENT_URI, PROJECTION,
                    null, null, null);
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        existing.add(key(text(cursor.getString(1)), text(cursor.getString(2)),
                                cursor.getLong(3)));
                    } while (cursor.moveToNext());
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        int imported = 0;
        int skipped = 0;
        for (NoteItem item : data.notes) {
            String itemKey = key(item.title, item.content, item.created);
            if (existing.contains(itemKey)) {
                skipped++;
                continue;
            }
            ContentValues values = new ContentValues();
            values.put(NotePad.Notes.COLUMN_NAME_TITLE, item.title);
            values.put(NotePad.Notes.COLUMN_NAME_NOTE, item.content);
            values.put(NotePad.Notes.COLUMN_NAME_CREATE_DATE, item.created);
            values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, item.modified);
            values.put(NotePad.Notes.COLUMN_NAME_DONE, item.done ? 1 : 0);
            if (resolver.insert(NotePad.Notes.CONTENT_URI, values) != null) {
                existing.add(itemKey);
                imported++;
            } else {
                skipped++;
            }
        }
        return new int[] {imported, skipped};
    }

    /** 通过 HTTP PUT 上传备份；支持可选的 Bearer 令牌。 */
    public static void uploadToCloud(String url, String token, String json)
            throws BackupException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("PUT");
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            connection.setDoOutput(true);
            if (token != null && token.trim().length() > 0) {
                connection.setRequestProperty("Authorization", "Bearer " + token.trim());
            }
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream out = connection.getOutputStream();
            try {
                out.write(json.getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new BackupException("服务器返回 " + code);
            }
        } catch (BackupException e) {
            throw e;
        } catch (Exception e) {
            Log.e(TAG, "upload failed", e);
            throw new BackupException("上传失败：" + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** 通过 HTTP GET 下载备份。 */
    public static String downloadFromCloud(String url, String token) throws BackupException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            if (token != null && token.trim().length() > 0) {
                connection.setRequestProperty("Authorization", "Bearer " + token.trim());
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new BackupException("服务器返回 " + code);
            }
            InputStream in = connection.getInputStream();
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                try {
                    StringBuilder builder = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        builder.append(line).append('\n');
                    }
                    return builder.toString();
                } finally {
                    reader.close();
                }
            } finally {
                in.close();
            }
        } catch (BackupException e) {
            throw e;
        } catch (Exception e) {
            Log.e(TAG, "download failed", e);
            throw new BackupException("下载失败：" + e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public static boolean deleteBackup(BackupInfo info) {
        return info.file != null && info.file.delete();
    }

    /** 最近备份的展示文本。 */
    public static String formatBackupTime(Context context, long time) {
        if (time <= 0) {
            return "-";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(time));
    }

    private static File getBackupDir(Context context) throws BackupException {
        File base = context.getExternalFilesDir(null);
        if (base == null) {
            base = context.getFilesDir();
        }
        File dir = new File(base, "backups");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new BackupException("无法创建备份目录");
        }
        return dir;
    }

    private static void pruneLocalBackups(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        List<File> backups = new ArrayList<File>();
        for (File file : files) {
            if (file.isFile() && file.getName().endsWith(".json")) {
                backups.add(file);
            }
        }
        Collections.sort(backups, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.valueOf(b.lastModified()).compareTo(a.lastModified());
            }
        });
        for (int i = MAX_LOCAL_BACKUPS; i < backups.size(); i++) {
            if (!backups.get(i).delete()) {
                Log.w(TAG, "cannot prune old backup: " + backups.get(i).getName());
            }
        }
    }

    private static String checksum(JSONArray notes) {
        CRC32 crc = new CRC32();
        crc.update(notes.toString().getBytes(java.nio.charset.Charset.forName("UTF-8")));
        return Long.toHexString(crc.getValue());
    }

    private static String key(String title, String content, long created) {
        return title + "\u0000" + content + "\u0000" + created;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (Exception e) {
            Log.e(TAG, "put json failed: " + key, e);
        }
    }
}
