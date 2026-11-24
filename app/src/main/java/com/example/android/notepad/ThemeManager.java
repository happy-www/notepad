package com.example.android.notepad;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

/**
 * 主题与字号偏好设置。
 *
 * <p>所有 Activity 在 {@code super.onCreate()} 之前调用 {@link #applyTheme(Activity)}，
 * 让自定义主题属性（颜色、卡片、分隔线等）在布局 inflate 时就生效。</p>
 */
public final class ThemeManager {

    public static final String PREF_NAME = "notepad_prefs";
    public static final String KEY_THEME = "theme";
    public static final String KEY_TEXT_SIZE = "text_size";

    public static final int THEME_PINK = 0;
    public static final int THEME_MINT = 1;
    public static final int THEME_PAPER = 2;
    public static final int THEME_NIGHT = 3;

    public static final int DEFAULT_TEXT_SIZE = 22;
    private static final int MIN_TEXT_SIZE = 14;
    private static final int MAX_TEXT_SIZE = 26;

    private ThemeManager() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 在 Activity.onCreate 中、super.onCreate 之前调用。 */
    public static void applyTheme(Activity activity) {
        activity.setTheme(styleForTheme(getTheme(activity)));
    }

    public static int getTheme(Context context) {
        return prefs(context).getInt(KEY_THEME, THEME_PINK);
    }

    /** SeekBar 的进度对应主题编号，范围 0-3。 */
    public static int styleForTheme(int theme) {
        switch (theme) {
            case THEME_MINT:
                return R.style.ThemeMint;
            case THEME_PAPER:
                return R.style.ThemePaper;
            case THEME_NIGHT:
                return R.style.ThemeNight;
            case THEME_PINK:
            default:
                return R.style.AppTheme;
        }
    }

    /** 对话框风格的主题，保持 Holo.Dialog 的浮层形态。 */
    public static int dialogStyleForTheme(int theme) {
        switch (theme) {
            case THEME_MINT:
                return R.style.DialogMint;
            case THEME_PAPER:
                return R.style.DialogPaper;
            case THEME_NIGHT:
                return R.style.DialogNight;
            case THEME_PINK:
            default:
                return R.style.DialogPink;
        }
    }

    public static void applyDialogTheme(Activity activity) {
        activity.setTheme(dialogStyleForTheme(getTheme(activity)));
    }

    /** 正文字号（sp），限制在 14-26 之间，避免极端值破坏布局。 */
    public static int getTextSize(Context context) {
        int size = prefs(context).getInt(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE);
        if (size < MIN_TEXT_SIZE || size > MAX_TEXT_SIZE) {
            return DEFAULT_TEXT_SIZE;
        }
        return size;
    }

    public static int sizeToProgress(int textSize) {
        return textSize - MIN_TEXT_SIZE;
    }

    public static int progressToSize(int progress) {
        return Math.min(MAX_TEXT_SIZE, Math.max(MIN_TEXT_SIZE, progress + MIN_TEXT_SIZE));
    }

    public static void save(Context context, int theme, int textSize) {
        prefs(context).edit()
                .putInt(KEY_THEME, theme)
                .putInt(KEY_TEXT_SIZE, progressToSize(textSize))
                .apply();
    }

    public static void reset(Context context) {
        prefs(context).edit()
                .putInt(KEY_THEME, THEME_PINK)
                .putInt(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE)
                .apply();
    }

    public static void saveCloudConfig(Context context, String url, String token) {
        prefs(context).edit()
                .putString(NoteBackupManager.KEY_CLOUD_URL, url)
                .putString(NoteBackupManager.KEY_CLOUD_TOKEN, token)
                .apply();
    }

    public static String getCloudUrl(Context context) {
        return prefs(context).getString(NoteBackupManager.KEY_CLOUD_URL, "");
    }

    public static String getCloudToken(Context context) {
        return prefs(context).getString(NoteBackupManager.KEY_CLOUD_TOKEN, "");
    }
}
