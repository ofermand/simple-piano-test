package com.simplepianotest.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.ContentResolver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal-text neon K-pop piano prototype.
 *
 * The Golden melody and backing are not bundled. An authorized user imports
 * a MIDI melody track and a synchronized instrumental audio file. The
 * public-domain Twinkle demo remains available to test the complete flow.
 */
public final class GoldenActivity extends Activity {
    private static final int PICK_MIDI=201, PICK_BACKING=202, MIC_PERMISSION=203, PICK_GUIDE=204;
    private enum Stage { HOME, NOTE_ONE, NOTE_TWO, BONUS_NOTE, PRACTICE, SONG, RESULT }
    private Stage stage=Stage.HOME;
    private GoldenView view;
    private final Handler main=new Handler(Looper.getMainLooper());
    private PitchDetector mic;
    private boolean micReady=false,playing=false,usingDemo=false,learned=false;
    private int noteStep=0, trackIndex=0, volumeLevel=1, levelIndex=0;
    private GoldenProgression progression;
    private int[] ownedPitches={60,67};
    private int[] practicePattern={60,67,60,67};
    private final int[] lessonPitches={60,67};
    private final int[] practiceMarks=new int[4];
    private final List<GoldenMidi.Note> melody=new ArrayList<>();
    private GoldenMidi.Song importedSong;
    private int[] marks=new int[0]; // 0 child-pending, 1 hit, 2 miss, 3 backing-only
    private Uri midiUri,backingUri,recordedGuideUri;
    private BackingPlayer demoBacking;
    private MediaPlayer importedBacking;
    private GoldenGuidePlayer guide;
    private MediaPlayer recordedGuide;
    private long lastGuideSyncMs=0;
    private long songPositionMs=0,lastHitAt=0,lastPracticeHitAt=0;
    private int lastHitPitch=-1, lastPlayedIndex=-1;
    private double currentPitch=-1,lastConfidence=0,rms=0;
    private long pitchAt=0;
    private String notice="";
    private String playbackError="";

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        view=new GoldenView();
        setContentView(view);
        restoreFiles();
        main.post(ticker);
        requestMic();
    }
    private final Runnable ticker=new Runnable(){
        @Override public void run(){
            if(stage==Stage.SONG && playing) {
                if(usingDemo && demoBacking!=null){
                    songPositionMs=Math.round(demoBacking.getBeatPosition()*60000.0/72);
                    if(songPositionMs>=40000)endSong();
                } else if(importedBacking!=null) {
                    songPositionMs=importedBacking.getCurrentPosition();
                    if(songPositionMs>=importedBacking.getDuration()-90 && !importedBacking.isPlaying())
                        endSong();
                }
                for(int i=0;i<melody.size();i++){
                    if(marks[i]==0 && songPositionMs>melody.get(i).endMs+600)
                        marks[i]=2;
                }
            }
            if(view!=null)view.invalidate();
            main.postDelayed(this,33);
        }
    };
    private void requestMic(){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
            startMic();
        else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},MIC_PERMISSION);
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results){
        super.onRequestPermissionsResult(code,permissions,results);
        if(code==MIC_PERMISSION && results.length>0 &&
                results[0]==PackageManager.PERMISSION_GRANTED)startMic();
    }
    private void startMic(){
        if(mic==null)mic=new PitchDetector(this,(hz,confidence,power,attack)->
            main.post(()->onPitch(hz,confidence,power,attack)));
        micReady=mic.start();
        if(!micReady)notice="🎙";
    }
    private void onPitch(double hz,double confidence,double power,boolean attack){
        if(isFinishing()||isDestroyed())return;
        rms=power;lastConfidence=confidence;
        if(hz<=0 || confidence<.52){
            if(SystemClock.elapsedRealtime()-pitchAt>600)currentPitch=-1;
            return;
        }
        double detected=69+12*Math.log(hz/440.0)/Math.log(2);
        currentPitch=detected;
        pitchAt=SystemClock.elapsedRealtime();
        if(stage==Stage.NOTE_ONE || stage==Stage.NOTE_TWO || stage==Stage.BONUS_NOTE) {
            int target=stage==Stage.BONUS_NOTE?progression.newestPitch(levelIndex):
                lessonPitches[stage==Stage.NOTE_ONE?0:1];
            if(GoldenLesson.sameNote(detected,target)){learned=true;notice="✓";}
            return;
        }
        if(stage==Stage.PRACTICE && noteStep<practiceMarks.length){
            int target=practicePattern[noteStep];
            long now=SystemClock.elapsedRealtime();
            if(GoldenLesson.sameNote(detected,target) && now-lastPracticeHitAt>380){
                practiceMarks[noteStep]=1;
                lastPracticeHitAt=now;noteStep++;
                notice="✓";
            }
            return;
        }
        if(stage!=Stage.SONG || !playing || songPositionMs<0 || marks.length!=melody.size())
            return;
        // Only consider the child-owned notes. Accompaniment never earns a check.
        int best=-1;
        double bestDistance=Double.MAX_VALUE;
        long heardAt=songPositionMs-115; // overlapping YIN capture introduces latency
        for(int i=0;i<melody.size();i++){
            if(marks[i]==1 || marks[i]==3)continue;
            GoldenMidi.Note note=melody.get(i);
            if(!GoldenLesson.sameNote(detected,note.midi) ||
                    !GoldenLesson.withinTime(heardAt,note))continue;
            double distance=Math.abs(heardAt-note.startMs);
            if(marks[i]==2)distance+=90;
            if(distance<bestDistance){best=i;bestDistance=distance;}
        }
        if(best<0)return;
        long now=SystemClock.elapsedRealtime();
        if(now-lastHitAt<210)return;
        int pitch=melody.get(best).midi;
        if(lastHitPitch==pitch && !attack && now-lastHitAt<470)return;
        marks[best]=1;
        lastHitPitch=pitch;
        lastHitAt=now;lastPlayedIndex=best;
        notice="✓";
        view.invalidate();
    }
    private void chooseDemo(){
        usingDemo=true;
        melody.clear();
        for(int i=0;i<SongData.MELODY.length;i++){
            long start=Math.round(SongData.STARTS[i]*60000.0/72.0);
            long end=Math.round((SongData.STARTS[i]+SongData.LENGTHS[i])*60000.0/72.0);
            melody.add(new GoldenMidi.Note(SongData.MELODY[i],start,end));
        }
        lessonPitches[0]=60;lessonPitches[1]=67;
        startLearning();
    }
    private void chooseGolden(){
        if(importedSong==null || backingUri==null) {
            notice="בחר MIDI וליווי";
            return;
        }
        usingDemo=false;
        melody.clear();
        melody.addAll(importedSong.tracks.get(trackIndex).notes);
        int[] pair=GoldenLesson.choose(melody);
        lessonPitches[0]=pair[0];lessonPitches[1]=pair[1];
        startLearning();
    }
    private void startLearning(){
        stopPlayback();
        marks=new int[melody.size()];
        for(int i=0;i<melody.size();i++)
            marks[i]=GoldenLesson.owns(melody.get(i).midi,lessonPitches)?0:3;
        noteStep=0;learned=false;
        for(int i=0;i<practiceMarks.length;i++)practiceMarks[i]=0;
        stage=Stage.NOTE_ONE;
        notice="";
    }
    private void next(){
        if(stage==Stage.NOTE_ONE){
            stage=Stage.NOTE_TWO;learned=false;notice="";
        }else if(stage==Stage.NOTE_TWO){
            stage=Stage.PRACTICE;noteStep=0;lastPracticeHitAt=0;notice="";
        }else if(stage==Stage.PRACTICE){
            stage=Stage.SONG;
            startPlayback();
        }else if(stage==Stage.RESULT){
            restartPlayback();
        }
        view.invalidate();
    }
    private void returnToPractice(){
        stopPlayback();
        stage=Stage.PRACTICE;
        noteStep=0;lastPracticeHitAt=0;notice="";
        for(int i=0;i<practiceMarks.length;i++)practiceMarks[i]=0;
        view.invalidate();
    }
    private void previous(){
        if(stage==Stage.NOTE_ONE)stage=Stage.HOME;
        else if(stage==Stage.NOTE_TWO)stage=Stage.NOTE_ONE;
        else if(stage==Stage.PRACTICE)stage=Stage.NOTE_TWO;
        else if(stage==Stage.SONG || stage==Stage.RESULT){
            returnToPractice();return;
        }
        learned=false;
        notice="";
    }
    private void startPlayback(){
        stopPlayback();
        for(int i=0;i<marks.length;i++)
            marks[i]=GoldenLesson.owns(melody.get(i).midi,lessonPitches)?0:3;
        songPositionMs=usingDemo?-3000:0;
        lastHitAt=0;lastHitPitch=-1;lastPlayedIndex=-1;
        playbackError="";
        try {
            if(usingDemo) {
                demoBacking=new BackingPlayer(this,72);
                demoBacking.setVolume(volumeLevel==0?0:volumeLevel==1?.33f:.65f);
                demoBacking.play();
            }else{
                importedBacking=new MediaPlayer();
                importedBacking.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA).build());
                importedBacking.setDataSource(this,backingUri);
                importedBacking.prepare();
                guide=new GoldenGuidePlayer(this,melody,lessonPitches);
                guide.setVolume(volumeLevel==0?0:volumeLevel==1?.34f:.7f);
                importedBacking.setVolume(volumeLevel==0?0:volumeLevel==1?.34f:.7f,
                    volumeLevel==0?0:volumeLevel==1?.34f:.7f);
                importedBacking.setOnCompletionListener(player->{
                    if(stage==Stage.SONG)endSong();
                });
                guide.play();
                importedBacking.start();
            }
            playing=true;
        }catch(Exception e){
            playbackError=e.getClass().getSimpleName();
            stopPlayback();
            notice="בדוק קובץ ליווי";
            stage=Stage.HOME;
        }
    }
    private void stopPlayback(){
        playing=false;
        if(demoBacking!=null){demoBacking.close();demoBacking=null;}
        if(importedBacking!=null){
            try{importedBacking.release();}catch(Exception ignored){}
            importedBacking=null;
        }
        if(guide!=null){guide.close();guide=null;}
    }
    private void pausePlay(){
        if(stage!=Stage.SONG)return;
        if(playing){
            if(usingDemo && demoBacking!=null)demoBacking.pause();
            else if(importedBacking!=null)importedBacking.pause();
            if(guide!=null)guide.pause();
            playing=false;
        }else {
            if(usingDemo && demoBacking!=null)demoBacking.play();
            else if(importedBacking!=null) {
                if(guide!=null){guide.alignTo(importedBacking.getCurrentPosition());guide.play();}
                importedBacking.start();
            }
            playing=true;
        }
    }
    private void restartPlayback(){stage=Stage.SONG;startPlayback();}
    private void endSong(){
        if(stage!=Stage.SONG)return;
        for(int i=0;i<marks.length;i++)if(marks[i]==0)marks[i]=2;
        stopPlayback();
        stage=Stage.RESULT;
    }
    private int hits(){
        int value=0;for(int mark:marks)if(mark==1)value++;return value;
    }
    private int total(){
        int value=0;for(int mark:marks)if(mark!=3)value++;return value;
    }
    private void changeVolume(){
        volumeLevel=(volumeLevel+1)%3;
        float vol=volumeLevel==0?0:volumeLevel==1?.34f:.7f;
        if(demoBacking!=null)demoBacking.setVolume(vol);
        if(importedBacking!=null)importedBacking.setVolume(vol,vol);
        if(guide!=null)guide.setVolume(vol);
    }
    private void pickFile(int code){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if(code==PICK_MIDI)
            intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{
                "audio/midi","audio/x-midi","application/x-midi",
                "audio/mid","application/octet-stream","*/*"});
        else
            intent.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{
                "audio/mpeg","audio/mp3","audio/wav","audio/x-wav",
                "audio/mp4","audio/aac","*/*"});
        startActivityForResult(intent,code);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK || data==null || data.getData()==null)return;
        Uri uri=data.getData();
        try{
            getContentResolver().takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if(request==PICK_MIDI) {
                try(InputStream in=getContentResolver().openInputStream(uri)){
                    if(in==null)throw new Exception("Cannot read file");
                    GoldenMidi.Song song=GoldenMidi.parse(in);
                    importedSong=song;
                    trackIndex=0;
                    midiUri=uri;
                    getPreferences(MODE_PRIVATE).edit()
                        .putString("midiUri",uri.toString()).apply();
                    notice="✓ MIDI";
                }
            }else if(request==PICK_BACKING){
                MediaPlayer test=new MediaPlayer();
                try{
                    test.setDataSource(this,uri);test.prepare();
                    if(test.getDuration()<=0)throw new Exception("Empty audio");
                }finally{test.release();}
                backingUri=uri;
                getPreferences(MODE_PRIVATE).edit()
                    .putString("backingUri",uri.toString()).apply();
                notice="✓ ליווי";
            }
        }catch(Exception e){
            notice=request==PICK_MIDI?"MIDI לא תקין":"קובץ ליווי לא תקין";
        }
        view.invalidate();
    }
    private void restoreFiles(){
        String midi=getPreferences(MODE_PRIVATE).getString("midiUri",null);
        String backing=getPreferences(MODE_PRIVATE).getString("backingUri",null);
        if(midi!=null){
            try{
                Uri uri=Uri.parse(midi);
                try(InputStream in=getContentResolver().openInputStream(uri)){
                    if(in!=null){importedSong=GoldenMidi.parse(in);midiUri=uri;}
                }
            }catch(Exception ignored){}
        }
        if(backing!=null)backingUri=Uri.parse(backing);
    }
    @Override protected void onPause(){
        super.onPause();
        if(playing)pausePlay();
        if(mic!=null)mic.stop();
        micReady=false;
    }
    @Override protected void onResume(){
        super.onResume();
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
            startMic();
    }
    @Override protected void onDestroy(){
        main.removeCallbacks(ticker);
        stopPlayback();
        if(mic!=null)mic.stop();
        super.onDestroy();
    }

    private final class GoldenView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF first=new RectF(),second=new RectF(),third=new RectF(),
            fourth=new RectF(),fifth=new RectF();
        private float scale=1,width=1100;
        private final int CYAN=0xff42e9ed,PINK=0xffff5caa,YELLOW=0xffffc85d;
        GoldenView(){super(GoldenActivity.this);}
        @Override protected void onDraw(Canvas screen) {
            super.onDraw(screen);
            scale=getHeight()/600f;
            width=getWidth()/scale;
            screen.save();screen.scale(scale,scale);
            background(screen);
            header(screen);
            if(stage==Stage.HOME)home(screen);
            else if(stage==Stage.NOTE_ONE || stage==Stage.NOTE_TWO)lesson(screen);
            else if(stage==Stage.PRACTICE)practice(screen);
            else if(stage==Stage.SONG)song(screen);
            else result(screen);
            screen.restore();
        }
        private void background(Canvas c){
            paint.reset();paint.setAntiAlias(true);
            paint.setShader(new LinearGradient(0,0,width,600,
                new int[]{0xff080e2b,0xff20204e,0xff10172f},null,
                Shader.TileMode.CLAMP));
            c.drawRect(0,0,width,600,paint);paint.setShader(null);
            circle(c,width-160,125,110,0x1338faff);
            circle(c,140,475,130,0x18ff61c4);
            circle(c,width*.62f,500,80,0x14ffe080);
            for(int i=0;i<29;i++){
                float x=25+(i*179)%((int)Math.max(300,width-55));
                float y=20+(i*71)%548;
                circle(c,x,y,i%4==0?3:1.5f,0x669adaff);
            }
        }
        private void header(Canvas c){
            text(c,"GOLDEN",40,72,49,YELLOW,true,Paint.Align.LEFT);
            text(c,usingDemo&&stage!=Stage.HOME?"DEMO  •  TWINKLE":
                "K-POP PIANO",42,99,14,0xffbac5ec,true,Paint.Align.LEFT);
            text(c,micReady?"●":"○",width-40,58,24,
                micReady?0xff4df1b5:0xffff93a2,true,Paint.Align.RIGHT);
            if(stage!=Stage.HOME){
                text(c,stage==Stage.NOTE_ONE?"01 / 03":
                    stage==Stage.NOTE_TWO?"02 / 03":
                    stage==Stage.PRACTICE?"03 / 03":"★",width-85,58,20,
                    0xffd8d2ff,true,Paint.Align.RIGHT);
            }
        }
        private void home(Canvas c){
            float mid=width/2;
            round(c,mid-360,140,mid+360,468,32,0xff22234e);
            round(c,mid-360,140,mid+360,146,3,0xffff66b2);
            text(c,"★  GOLDEN  ★",mid,217,54,YELLOW,true,Paint.Align.CENTER);
            text(c,"HUNTR/X",mid,252,17,CYAN,true,Paint.Align.CENTER);
            text(c,"♪",mid-205,333,66,PINK,true,Paint.Align.CENTER);
            text(c,"♫",mid+207,333,66,CYAN,true,Paint.Align.CENTER);
            first.set(mid-208,282,mid-20,347);
            second.set(mid+20,282,mid+208,347);
            pill(c,first,importedSong!=null?"✓ MIDI":"+ MIDI",0xff3b336e,Color.WHITE);
            pill(c,second,backingUri!=null?"✓ ליווי":"+ ליווי",0xff3b336e,Color.WHITE);
            third.set(mid-206,367,mid+206,430);
            pill(c,third,"▶  GOLDEN",importedSong!=null&&backingUri!=null?PINK:0xff665d83,Color.WHITE);
            fourth.set(mid-120,490,mid+120,545);
            pill(c,fourth,"▶ DEMO",0xff2a4573,Color.WHITE);
            fifth.set(mid+236,362,mid+337,428);
            if(importedSong!=null && importedSong.tracks.size()>1)
                pill(c,fifth,"♫ "+(trackIndex+1),0xff314766,CYAN);
            if(!notice.isEmpty())text(c,notice,mid,567,15,0xffe9dff6,false,Paint.Align.CENTER);
        }
        private void lesson(Canvas c){
            int pitch=lessonPitches[stage==Stage.NOTE_ONE?0:1];
            float mid=width/2;
            text(c,GoldenLesson.name(pitch),mid,148,38,YELLOW,true,Paint.Align.CENTER);
            staff(c,pitch,false,-1);
            keyboard(c,pitch,false);
            text(c,learned?"✓":"♪",mid,502,35,
                learned?0xff50f2b2:0xfff5eaff,true,Paint.Align.CENTER);
            first.set(width-283,522,width-39,576);
            pill(c,first,"→",PINK,Color.WHITE);
            second.set(39,522,143,576);
            pill(c,second,"‹",0xff343f6c,Color.WHITE);
        }
        private void practice(Canvas c){
            int target=lessonPitches[Math.min(noteStep,3)%2];
            text(c,(Math.min(noteStep,4))+" / 4",width/2,147,30,YELLOW,true,Paint.Align.CENTER);
            staff(c,target,false,-1);
            keyboard(c,target,false);
            for(int i=0;i<4;i++){
                float x=width/2+(i-1.5f)*49;
                circle(c,x,495,9,i<noteStep?0xff4befb5:
                    i==noteStep?PINK:0xff57638d);
            }
            first.set(width-285,522,width-39,576);
            pill(c,first,"▶",PINK,Color.WHITE);
            second.set(39,522,143,576);
            pill(c,second,"‹",0xff343f6c,Color.WHITE);
        }
        private void song(Canvas c){
            float progress=usingDemo?
                Math.max(0,Math.min(1,songPositionMs/40000f)):
                importedBacking!=null?
                    Math.max(0,Math.min(1,songPositionMs/(float)Math.max(1,importedBacking.getDuration()))):
                    0;
            round(c,42,124,width-42,132,4,0xff3b3c69);
            round(c,42,124,42+(width-84)*progress,132,4,PINK);
            boolean recent=wasRecentHit();
            int target=recent && lastPlayedIndex<melody.size()?
                GoldenLesson.normalized(melody.get(lastPlayedIndex).midi):
                nextRequiredNote();
            staff(c,target,true,songPositionMs);
            keyboard(c,target,recent);
            text(c,hits()+" / "+total(),width-47,492,23,YELLOW,true,Paint.Align.RIGHT);
            first.set(width-190,522,width-39,576);
            pill(c,first,playing?"Ⅱ":"▶",PINK,Color.WHITE);
            second.set(36,522,120,576);pill(c,second,"‹",0xff323d6b,Color.WHITE);
            third.set(133,522,217,576);pill(c,third,"↻",0xff323d6b,Color.WHITE);
            fourth.set(230,522,323,576);
            pill(c,fourth,volumeLevel==0?"◌":volumeLevel==1?"♪":"♫",0xff323d6b,CYAN);
        }
        private void result(Canvas c){
            float mid=width/2;
            text(c,"★  ★  ★",mid,225,75,YELLOW,true,Paint.Align.CENTER);
            text(c,hits()+" / "+total(),mid,332,62,Color.WHITE,true,Paint.Align.CENTER);
            text(c,"GOLDEN",mid,381,34,PINK,true,Paint.Align.CENTER);
            first.set(mid-8,442,mid+235,506);
            pill(c,first,"↻",PINK,Color.WHITE);
            second.set(mid-235,442,mid-25,506);
            pill(c,second,"‹",0xff343f6c,Color.WHITE);
        }
        private int nextRequiredNote(){
            if(melody.isEmpty())return -1;
            int future=-1;
            for(int i=0;i<melody.size();i++){
                if(marks[i]==3 || marks[i]==1)continue;
                GoldenMidi.Note note=melody.get(i);
                if(note.endMs+500<songPositionMs)continue;
                if(note.startMs>songPositionMs+2700)break;
                future=note.midi;
                break;
            }
            return future;
        }
        private boolean wasRecentHit(){
            return lastPlayedIndex>=0 &&
                SystemClock.elapsedRealtime()-lastHitAt<400;
        }
        private void staff(Canvas c,int target,boolean scroll,long pos) {
            float left=47,right=width-47,top=178,gap=21,bottom=top+4*gap;
            round(c,left,154,right,294,22,0xfff9f7ff);
            for(int i=0;i<5;i++)
                line(c,left+25,top+i*gap,right-25,top+i*gap,0xffa7a8c5,1.7f);
            text(c,"𝄞",left+56,bottom-5,66,0xff565a92,false,Paint.Align.CENTER);
            if(!scroll){
                drawNote(c,width/2+5,target,bottom,gap,CYAN,false);
            }else {
                float head=Math.min(width*.33f,380);
                line(c,head,166,head,280,PINK,3);
                for(int i=0;i<melody.size();i++) {
                    GoldenMidi.Note note=melody.get(i);
                    float x=head+(note.startMs-pos)*.145f;
                    if(x<left+90 || x>right-31)continue;
                    int mark=marks[i];
                    int col=mark==3?0xffc5c2d4:mark==1?0xff25bb84:
                        mark==2?0xffeb6688:GoldenLesson.owns(note.midi,lessonPitches)?PINK:0xffaaa6b9;
                    drawNote(c,x,note.midi,bottom,gap,col,mark==1);
                }
            }
            text(c,"♪",right-48,188,24,0xff9d91c8,true,Paint.Align.CENTER);
        }
        private void drawNote(Canvas c,float x,int midi,float bottom,float gap,
                              int color,boolean tick) {
            float y=GoldenLesson.staffY(midi,bottom,gap);
            if(GoldenLesson.normalized(midi)==60)
                line(c,x-25,y,x+25,y,0xff76718f,2);
            if(GoldenLesson.sharp(midi))
                text(c,"♯",x-25,y+7,23,0xff5c5879,true,Paint.Align.CENTER);
            paint.reset();paint.setAntiAlias(true);
            paint.setColor(color);
            c.save();c.rotate(-17,x,y);
            c.drawOval(new RectF(x-16,y-10,x+16,y+10),paint);
            c.restore();
            line(c,x+13,y-1,x+13,y-49,color,3);
            if(tick)text(c,"✓",x+30,y-24,20,0xff079875,true,Paint.Align.CENTER);
        }
        private void keyboard(Canvas c,int target,boolean success){
            float boardWidth=Math.min(680,width-120);
            float left=(width-boardWidth)/2,top=326;
            float keyWidth=boardWidth/8;
            round(c,left-14,top-14,left+boardWidth+14,top+129,17,0xff181d40);
            int[] white={0,2,4,5,7,9,11,12};
            int requested=target<0?-1:Math.floorMod(target,12);
            for(int i=0;i<8;i++){
                float x=left+i*keyWidth;
                int semitone=white[i]%12;
                boolean selected=requested>=0 && semitone==requested &&
                    white[i]<12 && !GoldenLesson.sharp(target);
                int fill=selected?(success?0xff4defb7:0xff6ce3eb):0xfff6f5ff;
                round(c,x+1,top,x+keyWidth-2,top+111,6,fill);
                if(selected) {
                    round(c,x+9,top+90,x+keyWidth-12,top+103,5,
                        success?0xff0d8a69:0xff0d849a);
                }
            }
            // Black notes occur between C/D, D/E, F/G, G/A and A/B.
            int[] index={0,1,3,4,5},pitch={1,3,6,8,10};
            for(int i=0;i<index.length;i++){
                float x=left+(index[i]+1)*keyWidth;
                boolean selected=requested==pitch[i];
                round(c,x-keyWidth*.30f,top,x+keyWidth*.30f,top+68,5,
                    selected?(success?0xff4defb7:PINK):0xff26304f);
                if(selected)round(c,x-keyWidth*.18f,top+45,x+keyWidth*.18f,
                    top+60,4,0xffffffff);
            }
            if(target>=0)text(c,GoldenLesson.name(target),width/2,481,22,
                success?0xff5aefb5:YELLOW,true,Paint.Align.CENTER);
        }
        private void pill(Canvas c,RectF area,String label,int background,int foreground) {
            round(c,area.left,area.top,area.right,area.bottom,15,background);
            text(c,label,area.centerX(),area.centerY()+7,
                label.length()>9?17:24,foreground,true,Paint.Align.CENTER);
        }
        private void text(Canvas c,String value,float x,float y,float size,
                          int color,boolean bold,Paint.Align align) {
            paint.reset();paint.setAntiAlias(true);paint.setColor(color);
            paint.setTextAlign(align);paint.setTextSize(size);
            paint.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",
                bold?Typeface.BOLD:Typeface.NORMAL));
            c.drawText(value,x,y,paint);
        }
        private void circle(Canvas c,float x,float y,float r,int color){
            paint.reset();paint.setAntiAlias(true);paint.setColor(color);
            c.drawCircle(x,y,r,paint);
        }
        private void round(Canvas c,float l,float t,float r,float b,float rad,int color){
            paint.reset();paint.setAntiAlias(true);paint.setColor(color);
            c.drawRoundRect(new RectF(l,t,r,b),rad,rad,paint);
        }
        private void line(Canvas c,float x,float y,float xx,float yy,int color,float width){
            paint.reset();paint.setAntiAlias(true);paint.setColor(color);
            paint.setStrokeWidth(width);c.drawLine(x,y,xx,yy,paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            if(event.getAction()!=MotionEvent.ACTION_UP)return true;
            float x=event.getX()/scale,y=event.getY()/scale;
            if(stage==Stage.HOME){
                if(first.contains(x,y))pickFile(PICK_MIDI);
                else if(second.contains(x,y))pickFile(PICK_BACKING);
                else if(third.contains(x,y))chooseGolden();
                else if(fourth.contains(x,y))chooseDemo();
                else if(fifth.contains(x,y)&&importedSong!=null)
                    trackIndex=(trackIndex+1)%importedSong.tracks.size();
            }else if(stage==Stage.NOTE_ONE || stage==Stage.NOTE_TWO ||
                     stage==Stage.PRACTICE){
                if(first.contains(x,y))next();
                else if(second.contains(x,y))previous();
            }else if(stage==Stage.SONG){
                if(first.contains(x,y))pausePlay();
                else if(second.contains(x,y))returnToPractice();
                else if(third.contains(x,y))restartPlayback();
                else if(fourth.contains(x,y))changeVolume();
            }else if(stage==Stage.RESULT){
                if(first.contains(x,y))restartPlayback();
                else if(second.contains(x,y))returnToPractice();
            }
            invalidate();return true;
        }
    }
}
