package com.example.android.notepad;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 笔记导出工具：支持单条/全部笔记导出为 TXT、Markdown、HTML、JSON，
 * 并通过系统「保存到文件」选择器写入本地或任意云盘目录；也支持直接分享纯文本。
 *
 * <p>调用方只需要在自己的 onActivityResult 中转发 {@link #handleResult}。</p>
 */
public final class NoteExportManager {

    public enum Format {
        TXT("txt", "text/plain"),
        MD("md", "text/markdown"),
        HTML("html", "text/html"),
        JSON("json", "application/json");

        final String extension;
        final String mimeType;

        Format(String extension, String mimeType) {
            this.extension = extension;
            this.mimeType = mimeType;
        }
    }

    private static final String[] PROJECTION = new String[] {
            NotePad.Notes._ID,
            NotePad.Notes.COLUMN_NAME_TITLE,
            NotePad.Notes.COLUMN_NAME_NOTE,
            NotePad.Notes.COLUMN_NAME_CREATE_DATE,
            NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE,
            NotePad.Notes.COLUMN_NAME_DONE
    };

    private static final class ExportJob {
        final String content;
        final String fileName;
        final String mimeType;

        ExportJob(String content, String fileName, String mimeType) {
            this.content = content;
            this.fileName = fileName;
            this.mimeType = mimeType;
        }
    }

    private static final Map<Integer, ExportJob> PENDING_JOBS = new HashMap<Integer, ExportJob>();

    private NoteExportManager() {
    }

    /** 显示导出方式选择框。noteUri 为 null 表示导出全部笔记。 */
    public static void showExportDialog(final Activity activity, final Uri noteUri,
                                        final int requestCode) {
        ContentResolver resolver = activity.getContentResolver();
        Uri uri = noteUri == null ? NotePad.Notes.CONTENT_URI : noteUri;
        Cursor check = resolver.query(uri, new String[] {NotePad.Notes._ID}, null, null, null);
        boolean empty = check == null || !check.moveToFirst();
        if (check != null) {
            check.close();
        }
        if (empty) {
            Toast.makeText(activity, R.string.export_empty, Toast.LENGTH_SHORT).show();
            return;
        }

        final String[] labels = new String[] {
                activity.getString(R.string.export_share),
                activity.getString(R.string.export_txt),
                activity.getString(R.string.export_md),
                activity.getString(R.string.export_html),
                activity.getString(R.string.export_json)
        };
        new AlertDialog.Builder(activity)
                .setTitle(R.string.export_title)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            shareAsText(activity, noteUri);
                        } else {
                            Format format = Format.values()[which - 1];
                            saveToDocument(activity, noteUri, format, requestCode);
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 调用方在 onActivityResult 中转发结果。 */
    public static void handleResult(Activity activity, int requestCode,
                                    int resultCode, Intent data) {
        ExportJob job = PENDING_JOBS.remove(requestCode);
        if (job == null || resultCode != Activity.RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        Uri target = data.getData();
        try {
            OutputStream out = activity.getContentResolver().openOutputStream(target);
            if (out == null) {
                throw new IllegalStateException("无法写入目标文件");
            }
            try {
                out.write(job.content.getBytes("UTF-8"));
                out.flush();
            } finally {
                out.close();
            }
            String name = target.getLastPathSegment();
            Toast.makeText(activity, activity.getString(R.string.export_done,
                    name == null ? job.fileName : name), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(activity, activity.getString(R.string.export_failed,
                    e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private static void shareAsText(Context context, Uri noteUri) {
        String text = buildContent(context, noteUri, Format.TXT);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.app_name));
        intent.putExtra(Intent.EXTRA_TEXT, text);
        context.startActivity(Intent.createChooser(intent,
                context.getString(R.string.export_title)));
    }

    private static void saveToDocument(Activity activity, Uri noteUri, Format format,
                                       int requestCode) {
        String content = buildContent(activity, noteUri, format);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());
        String fileName = String.format(Locale.getDefault(),
                activity.getString(R.string.export_file_name), stamp) + "." + format.extension;

        PENDING_JOBS.put(requestCode, new ExportJob(content, fileName, format.mimeType));

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(format.mimeType);
        // ACTION_CREATE_DOCUMENT 用 EXTRA_TITLE 指定初始文件名
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        activity.startActivityForResult(intent, requestCode);
    }

    private static String buildContent(Context context, Uri noteUri, Format format) {
        Uri uri = noteUri == null ? NotePad.Notes.CONTENT_URI : noteUri;
        String sort = noteUri == null ? NotePad.Notes.DEFAULT_SORT_ORDER : null;
        Cursor cursor = context.getContentResolver().query(uri, PROJECTION, null, null, sort);
        StringBuilder plain = new StringBuilder();
        StringBuilder markdown = new StringBuilder();
        StringBuilder htmlBody = new StringBuilder();
        JSONArray jsonNotes = new JSONArray();

        try {
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    long id = cursor.getLong(0);
                    String title = safe(cursor.getString(1));
                    String note = safe(cursor.getString(2));
                    long created = cursor.getLong(3);
                    long modified = cursor.getLong(4);
                    boolean done = cursor.getInt(5) != 0;

                    String timeInfo = "创建：" + formatTime(created)
                            + "　修改：" + formatTime(modified)
                            + "　状态：" + (done ? "已完成" : "待完成");

                    plain.append("标题：").append(title).append('\n')
                            .append(timeInfo).append("\n\n")
                            .append(note).append("\n\n")
                            .append("------------------------------\n\n");

                    markdown.append("# ").append(title).append("\n\n")
                            .append("> ").append(timeInfo).append("\n\n")
                            .append(note).append("\n\n")
                            .append("---\n\n");

                    htmlBody.append("<article><h2>").append(escapeHtml(title))
                            .append("</h2><p class=\"meta\">").append(escapeHtml(timeInfo))
                            .append("</p><div class=\"content\">")
                            .append(escapeHtml(note).replace("\n", "<br/>"))
                            .append("</div></article>\n");

                    JSONObject item = new JSONObject();
                    try {
                        item.put("id", id);
                        item.put("title", title);
                        item.put("note", note);
                        item.put("created", created);
                        item.put("modified", modified);
                        item.put("done", done ? 1 : 0);
                    } catch (Exception ignored) {
                        // JSONObject.put 对基本类型不会失败
                    }
                    jsonNotes.put(item);
                } while (cursor.moveToNext());
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        switch (format) {
            case MD:
                return markdown.toString();
            case HTML:
                return "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
                        + "<title>" + escapeHtml(context.getString(R.string.app_name))
                        + " 笔记导出</title><style>body{font-family:system-ui,sans-serif;"
                        + "max-width:760px;margin:40px auto;padding:0 20px;color:#333;line-height:1.7}"
                        + "article{border-bottom:1px solid #eee;padding-bottom:24px;margin-bottom:24px}"
                        + ".meta{color:#999;font-size:13px}.content{white-space:normal}</style>"
                        + "</head><body>" + htmlBody + "</body></html>";
            case JSON:
                JSONObject root = new JSONObject();
                try {
                    root.put("format", "notepad-export");
                    root.put("version", 1);
                    root.put("exportedAt", System.currentTimeMillis());
                    root.put("count", jsonNotes.length());
                    root.put("notes", jsonNotes);
                } catch (Exception ignored) {
                }
                try {
                    return root.toString(2);
                } catch (Exception e) {
                    return jsonNotes.toString();
                }
            case TXT:
            default:
                return plain.toString();
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String formatTime(long millis) {
        if (millis <= 0) {
            return "-";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date(millis));
    }

    private static String escapeHtml(String text) {
        return safe(text)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
