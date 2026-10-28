package com.local.snoreguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class LogActivity extends Activity {
    private static final int CREATE_LOG = 50;
    private ListView list;
    private TextView summary;
    private TextView empty;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_log);
        list = findViewById(R.id.logList);
        summary = findViewById(R.id.logSummary);
        empty = findViewById(R.id.logEmpty);
        findViewById(R.id.backButton).setOnClickListener(v -> finish());
        findViewById(R.id.exportButton).setOnClickListener(v -> exportLog());
        findViewById(R.id.clearButton).setOnClickListener(v -> confirmClear());
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        List<String> rows = EventLog.formatted(this);
        summary.setText("共 " + rows.size() + " 条 · 最多保留 500 条 · 不保存录音");
        list.setAdapter(new ArrayAdapter<>(this, R.layout.item_log, R.id.logLine, rows));
        empty.setVisibility(rows.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void exportLog() {
        if (EventLog.formatted(this).isEmpty()) {
            Toast.makeText(this, "当前没有可导出的日志", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TITLE, "鼾声守护-" + new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(new java.util.Date()) + ".txt");
        startActivityForResult(intent, CREATE_LOG);
    }

    private void confirmClear() {
        new AlertDialog.Builder(this).setTitle("清空夜间日志？")
                .setMessage("此操作不可撤销；应用设置不会受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (dialog, which) -> {
                    EventLog.clear(this);
                    refresh();
                    Toast.makeText(this, "夜间日志已清空", Toast.LENGTH_SHORT).show();
                }).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != CREATE_LOG || result != RESULT_OK || data == null) return;
        Uri uri = data.getData();
        if (uri == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
            if (output == null) throw new IllegalStateException("No output stream");
            output.write(EventLog.export(this).getBytes(StandardCharsets.UTF_8));
            output.flush();
            Toast.makeText(this, "日志已导出为 TXT", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "导出失败，请重新选择保存位置", Toast.LENGTH_LONG).show();
        }
    }
}
