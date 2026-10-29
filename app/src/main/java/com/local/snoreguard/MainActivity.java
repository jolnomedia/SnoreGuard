package com.local.snoreguard;
import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.*;
import android.view.*;
import android.widget.*;
public final class MainActivity extends Activity {
    private TextView statusTitle,statusDetail,volumeText,levelText,micHint,sensitivityLabel,stepLabel,capLabel,restoreLabel;
    private ProgressBar levelBar;private Spinner micSpinner;private SeekBar sensitivitySeek,stepSeek,capSeek,restoreSeek;private Button startButton,stopButton,logButton;
    private boolean pendingStart,receiverRegistered;private static final int REQUEST_PERMISSIONS=12;
    private static final int[] RESTORES={30,60,90,120,180,300};
    private static final String[] SENSITIVITIES={"很低","较低","标准","较高","很高"};
    private final BroadcastReceiver statusReceiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){
        int state=intent.getIntExtra("state",SnoreService.STATE_STOPPED);String message=intent.getStringExtra("message");int volume=intent.getIntExtra("volume",0),max=intent.getIntExtra("maxVolume",1);
        volumeText.setText("媒体音量  "+volume+" / "+max);levelBar.setMax(max);levelBar.setProgress(volume);levelText.setText(message);micHint.setText("当前来源："+intent.getStringExtra("mic")+"。耳机可继续正常播放。");
        switch(state){
            case SnoreService.STATE_CALIBRATING:statusTitle.setText("正在校准环境");statusDetail.setText("保持网易云白噪声正常播放，正在学习底噪。\n不会保存录音，也不会控制音乐播放。");setRunningUi(true);break;
            case SnoreService.STATE_LISTENING:statusTitle.setText("正在安静守护");statusDetail.setText("锁屏后仍会继续。检测到疑似鼾声时，\n仅提高系统媒体音量，安静后自动恢复。");setRunningUi(true);break;
            case SnoreService.STATE_SNORE:statusTitle.setText("检测到疑似鼾声");statusDetail.setText("只调整媒体音量，不申请音频焦点，\n不会暂停或切断网易云白噪声。");setRunningUi(true);break;
            case SnoreService.STATE_ERROR:statusTitle.setText("无法开始检测");statusDetail.setText(message);setRunningUi(false);Toast.makeText(MainActivity.this,message,Toast.LENGTH_LONG).show();break;
            default:statusTitle.setText("准备就绪");statusDetail.setText("先打开网易云播放白噪声，再开始检测。\n只调整媒体音量，不会暂停或控制播放。");setRunningUi(false);
        }
    }};
    @Override protected void onCreate(Bundle savedInstanceState){super.onCreate(savedInstanceState);setContentView(R.layout.activity_main);bindViews();configureMicSpinner();configureSettings();startButton.setBackgroundTintList(null);stopButton.setBackgroundTintList(null);logButton.setBackgroundTintList(null);
        startButton.setOnClickListener(v->requestStart());stopButton.setOnClickListener(v->{startService(new Intent(this,SnoreService.class).setAction(SnoreService.ACTION_STOP));statusDetail.setText("正在停止检测；如音量曾提高，将逐格缓慢恢复。");});
        logButton.setOnClickListener(v->startActivity(new Intent(this,LogActivity.class)));
        AudioManager audio=(AudioManager)getSystemService(AUDIO_SERVICE);volumeText.setText("媒体音量  "+audio.getStreamVolume(AudioManager.STREAM_MUSIC)+" / "+audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));setRunningUi(SnoreService.busy);}
    private void bindViews(){statusTitle=findViewById(R.id.statusTitle);statusDetail=findViewById(R.id.statusDetail);volumeText=findViewById(R.id.volumeText);levelText=findViewById(R.id.levelText);levelBar=findViewById(R.id.levelBar);micSpinner=findViewById(R.id.micSpinner);micHint=findViewById(R.id.micHint);sensitivityLabel=findViewById(R.id.sensitivityLabel);stepLabel=findViewById(R.id.stepLabel);capLabel=findViewById(R.id.capLabel);restoreLabel=findViewById(R.id.restoreLabel);sensitivitySeek=findViewById(R.id.sensitivitySeek);stepSeek=findViewById(R.id.stepSeek);capSeek=findViewById(R.id.capSeek);restoreSeek=findViewById(R.id.restoreSeek);startButton=findViewById(R.id.startButton);stopButton=findViewById(R.id.stopButton);logButton=findViewById(R.id.logButton);}
    private void configureMicSpinner(){
        ArrayAdapter<CharSequence> a=new ArrayAdapter<CharSequence>(this,android.R.layout.simple_spinner_item,getResources().getTextArray(R.array.mic_sources)){
            @Override public View getView(int p,View v,ViewGroup parent){View view=super.getView(p,v,parent);((TextView)view).setTextColor(Color.rgb(242,246,252));return view;}
            @Override public View getDropDownView(int p,View v,ViewGroup parent){View view=super.getDropDownView(p,v,parent);((TextView)view).setTextColor(Color.rgb(242,246,252));view.setBackgroundColor(Color.rgb(27,42,64));return view;}
        };a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);micSpinner.setAdapter(a);micSpinner.setSelection(Prefs.builtInMic(this)?0:1);
    }
    private void configureSettings(){
        sensitivitySeek.setProgress(Prefs.sensitivity(this)-1);stepSeek.setProgress(Prefs.step(this)-1);int ri=2;
        AudioManager audio=(AudioManager)getSystemService(AUDIO_SERVICE);int maxVolume=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        capSeek.setMax(maxVolume);capSeek.setProgress(Prefs.maxVolumeStep(this,maxVolume));
        for(int i=0;i<RESTORES.length;i++)if(RESTORES[i]==Prefs.restoreSeconds(this))ri=i;
        restoreSeek.setProgress(ri);listen(sensitivitySeek,p->sensitivityLabel.setText("检测灵敏度 · "+SENSITIVITIES[p]));listen(stepSeek,p->stepLabel.setText("每次提高 · "+(p+1)+" 格"));listen(capSeek,p->capLabel.setText("自动提高上限 · "+p+" / "+maxVolume+" 格"));listen(restoreSeek,p->restoreLabel.setText("无鼾声后恢复 · "+(RESTORES[p]<120?RESTORES[p]+" 秒":RESTORES[p]/60+" 分钟")));
    }
    private interface ProgressListener{void changed(int p);}private void listen(SeekBar bar,ProgressListener l){l.changed(bar.getProgress());bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean f){l.changed(p);}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});}
    private void requestStart(){
        Prefs.get(this).edit().putBoolean("built_in_mic",micSpinner.getSelectedItemPosition()==0).putInt("sensitivity",sensitivitySeek.getProgress()+1).putInt("step",stepSeek.getProgress()+1).putInt("max_volume_step",capSeek.getProgress()).putInt("restore",RESTORES[restoreSeek.getProgress()]).apply();
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)startDetectionService();else{pendingStart=true;if(Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO,Manifest.permission.POST_NOTIFICATIONS},REQUEST_PERMISSIONS);else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQUEST_PERMISSIONS);}
    }
    private void startDetectionService(){pendingStart=false;startForegroundService(new Intent(this,SnoreService.class).setAction(SnoreService.ACTION_START));setRunningUi(true);statusTitle.setText("正在启动检测");statusDetail.setText("网易云白噪声可以继续播放。\n应用只访问麦克风和系统媒体音量。");Toast.makeText(this,micSpinner.getSelectedItemPosition()==0?"将使用手机内置麦克风，不使用耳机麦克风":"麦克风由系统自动选择",Toast.LENGTH_SHORT).show();}
    private void setRunningUi(boolean active){startButton.setEnabled(!active);stopButton.setEnabled(active);micSpinner.setEnabled(!active);sensitivitySeek.setEnabled(!active);stepSeek.setEnabled(!active);capSeek.setEnabled(!active);restoreSeek.setEnabled(!active);}
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r!=REQUEST_PERMISSIONS||!pendingStart)return;if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)startDetectionService();else{pendingStart=false;Toast.makeText(this,"需要麦克风权限才能检测鼾声",Toast.LENGTH_LONG).show();}}
    @Override protected void onStart(){super.onStart();IntentFilter f=new IntentFilter(SnoreService.ACTION_STATUS);if(Build.VERSION.SDK_INT>=33)registerReceiver(statusReceiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(statusReceiver,f);receiverRegistered=true;setRunningUi(SnoreService.busy);}
    @Override protected void onStop(){if(receiverRegistered)unregisterReceiver(statusReceiver);receiverRegistered=false;super.onStop();}
}
