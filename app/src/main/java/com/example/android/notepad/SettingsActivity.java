package com.example.android.notepad;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 主题与正文字号设置页。保存后立即生效，返回列表时由调用方刷新界面。
 */
public class SettingsActivity extends Activity {

    private RadioGroup mThemeGroup;
    private SeekBar mSizeSeek;
    private TextView mSizeLabel;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        mThemeGroup = (RadioGroup) findViewById(R.id.theme_group);
        mSizeSeek = (SeekBar) findViewById(R.id.size_seek);
        mSizeLabel = (TextView) findViewById(R.id.size_label);

        mThemeGroup.check(themeViewId(ThemeManager.getTheme(this)));
        mSizeSeek.setMax(ThemeManager.sizeToProgress(26));
        mSizeSeek.setProgress(ThemeManager.sizeToProgress(ThemeManager.getTextSize(this)));
        updateSizeLabel();

        mSizeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateSizeLabel();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        findViewById(R.id.btn_apply).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ThemeManager.save(SettingsActivity.this, selectedTheme(), mSizeSeek.getProgress());
                setResult(RESULT_OK);
                Toast.makeText(SettingsActivity.this, R.string.settings_saved,
                        Toast.LENGTH_SHORT).show();
                recreate();
            }
        });

        findViewById(R.id.btn_reset).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ThemeManager.reset(SettingsActivity.this);
                mThemeGroup.check(R.id.theme_pink);
                mSizeSeek.setProgress(ThemeManager.sizeToProgress(ThemeManager.DEFAULT_TEXT_SIZE));
                setResult(RESULT_OK);
                Toast.makeText(SettingsActivity.this, R.string.settings_saved,
                        Toast.LENGTH_SHORT).show();
                recreate();
            }
        });
    }

    private void updateSizeLabel() {
        mSizeLabel.setText(getString(R.string.settings_text_size) + "   "
                + ThemeManager.progressToSize(mSizeSeek.getProgress()) + "sp");
    }

    private int selectedTheme() {
        int checked = mThemeGroup.getCheckedRadioButtonId();
        if (checked == R.id.theme_mint) {
            return ThemeManager.THEME_MINT;
        }
        if (checked == R.id.theme_paper) {
            return ThemeManager.THEME_PAPER;
        }
        if (checked == R.id.theme_night) {
            return ThemeManager.THEME_NIGHT;
        }
        return ThemeManager.THEME_PINK;
    }

    private static int themeViewId(int theme) {
        switch (theme) {
            case ThemeManager.THEME_MINT:
                return R.id.theme_mint;
            case ThemeManager.THEME_PAPER:
                return R.id.theme_paper;
            case ThemeManager.THEME_NIGHT:
                return R.id.theme_night;
            case ThemeManager.THEME_PINK:
            default:
                return R.id.theme_pink;
        }
    }
}
