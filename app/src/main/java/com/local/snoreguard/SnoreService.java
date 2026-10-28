package com.local.snoreguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.Locale;

public final class SnoreService extends Service {
    public static final String ACTION_START = "com.local.snoreguard.START";
    public static final String ACTION_STOP = "com.local.snoreguard.STOP";
    public static final String ACTION_STATUS = "com.local.snoreguard.STATUS";
    public static final int STATE_STOPPED = 0;
    public static final int STATE_CALIBRATING = 1;
    public static final int STATE_LISTENING = 2;
    public static final int STATE_SNORE = 3;
    public static final int STATE_ERROR = 4;
    public static volatile boolean running = false;
    public static volatile boolean busy = false;

    private static final String CHANNEL = "snore_detection";
    private static final int NOTIFICATION_ID = 41;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final VolumeRamp ramp = new VolumeRamp();
    private AudioManager audioManager;
    private PowerManager.WakeLock wakeLock;
    private volatile AudioRecord recorder;
    private int originalVolume;
    private int lastSetVolume = -1;
    private int eventCount;
    private boolean adjustedByUs;
    private boolean restoring;
    private boolean stopAfterRestore;
    private int pendingStopState = STATE_STOPPED;
    private String pendingStopMessage = "检测已停止";
    private boolean calibrationLogged;
    private long lastSnoreAt;
    private long lastAdjustmentAt;
    private long lastUiUpdate;
    private String micName = "手机内置麦克风";

    private final Runnable quietChecker = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (adjustedByUs && !restoring && SystemClock.elapsedRealtime() - lastSnoreAt
                    >= Prefs.restoreSeconds(SnoreService.this) * 1000L) {
                beginRestore(false);
            }
            main.postDelayed(this, 1000);
        }
    };

    private final Runnable rampStep = new Runnable() {
        @Override public void run() {
            if (!ramp.isActive()) {
                if (restoring) finishRestore();
                return;
            }
            int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (lastSetVolume >= 0 && current != lastSetVolume) {
                ramp.cancel();
                restoring = false;
                adjustedByUs = false;
                originalVolume = current;
                lastSetVolume = -1;
                EventLog.add(SnoreService.this, "手动音量", "检测到你手动调整，已取消自动渐变并采用新音量 " + current);
                if (stopAfterRestore) finishStop(STATE_STOPPED, "已停止检测，保留你手动设置的音量");
                return;
            }
            int next = ramp.next(current, SystemClock.elapsedRealtime());
            if (next != current) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, next, 0);
                lastSetVolume = next;
                adjustedByUs = true;
                EventLog.add(SnoreService.this, restoring ? "音量恢复" : "音量提高",
                        "媒体音量 " + current + " → " + next + "（单格渐变）");
                sendStatus(restoring ? STATE_LISTENING : STATE_SNORE,
                        (restoring ? "正在缓慢恢复 · " : "正在缓慢提高 · ") + next + " 格");
            }
            if (ramp.isActive()) main.postDelayed(this, VolumeRamp.STEP_INTERVAL_MS);
            else if (restoring) finishRestore();
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "鼾声检测", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示手机麦克风检测与媒体音量调整状态");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            requestStop(STATE_STOPPED, "检测已停止，媒体音量已逐格恢复");
            return START_NOT_STICKY;
        }
        if (!busy) startDetection();
        return START_NOT_STICKY;
    }

    private void startDetection() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            finishStop(STATE_ERROR, "缺少麦克风权限，请从应用内重新启动");
            return;
        }
        startForeground(NOTIFICATION_ID, notification("正在准备手机麦克风"));
        originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        lastSetVolume = -1;
        adjustedByUs = false;
        restoring = false;
        stopAfterRestore = false;
        calibrationLogged = false;
        eventCount = 0;
        busy = true;
        running = true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SnoreGuard:Detection");
        wakeLock.acquire();
        EventLog.add(this, "开始检测", "初始媒体音量 " + originalVolume + "，开始约 2 秒背景 dB 校准");
        sendStatus(STATE_CALIBRATING, "正在采集约 2 秒背景声音并计算平均 dB");
        new Thread(this::captureAudio, "snore-audio").start();
        main.postDelayed(quietChecker, 1000);
    }

    private void captureAudio() {
        final int sampleRate = 16000;
        final int frameSize = 1024;
        boolean builtInRequested = Prefs.builtInMic(this);
        AudioDeviceInfo builtIn = null;
        if (builtInRequested) {
            for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                    builtIn = device;
                    break;
                }
            }
            if (builtIn == null) {
                fail("没有找到手机内置麦克风，已停止检测");
                return;
            }
        }
        int minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            fail("当前手机不支持所需录音格式");
            return;
        }
        AudioRecord record;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuffer * 2, frameSize * 4));
        } catch (SecurityException | IllegalArgumentException e) {
            fail("无法打开麦克风，请检查系统权限");
            return;
        }
        recorder = record;
        if (builtInRequested && !record.setPreferredDevice(builtIn)) {
            fail("手机系统不允许指定内置麦克风，已停止检测");
            return;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            fail("麦克风初始化失败，可能正被其他应用占用");
            return;
        }
        try {
            record.startRecording();
        } catch (IllegalStateException | SecurityException e) {
            fail("麦克风启动失败，请关闭占用录音的应用后重试");
            return;
        }

        SnoreDetector detector = new SnoreDetector(Prefs.sensitivity(this));
        short[] buffer = new short[frameSize];
        boolean routeChecked = false;
        try {
            while (running) {
                int read = record.read(buffer, 0, buffer.length);
                if (read <= 0) continue;
                if (!routeChecked) {
                    routeChecked = true;
                    AudioDeviceInfo routed = record.getRoutedDevice();
                    if (builtInRequested && routed != null && routed.getType() != AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                        fail("实际录音被路由到耳机或外部麦克风，已停止检测");
                        return;
                    }
                    if (routed != null) micName = builtInRequested ? "手机内置麦克风" : String.valueOf(routed.getProductName());
                    else if (!builtInRequested) micName = "系统自动选择";
                }
                SnoreDetector.Analysis analysis = detector.process(buffer, read, sampleRate);
                if (analysis.calibrated && !calibrationLogged) {
                    calibrationLogged = true;
                    main.post(() -> EventLog.add(this, "校准完成", String.format(Locale.getDefault(),
                            "32 帧背景平均 %.1f dB（约 2.05 秒），麦克风：%s", analysis.baselineDb, micName)));
                }
                if (analysis.snore) main.post(() -> onSnoreDetected(analysis));
                long now = SystemClock.elapsedRealtime();
                if (now - lastUiUpdate > 250) {
                    lastUiUpdate = now;
                    main.post(() -> {
                        if (!running) return;
                        int state = analysis.calibrated ? STATE_LISTENING : STATE_CALIBRATING;
                        String text = analysis.calibrated
                                ? String.format(Locale.getDefault(), "环境 %.1f dB · 背景 %.1f dB", analysis.db, analysis.baselineDb)
                                : "正在采集背景声音并计算平均 dB，请保持当前播放状态";
                        sendStatus(state, text);
                    });
                }
            }
        } catch (RuntimeException e) {
            if (running) fail("录音中断，请重新开始检测");
        }
    }

    private void onSnoreDetected(SnoreDetector.Analysis analysis) {
        if (!running) return;
        long now = SystemClock.elapsedRealtime();
        lastSnoreAt = now;
        EventLog.add(this, "疑似鼾声", String.format(Locale.getDefault(),
                "环境 %.1f dB，背景 %.1f dB", analysis.db, analysis.baselineDb));
        if (restoring) {
            ramp.cancel();
            restoring = false;
        }
        if (now - lastAdjustmentAt < 10000) {
            sendStatus(STATE_SNORE, "检测到疑似鼾声 · 10 秒防频繁调节中");
            return;
        }
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cap = Math.round(max * Prefs.cap(this) / 100f);
        if (lastSetVolume >= 0 && current != lastSetVolume) {
            originalVolume = current;
            adjustedByUs = false;
            lastSetVolume = -1;
        }
        int target = Math.min(cap, current + Prefs.step(this));
        if (target > current) {
            ramp.start(current, target);
            lastAdjustmentAt = now;
            eventCount++;
            EventLog.add(this, "开始渐变", "目标媒体音量 " + target + "，每 1.5 秒最多提高 1 格");
            main.removeCallbacks(rampStep);
            main.post(rampStep);
            updateNotification("正在小步提高媒体音量 · 连续安静后缓慢恢复");
        } else {
            sendStatus(STATE_SNORE, current >= cap ? "检测到疑似鼾声 · 已达音量上限" : "检测到疑似鼾声");
        }
    }

    private void beginRestore(boolean stopping) {
        if (!adjustedByUs) {
            if (stopping) finishStop(STATE_STOPPED, "检测已停止，媒体音量无需恢复");
            return;
        }
        restoring = true;
        stopAfterRestore = stopping;
        int current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        ramp.start(current, originalVolume);
        EventLog.add(this, "开始恢复", "目标媒体音量 " + originalVolume + "，每 1.5 秒最多降低 1 格");
        sendStatus(STATE_LISTENING, "连续安静，正在逐格缓慢恢复原媒体音量");
        main.removeCallbacks(rampStep);
        main.post(rampStep);
    }

    private void finishRestore() {
        restoring = false;
        adjustedByUs = false;
        lastSetVolume = -1;
        EventLog.add(this, "恢复完成", "媒体音量已逐格恢复至 " + originalVolume);
        if (stopAfterRestore) finishStop(pendingStopState, pendingStopMessage);
        else {
            sendStatus(STATE_LISTENING, "持续安静，已缓慢恢复原媒体音量");
            updateNotification("正在使用手机麦克风检测鼾声");
        }
    }

    private void requestStop(int state, String message) {
        pendingStopState = state;
        pendingStopMessage = message;
        running = false;
        main.removeCallbacks(quietChecker);
        releaseRecorder();
        if (adjustedByUs) beginRestore(true);
        else finishStop(state, message);
    }

    private void fail(String message) {
        EventLog.add(this, "检测错误", message);
        main.post(() -> requestStop(STATE_ERROR, message));
    }

    private void finishStop(int state, String message) {
        running = false;
        busy = false;
        ramp.cancel();
        restoring = false;
        stopAfterRestore = false;
        main.removeCallbacks(quietChecker);
        main.removeCallbacks(rampStep);
        releaseRecorder();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        EventLog.add(this, "停止检测", message);
        stopForeground(STOP_FOREGROUND_REMOVE);
        sendStatus(state, message);
        stopSelf();
    }

    private void releaseRecorder() {
        AudioRecord current = recorder;
        recorder = null;
        if (current != null) {
            try { current.stop(); } catch (IllegalStateException ignored) {}
            current.release();
        }
    }

    private void sendStatus(int state, String message) {
        Intent status = new Intent(ACTION_STATUS).setPackage(getPackageName());
        status.putExtra("state", state);
        status.putExtra("message", message);
        status.putExtra("volume", audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));
        status.putExtra("maxVolume", audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        status.putExtra("mic", micName);
        status.putExtra("events", eventCount);
        sendBroadcast(status);
    }

    private Notification notification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, SnoreService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("鼾声守护运行中")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "停止并缓慢恢复", stopPending).build())
                .build();
    }

    private void updateNotification(String text) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(text));
    }

    @Override public void onDestroy() {
        if (busy || running || ramp.isActive()) finishStop(STATE_STOPPED, "检测已停止");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
