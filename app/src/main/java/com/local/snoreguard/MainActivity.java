package com.local.snoreguard;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Build;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private TextView status;
    private TextView details;
    private TextView volume;
    private TextView sensitivityValue;
    private TextView stepValue;
    private TextView capValue;
    private TextView quietValue;
    private Button start;
    private Button stop;
    private Button logButton;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra("state", SnoreService.STATE_STOPPED);
            status.setText(intent.getStringExtra("message"));
            int current = intent.getIntExtra("volume", 0);
            int max = intent.getIntExtra("maxVolume", 15);
            volume.setText("媒体音量 " + current + " / " + max);
            details.setText(intent.getStringExtra("mic") + " · 本次音量调整 "
                    + intent.getIntExtra("events", 0) + " 次");
            start.setEnabled(!SnoreService.busy);
            stop.setEnabled(SnoreService.busy);
            logButton.setEnabled(true);
            if (state == SnoreService.STATE_ERROR) Toast.makeText(MainActivity.this,
                    intent.getStringExtra("message"), Toast.LENGTH_LONG).show();
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        details = findViewById(R.id.details);
        volume = findViewById(R.id.volume);
        sensitivityValue = findViewById(R.id.sensitivityValue);
        stepValue = findViewById(R.id.stepValue);
        capValue = findViewById(R.id.capValue);
        quietValue = findViewById(R.id.quietValue);
        start = findViewById(R.id.startButton);
        stop = findViewById(R.id.stopButton);
        logButton = findViewById(R.id.logButton);
        SeekBar sensitivity = findViewById(R.id.sensitivity);
        SeekBar step = findViewById(R.id.step);
        SeekBar cap = findViewById(R.id.cap);
        SeekBar quiet = findViewById(R.id.quiet);
        CheckBox builtInMic = findViewById(R.id.builtInMic);
        builtInMic.setChecked(Prefs.builtInMic(this));
        builtInMic.setOnCheckedChangeListener((button, checked) -> Prefs.setBuiltInMic(this, checked));
        sensitivity.setMax(4);
        sensitivity.setProgress(Prefs.sensitivity(this) - 1);
        step.setMax(4);
        step.setProgress(Prefs.step(this) - 1);
        cap.setMax(70);
        cap.setProgress(Prefs.cap(this) - 30);
        quiet.setMax(150);
        quiet.setProgress(Prefs.restoreSeconds(this) - 30);
        updateLabels();
        sensitivity.setOnSeekBarChangeListener(listener(v -> { Prefs.setSensitivity(this, v + 1); updateLabels(); }));
        step.setOnSeekBarChangeListener(listener(v -> { Prefs.setStep(this, v + 1); updateLabels(); }));
        cap.setOnSeekBarChangeListener(listener(v -> { Prefs.setCap(this, v + 30); updateLabels(); }));
        quiet.setOnSeekBarChangeListener(listener(v -> { Prefs.setQuiet(this, v + 30); updateLabels(); }));
        findViewById(R.id.privacyButton).setOnClickListener(v -> showPrivacy());
        logButton.setOnClickListener(v -> startActivity(new Intent(this, LogActivity.class)));
        start.setOnClickListener(v -> requestStart());
        stop.setOnClickListener(v -> {
            stop.setEnabled(false);
            status.setText("正在停止检测并逐格恢复音量");
            startService(new Intent(this, SnoreService.class).setAction(SnoreService.ACTION_STOP));
        });
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}, 20);
        refreshVolume();
    }

    private interface IntAction { void run(int value); }
    private SeekBar.OnSeekBarChangeListener listener(IntAction action) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean user) {
                if (user) action.run(value);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        };
    }

    private void updateLabels() {
        sensitivityValue.setText("灵敏度 " + Prefs.sensitivity(this) + " / 5");
        stepValue.setText("目标增幅 " + Prefs.step(this) + " 格 · 每 1.5 秒最多变化 1 格");
        capValue.setText("音量上限 " + Prefs.cap(this) + "%");
        quietValue.setText("连续安静 " + Prefs.restoreSeconds(this) + " 秒后逐格恢复");
    }

    private void requestStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 21);
            return;
        }
        Prefs.setEnabled(this, true);
        start.setEnabled(false);
        stop.setEnabled(true);
        status.setText("正在启动约 2 秒背景 dB 校准");
        startForegroundService(new Intent(this, SnoreService.class).setAction(SnoreService.ACTION_START));
    }

    private void refreshVolume() {
        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        volume.setText("媒体音量 " + manager.getStreamVolume(AudioManager.STREAM_MUSIC) + " / "
                + manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
    }

    private void showPrivacy() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        box.setPadding(padding, 0, padding, 0);
        TextView text = new TextView(this);
        text.setText("仅保存事件时间、dB、音量和麦克风来源，不保存录音。日志最多 500 条，可在日志页导出 TXT。\n\n数据只保存在本机，可在日志页清空。\n\n输入下方英文可清除本应用全部设置：");
        text.setPadding(0, padding / 2, 0, padding);
        EditText input = new EditText(this);
        input.setHint("RESET");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        box.addView(text);
        box.addView(input);
        new AlertDialog.Builder(this).setTitle("隐私与本地日志").setView(box)
                .setNegativeButton("关闭", null)
                .setPositiveButton("清除全部数据", (dialog, which) -> {
                    if ("RESET".equals(input.getText().toString())) {
                        Prefs.clearAll(this);
                        EventLog.clear(this);
                        recreate();
                        Toast.makeText(this, "已清除设置和夜间日志", Toast.LENGTH_SHORT).show();
                    } else Toast.makeText(this, "未清除：请输入大写 RESET", Toast.LENGTH_SHORT).show();
                }).show();
    }

    @Override protected void onResume() {
        super.onResume();
        registerReceiver(receiver, new IntentFilter(SnoreService.ACTION_STATUS), Build.VERSION.SDK_INT >= 33 ? RECEIVER_NOT_EXPORTED : 0);
        start.setEnabled(!SnoreService.busy);
        stop.setEnabled(SnoreService.busy);
        logButton.setEnabled(true);
        if (!SnoreService.busy) {
            status.setText("准备就绪");
            details.setText("网易云可继续播放白噪声 · 默认使用手机内置麦克风");
        }
        refreshVolume();
    }

    @Override protected void onPause() {
        unregisterReceiver(receiver);
        super.onPause();
    }
}
