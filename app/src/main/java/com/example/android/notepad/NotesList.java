/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.notepad;

import android.app.ListActivity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.ContextMenu;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 笔记列表页：时间戳、标题/内容即时搜索、主题切换入口、导出入口与备份中心入口。
 */
public class NotesList extends ListActivity {

    private static final String TAG = "NotesList";

    private static final int REQ_EXPORT_ALL = 3001;
    private static final int REQ_EXPORT_ONE = 3002;
    private static final int REQ_SETTINGS = 3003;
    private static final int REQ_BACKUP = 3004;

    private static final String[] PROJECTION = new String[] {
            NotePad.Notes._ID,                          // 0
            NotePad.Notes.COLUMN_NAME_TITLE,            // 1
            NotePad.Notes.COLUMN_NAME_NOTE,             // 2
            NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE,// 3
            NotePad.Notes.COLUMN_NAME_DONE              // 4
    };

    private static final int COLUMN_INDEX_TITLE = 1;
    private static final int COLUMN_INDEX_CONTENT = 2;
    private static final int COLUMN_INDEX_MODIFIED = 3;
    private static final int COLUMN_INDEX_DONE = 4;

    // 待办筛选：0=全部 1=待完成 2=已完成
    private static final int FILTER_ALL = 0;
    private static final int FILTER_PENDING = 1;
    private static final int FILTER_DONE = 2;

    private SimpleCursorAdapter mAdapter;
    private String mKeyword = "";
    private int mTodoFilter = FILTER_ALL;
    private TextView mTodoStatus;
    private TextView[] mFilterChips;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setDefaultKeyMode(DEFAULT_KEYS_SHORTCUT);
        setContentView(R.layout.notes_list);

        Intent intent = getIntent();
        if (intent.getData() == null) {
            intent.setData(NotePad.Notes.CONTENT_URI);
        }
        getListView().setOnCreateContextMenuListener(this);

        // 待办筛选栏：三个标签 + 状态统计
        mTodoStatus = (TextView) findViewById(R.id.todo_status);
        mFilterChips = new TextView[] {
                (TextView) findViewById(R.id.todo_filter_all),
                (TextView) findViewById(R.id.todo_filter_pending),
                (TextView) findViewById(R.id.todo_filter_done)
        };
        for (int i = 0; i < mFilterChips.length; i++) {
            mFilterChips[i].setOnClickListener(new FilterClick(i));
        }

        refreshList("");

        // 用普通 EditText 做搜索框（系统 SearchView 会给输入框强制加 NO_SUGGESTIONS
        // 标志，令中文输入法无法弹出候选词栏，打拼音只出现字母）。
        final EditText searchInput = (EditText) findViewById(R.id.search_input);
        final ImageView clearButton = (ImageView) findViewById(R.id.search_clear);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshList(s.toString());
                clearButton.setVisibility(s.length() > 0 ? View.VISIBLE : View.INVISIBLE);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                searchInput.setText("");
                searchInput.requestFocus();
            }
        });
        searchInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    InputMethodManager imm = (InputMethodManager) getSystemService(
                            Context.INPUT_METHOD_SERVICE);
                    if (imm != null) {
                        imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
                    }
                    return true;
                }
                return false;
            }
        });
    }

    /** 根据关键字与待办筛选条件重新查询列表。 */
    private void refreshList(String keyword) {
        mKeyword = keyword == null ? "" : keyword;

        StringBuilder selection = new StringBuilder();
        java.util.ArrayList<String> args = new java.util.ArrayList<String>();
        if (mKeyword.trim().length() > 0) {
            selection.append("(").append(NotePad.Notes.COLUMN_NAME_TITLE).append(" LIKE ? OR ")
                    .append(NotePad.Notes.COLUMN_NAME_NOTE).append(" LIKE ?)");
            String kw = "%" + mKeyword.trim() + "%";
            args.add(kw);
            args.add(kw);
        }
        if (mTodoFilter != FILTER_ALL) {
            if (selection.length() > 0) {
                selection.append(" AND ");
            }
            selection.append(NotePad.Notes.COLUMN_NAME_DONE).append(" = ?");
            args.add(mTodoFilter == FILTER_DONE ? "1" : "0");
        }

        Cursor cursor = managedQuery(
                NotePad.Notes.CONTENT_URI,
                PROJECTION,
                selection.length() > 0 ? selection.toString() : null,
                args.size() > 0 ? args.toArray(new String[args.size()]) : null,
                // 未完成排前面，方便逐项勾选；同类按修改时间倒序
                NotePad.Notes.COLUMN_NAME_DONE + " ASC, " + NotePad.Notes.DEFAULT_SORT_ORDER);

        if (mAdapter == null) {
            String[] dataColumns = {
                    NotePad.Notes.COLUMN_NAME_TITLE,
                    NotePad.Notes.COLUMN_NAME_NOTE,
                    NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE,
                    NotePad.Notes.COLUMN_NAME_DONE
            };
            int[] viewIDs = {android.R.id.text1, R.id.note_preview, R.id.text2, R.id.todo_check};
            mAdapter = new SimpleCursorAdapter(this, R.layout.noteslist_item,
                    cursor, dataColumns, viewIDs);
            mAdapter.setViewBinder(new SimpleCursorAdapter.ViewBinder() {
                @Override
                public boolean setViewValue(View view, Cursor cursor, int columnIndex) {
                    final boolean done = cursor.getInt(COLUMN_INDEX_DONE) != 0;
                    final long noteId = cursor.getLong(0);

                    if (view.getId() == R.id.todo_check) {
                        CheckBox check = (CheckBox) view;
                        check.setOnCheckedChangeListener(null);
                        check.setChecked(done);
                        check.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                            @Override
                            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                                setNoteDone(noteId, isChecked);
                            }
                        });
                        return true;
                    }
                    if (view.getId() == android.R.id.text1 || view.getId() == R.id.note_preview) {
                        TextView tv = (TextView) view;
                        String value = cursor.getString(columnIndex);
                        if (value == null) {
                            value = "";
                        }
                        if (view.getId() == R.id.note_preview) {
                            value = value.replace('\n', ' ').trim();
                        }
                        tv.setText(value);
                        // 已完成：标题与预览加删除线并降低透明度
                        int flags = done
                                ? tv.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG
                                : tv.getPaintFlags() & ~Paint.STRIKE_THRU_TEXT_FLAG;
                        tv.setPaintFlags(flags);
                        tv.setAlpha(done ? 0.55f : 1.0f);
                        return done; // 返回 done：未完成时交给默认绑定，避免重复设置
                    }
                    if (view.getId() == R.id.text2) {
                        long millis = cursor.getLong(columnIndex);
                        String time = new SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                                .format(new Date(millis));
                        ((TextView) view).setText(time);
                        return true;
                    }
                    return false;
                }
            });
            setListAdapter(mAdapter);
        } else {
            mAdapter.swapCursor(cursor);
        }

        updateTodoStatus();
        updateFilterChips();
    }

    /** 统计待办与已完成数量，更新顶部状态文本。 */
    private void updateTodoStatus() {
        if (mTodoStatus == null) {
            return;
        }
        int pending = countNotes(0);
        int done = countNotes(1);
        mTodoStatus.setText(getString(R.string.todo_status, pending, done));
    }

    private int countNotes(int doneValue) {
        Cursor c = getContentResolver().query(
                NotePad.Notes.CONTENT_URI,
                new String[] {NotePad.Notes._ID},
                NotePad.Notes.COLUMN_NAME_DONE + " = ?",
                new String[] {String.valueOf(doneValue)},
                null);
        if (c == null) {
            return 0;
        }
        try {
            return c.getCount();
        } finally {
            c.close();
        }
    }

    /** 高亮当前选中的筛选标签。 */
    private void updateFilterChips() {
        if (mFilterChips == null) {
            return;
        }
        for (int i = 0; i < mFilterChips.length; i++) {
            boolean selected = (i == mTodoFilter);
            mFilterChips[i].setTextColor(getResources().getColor(
                    selected ? R.color.accent : R.color.hint_pink));
        }
    }

    private class FilterClick implements View.OnClickListener {
        private final int mValue;

        FilterClick(int value) {
            mValue = value;
        }

        @Override
        public void onClick(View v) {
            mTodoFilter = mValue;
            refreshList(mKeyword);
        }
    }

    /** 直接切换某条笔记的完成状态。 */
    private void setNoteDone(long noteId, boolean done) {
        android.net.Uri uri = ContentUris.withAppendedId(
                NotePad.Notes.CONTENT_URI, noteId);
        ContentValues values = new ContentValues();
        values.put(NotePad.Notes.COLUMN_NAME_DONE, done ? 1 : 0);
        values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, System.currentTimeMillis());
        getContentResolver().update(uri, values, null, null);
        refreshList(mKeyword);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从编辑器返回时待办状态可能已变化，刷新列表与统计
        if (mAdapter != null) {
            refreshList(mKeyword);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.list_options_menu, menu);

        Intent intent = new Intent(null, getIntent().getData());
        intent.addCategory(Intent.CATEGORY_ALTERNATIVE);
        menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, 0, 0,
                new ComponentName(this, NotesList.class), null, intent, 0, null);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        MenuItem pasteItem = menu.findItem(R.id.menu_paste);
        pasteItem.setEnabled(clipboard.hasPrimaryClip());

        final boolean haveItems = getListAdapter() != null && getListAdapter().getCount() > 0;
        if (haveItems) {
            Uri uri = ContentUris.withAppendedId(getIntent().getData(), getSelectedItemId());
            Intent[] specifics = new Intent[] {new Intent(Intent.ACTION_EDIT, uri)};
            MenuItem[] items = new MenuItem[1];
            Intent alternatives = new Intent(null, uri);
            alternatives.addCategory(Intent.CATEGORY_ALTERNATIVE);
            menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, Menu.NONE, Menu.NONE,
                    null, specifics, alternatives, Menu.NONE, items);
            if (items[0] != null) {
                items[0].setShortcut('1', 'e');
            }
        } else {
            menu.removeGroup(Menu.CATEGORY_ALTERNATIVE);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_add) {
            startActivity(new Intent(Intent.ACTION_INSERT, getIntent().getData()));
            return true;
        } else if (id == R.id.menu_paste) {
            startActivity(new Intent(Intent.ACTION_PASTE, getIntent().getData()));
            return true;
        } else if (id == R.id.menu_export) {
            NoteExportManager.showExportDialog(this, null, REQ_EXPORT_ALL);
            return true;
        } else if (id == R.id.menu_backup) {
            startActivityForResult(new Intent(this, BackupActivity.class), REQ_BACKUP);
            return true;
        } else if (id == R.id.menu_settings) {
            startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXPORT_ALL || requestCode == REQ_EXPORT_ONE) {
            NoteExportManager.handleResult(this, requestCode, resultCode, data);
        } else if (resultCode == RESULT_OK && requestCode == REQ_BACKUP) {
            refreshList(mKeyword);
        } else if (requestCode == REQ_SETTINGS) {
            // 设置页可能自行 recreate 以预览主题，返回时无条件重建列表以应用最新偏好。
            recreate();
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenuInfo menuInfo) {
        AdapterView.AdapterContextMenuInfo info;
        try {
            info = (AdapterView.AdapterContextMenuInfo) menuInfo;
        } catch (ClassCastException e) {
            Log.e(TAG, "bad menuInfo", e);
            return;
        }

        Cursor cursor = (Cursor) getListAdapter().getItem(info.position);
        if (cursor == null) {
            return;
        }

        getMenuInflater().inflate(R.menu.list_context_menu, menu);
        menu.setHeaderTitle(cursor.getString(COLUMN_INDEX_TITLE));

        // 长按菜单的完成项标题随当前状态变化
        MenuItem toggle = menu.findItem(R.id.context_toggle_done);
        if (toggle != null) {
            boolean done = cursor.getInt(COLUMN_INDEX_DONE) != 0;
            toggle.setTitle(done ? R.string.todo_mark_undone : R.string.todo_mark_done);
        }

        Intent intent = new Intent(null,
                Uri.withAppendedPath(getIntent().getData(), Integer.toString((int) info.id)));
        intent.addCategory(Intent.CATEGORY_ALTERNATIVE);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, 0, 0,
                new ComponentName(this, NotesList.class), null, intent, 0, null);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterView.AdapterContextMenuInfo info;
        try {
            info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        } catch (ClassCastException e) {
            Log.e(TAG, "bad menuInfo", e);
            return false;
        }
        Uri noteUri = ContentUris.withAppendedId(getIntent().getData(), info.id);

        int id = item.getItemId();
        if (id == R.id.context_toggle_done) {
            // 读取当前完成状态并取反
            Cursor c = getContentResolver().query(noteUri,
                    new String[] {NotePad.Notes.COLUMN_NAME_DONE}, null, null, null);
            boolean done = false;
            if (c != null) {
                if (c.moveToFirst()) {
                    done = c.getInt(0) != 0;
                }
                c.close();
            }
            setNoteDone(info.id, !done);
            return true;
        }
        if (id == R.id.context_open) {
            startActivity(new Intent(Intent.ACTION_EDIT, noteUri));
            return true;
        } else if (id == R.id.context_copy) {
            ClipboardManager clipboard = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newUri(getContentResolver(), "Note", noteUri));
            return true;
        } else if (id == R.id.context_delete) {
            getContentResolver().delete(noteUri, null, null);
            refreshList(mKeyword);
            return true;
        } else if (id == R.id.context_export) {
            NoteExportManager.showExportDialog(this, noteUri, REQ_EXPORT_ONE);
            return true;
        }
        return super.onContextItemSelected(item);
    }

    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {
        Uri uri = ContentUris.withAppendedId(getIntent().getData(), id);
        String action = getIntent().getAction();
        if (Intent.ACTION_PICK.equals(action) || Intent.ACTION_GET_CONTENT.equals(action)) {
            setResult(RESULT_OK, new Intent().setData(uri));
        } else {
            startActivity(new Intent(Intent.ACTION_EDIT, uri));
        }
    }
}
