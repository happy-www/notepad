package com.example.android.notepad;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 备份中心：本机历史备份、保存到云盘（系统文件选择器）、
 * 自建云同步地址的上传/下载，以及从文件或云端恢复。
 */
public class BackupActivity extends Activity {

    private static final int REQ_SAVE_DOCUMENT = 4001;
    private static final int REQ_OPEN_DOCUMENT = 4002;
    private static final String MODE_UPLOAD = "upload";
    private static final String MODE_DOWNLOAD = "download";

    private static String sPendingJson;

    private TextView mStatus;
    private ListView mBackupList;
    private ArrayAdapter<NoteBackupManager.BackupInfo> mAdapter;
    private List<NoteBackupManager.BackupInfo> mBackups;
    private ProgressDialog mProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_backup);

        mStatus = (TextView) findViewById(R.id.backup_status);
        mBackupList = (ListView) findViewById(R.id.backups_list);
        mBackups = new ArrayList<NoteBackupManager.BackupInfo>();
        mAdapter = new ArrayAdapter<NoteBackupManager.BackupInfo>(
                this, R.layout.backup_list_item, R.id.backup_name, mBackups) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                NoteBackupManager.BackupInfo info = getItem(position);
                TextView name = (TextView) view.findViewById(R.id.backup_name);
                name.setText(info.name);
                TextView meta = (TextView) view.findViewById(R.id.backup_meta);
                int count = info.count < 0 ? 0 : info.count;
                meta.setText(getString(R.string.backup_item_meta, count,
                        NoteBackupManager.formatBackupTime(BackupActivity.this, info.time)));
                return view;
            }
        };
        mBackupList.setAdapter(mAdapter);
        mBackupList.setEmptyView(findViewById(android.R.id.empty));
        mBackupList.setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(android.widget.AdapterView<?> parent, View view,
                                    int position, long id) {
                showBackupActions(mBackups.get(position));
            }
        });

        findViewById(R.id.btn_backup_local).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                backupToLocal();
            }
        });
        findViewById(R.id.btn_cloud_save).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveToCloudDrive();
            }
        });
        findViewById(R.id.btn_cloud_config).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showCloudConfigDialog();
            }
        });
        findViewById(R.id.btn_cloud_upload).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CloudResult result = checkCloudConfig();
                if (result.error == null) {
                    new CloudTask().execute(MODE_UPLOAD, result.url, result.token);
                }
            }
        });
        findViewById(R.id.btn_cloud_download).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CloudResult result = checkCloudConfig();
                if (result.error == null) {
                    new CloudTask().execute(MODE_DOWNLOAD, result.url, result.token);
                }
            }
        });
        findViewById(R.id.btn_file_restore).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openBackupFile();
            }
        });
        findViewById(R.id.btn_share_latest).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                shareLatest();
            }
        });

        refresh();
    }

    private void refresh() {
        mBackups.clear();
        mBackups.addAll(NoteBackupManager.listLocalBackups(this));
        mAdapter.notifyDataSetChanged();
        if (mBackups.isEmpty()) {
            mStatus.setText(R.string.backup_status_empty);
        } else {
            mStatus.setText(getString(R.string.backup_status, mBackups.size(),
                    NoteBackupManager.formatBackupTime(this, mBackups.get(0).time)));
        }
    }

    private void backupToLocal() {
        try {
            String json = NoteBackupManager.createBackupJson(this);
            File file = NoteBackupManager.writeLocalBackup(this, json);
            setResult(RESULT_OK);
            refresh();
            toast(getString(R.string.backup_done, file.getName()));
        } catch (NoteBackupManager.BackupException e) {
            toast(getString(R.string.backup_failed, e.getMessage()));
        }
    }

    private void saveToCloudDrive() {
        try {
            sPendingJson = NoteBackupManager.createBackupJson(this);
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(new Date());
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "notepad_backup_" + stamp + ".json");
            startActivityForResult(intent, REQ_SAVE_DOCUMENT);
        } catch (NoteBackupManager.BackupException e) {
            toast(getString(R.string.backup_failed, e.getMessage()));
        }
    }

    private void openBackupFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, getString(R.string.backup_pick_hint));
        startActivityForResult(intent, REQ_OPEN_DOCUMENT);
    }

    private void shareLatest() {
        if (mBackups.isEmpty()) {
            toast(R.string.backup_status_empty);
            return;
        }
        try {
            String json = NoteBackupManager.readFile(mBackups.get(0).file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.backup_title));
            intent.putExtra(Intent.EXTRA_TEXT, json);
            startActivity(Intent.createChooser(intent, getString(R.string.backup_share_latest)));
        } catch (NoteBackupManager.BackupException e) {
            toast(e.getMessage());
        }
    }

    private void showBackupActions(final NoteBackupManager.BackupInfo info) {
        String[] actions = new String[] {
                getString(R.string.backup_merge),
                getString(R.string.backup_share_latest),
                getString(R.string.backup_delete)
        };
        new AlertDialog.Builder(this)
                .setTitle(info.name)
                .setItems(actions, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            try {
                                NoteBackupManager.BackupData data = NoteBackupManager.parseBackup(
                                        NoteBackupManager.readFile(info.file));
                                confirmRestore(data);
                            } catch (NoteBackupManager.BackupException e) {
                                showRestoreError(e.getMessage());
                            }
                        } else if (which == 1) {
                            try {
                                Intent intent = new Intent(Intent.ACTION_SEND);
                                intent.setType("text/plain");
                                intent.putExtra(Intent.EXTRA_SUBJECT, info.name);
                                intent.putExtra(Intent.EXTRA_TEXT,
                                        NoteBackupManager.readFile(info.file));
                                startActivity(Intent.createChooser(intent, info.name));
                            } catch (NoteBackupManager.BackupException e) {
                                toast(e.getMessage());
                            }
                        } else {
                            if (NoteBackupManager.deleteBackup(info)) {
                                toast(R.string.backup_deleted);
                                refresh();
                            } else {
                                toast(getString(R.string.backup_failed, "未知原因"));
                            }
                        }
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmRestore(final NoteBackupManager.BackupData data) {
        String message = getString(R.string.backup_restore_message,
                NoteBackupManager.formatBackupTime(this, data.createdAt), data.notes.size());
        new AlertDialog.Builder(this)
                .setTitle(R.string.backup_restore_title)
                .setMessage(message)
                .setPositiveButton(R.string.backup_merge, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        doRestore(data, false);
                    }
                })
                .setNeutralButton(R.string.backup_replace, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        confirmReplace(data);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmReplace(final NoteBackupManager.BackupData data) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.backup_restore_title)
                .setMessage(R.string.backup_replace)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        doRestore(data, true);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void doRestore(NoteBackupManager.BackupData data, boolean clearFirst) {
        int[] result = NoteBackupManager.restore(this, data, clearFirst);
        setResult(RESULT_OK);
        refresh();
        toast(getString(R.string.backup_restore_done, result[0], result[1]));
    }

    private void showCloudConfigDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = 16;
        layout.setPadding(padding, padding, padding, padding);

        final EditText urlInput = new EditText(this);
        urlInput.setHint(R.string.cloud_url_hint);
        urlInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlInput.setText(ThemeManager.getCloudUrl(this));
        layout.addView(urlInput);

        final EditText tokenInput = new EditText(this);
        tokenInput.setHint(R.string.cloud_token_hint);
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setText(ThemeManager.getCloudToken(this));
        layout.addView(tokenInput);

        new AlertDialog.Builder(this)
                .setTitle(R.string.cloud_config_title)
                .setMessage(R.string.cloud_config_message)
                .setView(layout)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ThemeManager.saveCloudConfig(BackupActivity.this,
                                urlInput.getText().toString().trim(),
                                tokenInput.getText().toString().trim());
                        toast(R.string.cloud_saved);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private CloudResult checkCloudConfig() {
        CloudResult result = new CloudResult();
        result.url = ThemeManager.getCloudUrl(this);
        result.token = ThemeManager.getCloudToken(this);
        if (result.url.length() == 0) {
            result.error = getString(R.string.cloud_not_set);
            toast(result.error);
        }
        return result;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_SAVE_DOCUMENT && sPendingJson != null) {
            try {
                OutputStream out = getContentResolver().openOutputStream(uri);
                if (out == null) {
                    throw new IllegalStateException("无法写入文件");
                }
                try {
                    out.write(sPendingJson.getBytes("UTF-8"));
                    out.flush();
                } finally {
                    out.close();
                }
                toast(getString(R.string.export_done, String.valueOf(uri.getLastPathSegment())));
            } catch (Exception e) {
                toast(getString(R.string.export_failed, e.getMessage()));
            } finally {
                sPendingJson = null;
            }
        } else if (requestCode == REQ_OPEN_DOCUMENT) {
            try {
                NoteBackupManager.BackupData backup = NoteBackupManager.parseBackup(
                        NoteBackupManager.readUri(this, uri));
                confirmRestore(backup);
            } catch (NoteBackupManager.BackupException e) {
                showRestoreError(e.getMessage());
            }
        }
    }

    /** 恢复失败时用对话框展示完整原因与引导（Toast 行数有限，会截断长文案）。 */
    private void showRestoreError(String detail) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.backup_invalid)
                .setMessage(getString(R.string.backup_restore_invalid, detail))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_LONG).show();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static class CloudResult {
        String url;
        String token;
        String error;
    }

    private static class CloudTaskResult {
        boolean ok;
        String message;
        NoteBackupManager.BackupData data;
    }

    /** 网络请求放到后台线程，避免阻塞 UI。 */
    private class CloudTask extends AsyncTask<String, Void, CloudTaskResult> {

        @Override
        protected void onPreExecute() {
            mProgress = ProgressDialog.show(BackupActivity.this, "",
                    getString(R.string.cloud_uploading), true, false);
        }

        @Override
        protected CloudTaskResult doInBackground(String... params) {
            CloudTaskResult result = new CloudTaskResult();
            try {
                if (MODE_UPLOAD.equals(params[0])) {
                    String json = NoteBackupManager.createBackupJson(BackupActivity.this);
                    NoteBackupManager.uploadToCloud(params[1], params[2], json);
                    result.message = getString(R.string.cloud_upload_done);
                } else {
                    String json = NoteBackupManager.downloadFromCloud(params[1], params[2]);
                    result.data = NoteBackupManager.parseBackup(json);
                    result.message = getString(R.string.cloud_download_done);
                }
                result.ok = true;
            } catch (Exception e) {
                result.ok = false;
                result.message = e.getMessage();
            }
            return result;
        }

        @Override
        protected void onPostExecute(CloudTaskResult result) {
            if (mProgress != null && mProgress.isShowing()) {
                mProgress.dismiss();
            }
            if (result.ok) {
                toast(result.message);
                if (result.data != null) {
                    confirmRestore(result.data);
                }
            } else {
                toast(getString(R.string.backup_failed, result.message));
            }
        }
    }
}
