package com.local.snoreguard;
import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.*;
import android.os.*;
public final class SnoreService extends Service {
    public static final String ACTION_START="com.local.snoreguard.START", ACTION_STOP="com.local.snoreguard.STOP", ACTION_STATUS="com.local.snoreguard.STATUS";
    public static final int STATE_STOPPED=0,STATE_CALIBRATING=1,STATE_LISTENING=2,STATE_SNORE=3,STATE_ERROR=4;
    public static volatile boolean running=false;
    private static final String CHANNEL="snore_detection"; private static final int NOTIFICATION_ID=41;
    private final Handler main=new Handler(Looper.getMainLooper());
    private AudioManager audioManager; private PowerManager.WakeLock wakeLock; private volatile AudioRecord recorder;
    private int originalVolume,lastSetVolume=-1,eventCount; private boolean adjustedByUs;
    private long lastSnoreAt,lastAdjustmentAt,lastUiUpdate; private String micName="手机内置麦克风";
    private final Runnable quietChecker=new Runnable(){ public void run(){
        if(!running)return;
        if(adjustedByUs&&SystemClock.elapsedRealtime()-lastSnoreAt>=Prefs.restoreSeconds(SnoreService.this)*1000L){restoreVolume();sendStatus(STATE_LISTENING,"持续安静，已恢复原媒体音量");}
        main.postDelayed(this,1000);
    }};
    @Override public void onCreate(){super.onCreate();audioManager=(AudioManager)getSystemService(AUDIO_SERVICE);
        NotificationChannel c=new NotificationChannel(CHANNEL,"鼾声检测",NotificationManager.IMPORTANCE_LOW);c.setDescription("显示手机麦克风检测与媒体音量调整状态");getSystemService(NotificationManager.class).createNotificationChannel(c);}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?ACTION_START:intent.getAction();
        if(ACTION_STOP.equals(action)){stopRunning(STATE_STOPPED,"检测已停止，媒体音量已按需恢复");return START_NOT_STICKY;}
        if(!running)startDetection();return START_NOT_STICKY;
    }
    private void startDetection(){
        if(checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){stopRunning(STATE_ERROR,"缺少麦克风权限，请从应用内重新启动");return;}
        startForeground(NOTIFICATION_ID,notification("正在准备手机麦克风"));
        originalVolume=audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);lastSetVolume=-1;adjustedByUs=false;eventCount=0;running=true;
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);wakeLock=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SnoreGuard:Detection");wakeLock.acquire();
        sendStatus(STATE_CALIBRATING,"正在学习白噪声与环境底噪，约 2 秒");new Thread(this::captureAudio,"snore-audio").start();main.postDelayed(quietChecker,1000);
    }
    private void captureAudio(){
        final int sampleRate=16000,frameSize=1024;boolean builtInRequested=Prefs.builtInMic(this);AudioDeviceInfo builtIn=null;
        if(builtInRequested){
            for(AudioDeviceInfo d:audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS))if(d.getType()==AudioDeviceInfo.TYPE_BUILTIN_MIC){builtIn=d;break;}
            if(builtIn==null){fail("没有找到手机内置麦克风，已停止检测");return;}
        }
        int minBuffer=AudioRecord.getMinBufferSize(sampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(minBuffer<=0){fail("当前手机不支持所需录音格式");return;}
        AudioRecord record;
        try{record=new AudioRecord(MediaRecorder.AudioSource.MIC,sampleRate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT,Math.max(minBuffer*2,frameSize*4));}
        catch(SecurityException|IllegalArgumentException e){fail("无法打开麦克风，请检查系统权限");return;}
        recorder=record;
        if(builtInRequested&&!record.setPreferredDevice(builtIn)){fail("手机系统不允许指定内置麦克风，已停止检测");return;}
        if(record.getState()!=AudioRecord.STATE_INITIALIZED){fail("麦克风初始化失败，可能正被其他应用占用");return;}
        try{record.startRecording();}catch(IllegalStateException|SecurityException e){fail("麦克风启动失败，请关闭占用录音的应用后重试");return;}
        SnoreDetector detector=new SnoreDetector(Prefs.sensitivity(this));short[] buffer=new short[frameSize];boolean routeChecked=false;
        try{
            while(running){
                int read=record.read(buffer,0,buffer.length);if(read<=0)continue;
                if(!routeChecked){
                    routeChecked=true;AudioDeviceInfo routed=record.getRoutedDevice();
                    if(builtInRequested&&routed!=null&&routed.getType()!=AudioDeviceInfo.TYPE_BUILTIN_MIC){fail("实际录音被路由到耳机或外部麦克风，已停止检测");return;}
                    if(routed!=null)micName=builtInRequested?"手机内置麦克风":String.valueOf(routed.getProductName());else if(!builtInRequested)micName="系统自动选择";
                }
                SnoreDetector.Analysis analysis=detector.process(buffer,read,sampleRate);
                if(analysis.snore)main.post(this::onSnoreDetected);
                long now=SystemClock.elapsedRealtime();
                if(now-lastUiUpdate>250){lastUiUpdate=now;main.post(()->{if(!running)return;int state=analysis.calibrated?STATE_LISTENING:STATE_CALIBRATING;
                    String text=analysis.calibrated?String.format("环境 %.0f dB · 底噪 %.0f dB",analysis.db,analysis.baselineDb):"正在学习白噪声与环境底噪，请保持当前播放状态";sendStatus(state,text);});}
            }
        }catch(RuntimeException e){if(running)fail("录音中断，请重新开始检测");}
    }
    private void onSnoreDetected(){
        if(!running)return;long now=SystemClock.elapsedRealtime();lastSnoreAt=now;
        if(now-lastAdjustmentAt<10000){sendStatus(STATE_SNORE,"检测到疑似鼾声 · 10 秒防频繁调节中");return;}
        int current=audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),max=audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),cap=Math.round(max*Prefs.cap(this)/100f);
        if(lastSetVolume>=0&&current!=lastSetVolume){originalVolume=current;adjustedByUs=false;lastSetVolume=-1;}
        int target=Math.min(cap,current+Prefs.step(this));
        if(target>current){audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,target,0);lastSetVolume=target;adjustedByUs=true;lastAdjustmentAt=now;eventCount++;sendStatus(STATE_SNORE,"检测到疑似鼾声 · 媒体音量提高至 "+target+"/"+max);updateNotification("已提高媒体音量 · 连续安静后自动恢复");}
        else sendStatus(STATE_SNORE,current>=cap?"检测到疑似鼾声 · 已达音量上限":"检测到疑似鼾声");
    }
    private void restoreVolume(){
        if(!adjustedByUs)return;int current=audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        if(lastSetVolume>=0&&current==lastSetVolume)audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,originalVolume,0);
        adjustedByUs=false;lastSetVolume=-1;updateNotification("正在使用手机麦克风检测鼾声");
    }
    private void fail(String message){main.post(()->stopRunning(STATE_ERROR,message));}
    private void stopRunning(int state,String message){
        boolean wasRunning=running;running=false;main.removeCallbacks(quietChecker);AudioRecord current=recorder;recorder=null;
        if(current!=null){try{current.stop();}catch(IllegalStateException ignored){}current.release();}
        if(wasRunning)restoreVolume();if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();wakeLock=null;
        stopForeground(STOP_FOREGROUND_REMOVE);sendStatus(state,message);stopSelf();
    }
    private void sendStatus(int state,String message){
        Intent s=new Intent(ACTION_STATUS).setPackage(getPackageName());s.putExtra("state",state);s.putExtra("message",message);
        s.putExtra("volume",audioManager.getStreamVolume(AudioManager.STREAM_MUSIC));s.putExtra("maxVolume",audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));s.putExtra("mic",micName);s.putExtra("events",eventCount);sendBroadcast(s);
    }
    private Notification notification(String text){
        PendingIntent open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Intent stop=new Intent(this,SnoreService.class).setAction(ACTION_STOP);PendingIntent stopPending=PendingIntent.getService(this,1,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_app).setContentTitle("鼾声守护运行中").setContentText(text).setContentIntent(open).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).addAction(new Notification.Action.Builder(null,"停止并恢复",stopPending).build()).build();
    }
    private void updateNotification(String text){getSystemService(NotificationManager.class).notify(NOTIFICATION_ID,notification(text));}
    @Override public void onDestroy(){if(running)stopRunning(STATE_STOPPED,"检测已停止");super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
