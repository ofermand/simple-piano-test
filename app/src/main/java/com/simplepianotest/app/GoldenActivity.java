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
    private boolean micReady=false,playing=false,learned=false;
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
                if(importedBacking!=null) {
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
    private String songKey(){
        return "golden_level_"+(midiUri==null?"none":midiUri.toString()).hashCode()+"_track_"+trackIndex;
    }
    private String stemKey(){
        return "golden_stem_"+songKey()+"_level_"+levelIndex;
    }
    private void applyLevel(){
        if(progression==null)return;
        levelIndex=progression.clamp(levelIndex);
        ownedPitches=progression.ownedAt(levelIndex);
        lessonPitches[0]=ownedPitches[0];
        lessonPitches[1]=ownedPitches[1];
        if(levelIndex==0){
            practicePattern=new int[]{lessonPitches[0],lessonPitches[1],
                lessonPitches[0],lessonPitches[1]};
        }else{
            int newest=progression.newestPitch(levelIndex);
            int previous=ownedPitches[ownedPitches.length-2];
            practicePattern=new int[]{newest,previous,newest,previous};
        }
        String uri=getPreferences(MODE_PRIVATE).getString(stemKey(),null);
        recordedGuideUri=uri==null?null:Uri.parse(uri);
    }
    private void chooseGolden(){
        if(importedSong==null || backingUri==null){
            notice="בחרו תווים וליווי";
            return;
        }
        melody.clear();
        melody.addAll(importedSong.tracks.get(trackIndex).notes);
        if(melody.isEmpty()){notice="קובץ תווים ריק";return;}
        progression=new GoldenProgression(melody);
        levelIndex=getPreferences(MODE_PRIVATE).getInt(songKey(),0);
        applyLevel();
        startLearning();
    }
    private void resetPractice(){
        noteStep=0;
        lastPracticeHitAt=0;
        learned=false;
        notice="";
        for(int i=0;i<practiceMarks.length;i++)practiceMarks[i]=0;
    }
    private void resetMarks(){
        marks=new int[melody.size()];
        for(int i=0;i<melody.size();i++)
            marks[i]=GoldenLesson.owns(melody.get(i).midi,ownedPitches)?0:3;
    }
    private void startLearning(){
        stopPlayback();
        resetMarks();
        resetPractice();
        stage=levelIndex==0?Stage.NOTE_ONE:Stage.BONUS_NOTE;
        notice="";
    }
    private void unlockNextLevel(){
        if(progression==null || !progression.hasNext(levelIndex))return;
        levelIndex++;
        getPreferences(MODE_PRIVATE).edit().putInt(songKey(),levelIndex).apply();
        applyLevel();
        startLearning();
    }
    private void next(){
        if(stage==Stage.NOTE_ONE){
            stage=Stage.NOTE_TWO;learned=false;notice="";
        }else if(stage==Stage.NOTE_TWO || stage==Stage.BONUS_NOTE){
            stage=Stage.PRACTICE;resetPractice();
        }else if(stage==Stage.PRACTICE){
            stage=Stage.SONG;
            startPlayback();
        }else if(stage==Stage.RESULT){
            if(progression!=null && progression.hasNext(levelIndex))
                unlockNextLevel();
            else restartPlayback();
        }
        view.invalidate();
    }
    private void returnToPractice(){
        stopPlayback();
        stage=Stage.PRACTICE;
        resetPractice();
        view.invalidate();
    }
    private void previous(){
        if(stage==Stage.NOTE_ONE)stage=Stage.HOME;
        else if(stage==Stage.NOTE_TWO)stage=Stage.NOTE_ONE;
        else if(stage==Stage.BONUS_NOTE)stage=Stage.RESULT;
        else if(stage==Stage.PRACTICE)
            stage=levelIndex==0?Stage.NOTE_TWO:Stage.BONUS_NOTE;
        else if(stage==Stage.SONG || stage==Stage.RESULT) {
            returnToPractice();return;
        }
        learned=false;notice="";
    }
    private MediaPlayer prepareAudio(Uri uri)throws Exception{
        MediaPlayer mp=new MediaPlayer();
        try{
            mp.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA).build());
            mp.setDataSource(this,uri);
            mp.prepare();
            return mp;
        }catch(Exception ex){mp.release();throw ex;}
    }
    private float mixVolume(){return volumeLevel==0?0:volumeLevel==1?.35f:.68f;}
    private void startPlayback(){
        stopPlayback();
        resetMarks();
        songPositionMs=0;
        lastHitAt=0;lastHitPitch=-1;lastPlayedIndex=-1;
        playbackError="";
        try{
            if(backingUri==null)throw new IllegalStateException("No instrumental");
            importedBacking=prepareAudio(backingUri);
            importedBacking.setVolume(mixVolume(),mixVolume());
            importedBacking.setOnCompletionListener(player->{
                if(stage==Stage.SONG)endSong();
            });
            if(recordedGuideUri!=null){
                try {
                    recordedGuide=prepareAudio(recordedGuideUri);
                    recordedGuide.setVolume(mixVolume(),mixVolume());
                    recordedGuide.seekTo(0);
                }catch(Exception invalidStem){
                    recordedGuide=null;
                    notice="STEM?";
                }
            }
            if(recordedGuide==null){
                guide=new GoldenGuidePlayer(this,melody,ownedPitches);
                guide.setVolume(mixVolume());
            }
            if(recordedGuide!=null)recordedGuide.start();
            if(guide!=null)guide.play();
            importedBacking.start();
            playing=true;
        }catch(Exception e){
            playbackError=e.getClass().getSimpleName();
            stopPlayback();
            notice="קובץ ליווי?";
            stage=Stage.HOME;
        }
    }
    private void stopPlayback(){
        playing=false;
        if(importedBacking!=null){
            try{importedBacking.release();}catch(Exception ignored){}
            importedBacking=null;
        }
        if(recordedGuide!=null){
            try{recordedGuide.release();}catch(Exception ignored){}
            recordedGuide=null;
        }
        if(guide!=null){guide.close();guide=null;}
    }
    private void pausePlay(){
        if(stage!=Stage.SONG)return;
        if(playing){
            if(importedBacking!=null)importedBacking.pause();
            if(recordedGuide!=null)recordedGuide.pause();
            if(guide!=null)guide.pause();
            playing=false;
        }else{
            if(importedBacking!=null){
                int position=importedBacking.getCurrentPosition();
                if(recordedGuide!=null){
                    recordedGuide.seekTo(position);
                    recordedGuide.start();
                }
                if(guide!=null){guide.alignTo(position);guide.play();}
                importedBacking.start();
                playing=true;
            }
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
        if(importedBacking!=null)importedBacking.setVolume(vol,vol);
        if(recordedGuide!=null)recordedGuide.setVolume(vol,vol);
        if(guide!=null)guide.setVolume(vol);
    }
    private void pickFile(int code){
        if(code==PICK_GUIDE && progression==null){
            if(importedSong==null || midiUri==null){notice="MIDI FIRST";view.invalidate();return;}
            melody.clear();
            melody.addAll(importedSong.tracks.get(trackIndex).notes);
            progression=new GoldenProgression(melody);
            levelIndex=getPreferences(MODE_PRIVATE).getInt(songKey(),0);
            applyLevel();
        }
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
                    progression=null;
                    recordedGuideUri=null;
                    trackIndex=0;
                    midiUri=uri;
                    getPreferences(MODE_PRIVATE).edit()
                        .putString("midiUri",uri.toString()).apply();
                    notice="✓ MIDI";
                }
            }else if(request==PICK_BACKING || request==PICK_GUIDE){
                MediaPlayer test=new MediaPlayer();
                try{
                    test.setDataSource(this,uri);test.prepare();
                    if(test.getDuration()<=0)throw new Exception("Empty audio");
                }finally{test.release();}
                if(request==PICK_BACKING){
                    backingUri=uri;
                    getPreferences(MODE_PRIVATE).edit()
                        .putString("backingUri",uri.toString()).apply();
                    notice="✓ ליווי";
                }else if(progression!=null){
                    recordedGuideUri=uri;
                    getPreferences(MODE_PRIVATE).edit()
                        .putString(stemKey(),uri.toString()).apply();
                    notice="✓ STEM";
                }
            }
        }catch(Exception e){
            notice=request==PICK_MIDI?"MIDI לא תקין":"אודיו לא תקין";
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
        private final int ELECTRIC=0xff3cf7f2, HOT=0xffff36a7, ACID=0xffccff35,
            PURPLE=0xff9b4bff, INK=0xff06081c;
        private void polygon(Canvas c,float[] points,int fill){
            Path path=new Path();path.moveTo(points[0],points[1]);
            for(int i=2;i<points.length;i+=2)path.lineTo(points[i],points[i+1]);
            path.close();
            paint.reset();paint.setAntiAlias(true);paint.setColor(fill);
            c.drawPath(path,paint);
        }
        private void background(Canvas c){
            paint.reset();paint.setAntiAlias(true);
            paint.setShader(new LinearGradient(0,0,width,600,
                new int[]{0xff050313,0xff19054a,0xff050d25},null,Shader.TileMode.CLAMP));
            c.drawRect(0,0,width,600,paint);paint.setShader(null);
            // Extreme live-show laser wedges, stylized as an original stage.
            polygon(c,new float[]{0,0,width*.31f,0,width*.10f,600,0,600},0x143cf7f2);
            polygon(c,new float[]{width,0,width*.73f,0,width*.92f,600,width,600},0x19ff36a7);
            polygon(c,new float[]{width*.53f,0,width*.65f,0,width*.45f,600},0x0eccff35);
            for(int i=0;i<15;i++){
                float y=290+i*26;
                line(c,0,y,width,y,0x244f5193,1);
            }
            float center=width/2;
            for(int i=-9;i<=9;i++)
                line(c,center+i*80,600,center+i*42,290,0x2848d7ee,1);
            for(int i=0;i<27;i++){
                float x=(i*173+47)%Math.max(600,(int)width);
                float y=25+(i*91)%505;
                circle(c,x,y,(i%4==0)?3:1.3f,0x99d6edff);
            }
            float energy=(float)Math.min(1,.1+rms*100);
            for(int i=0;i<26;i++){
                float x=(width/2-285)+i*22;
                float pulse=(float)Math.abs(Math.sin(i*1.63+SystemClock.elapsedRealtime()*.004));
                float height=8+Math.min(45,(9+energy*35)*pulse);
                round(c,x,570-height,x+11,570,3,i%3==0?0x99ff36a7:0x9946eee9);
            }
        }
        private void header(Canvas c){
            // Glitch-style neon lettering and compact level information.
            text(c,"GOLDEN",39,74,55,0x99ff36a7,true,Paint.Align.LEFT);
            text(c,"GOLDEN",44,70,55,ACID,true,Paint.Align.LEFT);
            text(c,"HUNTR/X  •  PIANO QUEST",46,98,14,ELECTRIC,true,Paint.Align.LEFT);
            if(stage!=Stage.HOME){
                text(c,"LV "+String.format(Locale.US,"%02d",levelIndex+1),
                    width-95,61,29,ACID,true,Paint.Align.RIGHT);
            }
            circle(c,width-44,51,8,micReady?0xff63ffb5:0xffff7684);
            if(learned && (stage==Stage.NOTE_ONE || stage==Stage.NOTE_TWO ||
                stage==Stage.BONUS_NOTE))text(c,"✓",width/2,98,34,ACID,true,Paint.Align.CENTER);
        }
        private void home(Canvas c){
            float mid=width/2;
            polygon(c,new float[]{mid-382,137,mid+382,137,mid+352,478,
                mid-354,478},0xd510103c);
            round(c,mid-350,142,mid+350,147,2,HOT);
            round(c,mid-350,469,mid+350,474,2,ELECTRIC);
            text(c,"★",mid-269,227,57,HOT,true,Paint.Align.CENTER);
            text(c,"★",mid+269,227,57,ELECTRIC,true,Paint.Align.CENTER);
            text(c,"GOLDEN",mid,226,72,ACID,true,Paint.Align.CENTER);
            text(c,"PLAY THE SONG",mid,263,19,0xffe3dbff,true,Paint.Align.CENTER);
            first.set(mid-310,303,mid-105,358);
            second.set(mid-88,303,mid+115,358);
            pill(c,first,importedSong==null?"+ NOTES":"✓ NOTES",0xff292454,ELECTRIC);
            pill(c,second,backingUri==null?"+ AUDIO":"✓ AUDIO",0xff292454,ELECTRIC);
            third.set(mid-310,390,mid+225,452);
            pill(c,third,"▶   START",importedSong!=null&&backingUri!=null?HOT:0xff554767,Color.WHITE);
            fourth.set(mid+131,303,mid+310,358);
            pill(c,fourth,"+ STEM",0xff302450,ACID);
            fifth.set(mid+237,390,mid+327,452);
            if(importedSong!=null && importedSong.tracks.size()>1)
                pill(c,fifth,"♫ "+(trackIndex+1),0xff28285e,ELECTRIC);
            if(!notice.isEmpty())
                text(c,notice,mid,513,17,ACID,true,Paint.Align.CENTER);
        }
        private void lesson(Canvas c){
            int pitch=stage==Stage.BONUS_NOTE?progression.newestPitch(levelIndex):
                lessonPitches[stage==Stage.NOTE_ONE?0:1];
            float mid=width/2;
            text(c,stage==Stage.BONUS_NOTE?"NEW NOTE":"POWER NOTE",
                mid,143,23,HOT,true,Paint.Align.CENTER);
            text(c,GoldenLesson.name(pitch),mid,171,27,ACID,true,Paint.Align.CENTER);
            staff(c,pitch,false,-1);
            keyboard(c,pitch,learned);
            text(c,learned?"✓":"⚡",mid,504,32,
                learned?0xff48ffaa:ACID,true,Paint.Align.CENTER);
            first.set(width-286,520,width-39,577);
            pill(c,first,"NEXT ▶",HOT,Color.WHITE);
            second.set(39,520,155,577);
            pill(c,second,"‹",0xff26225a,Color.WHITE);
        }
        private void practice(Canvas c){
            int pitch=practicePattern[Math.min(noteStep,practicePattern.length-1)];
            float mid=width/2;
            text(c,"COMBO",mid,143,25,HOT,true,Paint.Align.CENTER);
            text(c,Math.min(noteStep,4)+" / 4",mid,171,27,ACID,true,Paint.Align.CENTER);
            staff(c,pitch,false,-1);
            keyboard(c,pitch,noteStep>0 && SystemClock.elapsedRealtime()-lastPracticeHitAt<320);
            for(int i=0;i<4;i++){
                float x=mid+(i-1.5f)*54;
                round(c,x-16,485,x+16,499,5,i<noteStep?0xff36f0b3:
                    i==noteStep?HOT:0xff433c78);
            }
            first.set(width-286,520,width-39,577);
            pill(c,first,"PLAY ▶",HOT,Color.WHITE);
            second.set(39,520,155,577);
            pill(c,second,"‹",0xff26225a,Color.WHITE);
        }
        private void song(Canvas c){
            float progress=importedBacking!=null?
                Math.max(0,Math.min(1,songPositionMs/
                    (float)Math.max(1,importedBacking.getDuration()))):0;
            round(c,43,128,width-43,137,4,0xff373562);
            round(c,43,128,43+(width-86)*progress,137,4,ACID);
            boolean recent=wasRecentHit();
            int target=recent && lastPlayedIndex<melody.size()?
                GoldenLesson.normalized(melody.get(lastPlayedIndex).midi):
                nextRequiredNote();
            staff(c,target,true,songPositionMs);
            keyboard(c,target,recent);
            text(c,hits()+" / "+total(),width-47,492,25,ACID,true,Paint.Align.RIGHT);
            if(recent){
                text(c,"PERFECT!",width-150,325,18,0xff51ffb5,true,Paint.Align.CENTER);
            }
            first.set(width-190,519,width-38,579);
            pill(c,first,playing?"Ⅱ":"▶",HOT,Color.WHITE);
            second.set(39,519,124,579);pill(c,second,"‹",0xff242255,Color.WHITE);
            third.set(138,519,223,579);pill(c,third,"↻",0xff242255,Color.WHITE);
            fourth.set(238,519,333,579);
            pill(c,fourth,volumeLevel==0?"◌":volumeLevel==1?"♪":"♫",0xff242255,ELECTRIC);
        }
        private void result(Canvas c){
            float mid=width/2;
            polygon(c,new float[]{mid-420,152,mid+420,152,mid+370,460,
                mid-370,460},0xd510103c);
            text(c,"★  ★  ★",mid,215,74,ACID,true,Paint.Align.CENTER);
            text(c,"LEVEL "+(levelIndex+1),mid,273,30,ELECTRIC,true,Paint.Align.CENTER);
            text(c,hits()+" / "+total(),mid,353,70,Color.WHITE,true,Paint.Align.CENTER);
            text(c,progression!=null && progression.hasNext(levelIndex)?
                "+1 NOTE UNLOCKED":"SONG MASTER",
                mid,406,26,HOT,true,Paint.Align.CENTER);
            first.set(mid+6,444,mid+272,506);
            pill(c,first,progression!=null&&progression.hasNext(levelIndex)?
                "NEXT LEVEL ▶":"REPLAY ↻",HOT,Color.WHITE);
            second.set(mid-278,444,mid-8,506);
            pill(c,second,"REPLAY ↻",0xff262a63,ELECTRIC);
            third.set(mid-95,516,mid+95,573);
            pill(c,third,recordedGuideUri==null?"+ STEM":"✓ STEM",
                0xff25214f,ACID);
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
                else if(fourth.contains(x,y))pickFile(PICK_GUIDE);
                else if(fifth.contains(x,y)&&importedSong!=null){
                    trackIndex=(trackIndex+1)%importedSong.tracks.size();
                    progression=null;
                    recordedGuideUri=null;
                }
            }else if(stage==Stage.NOTE_ONE || stage==Stage.NOTE_TWO ||
                     stage==Stage.BONUS_NOTE || stage==Stage.PRACTICE){
                if(first.contains(x,y))next();
                else if(second.contains(x,y))previous();
            }else if(stage==Stage.SONG){
                if(first.contains(x,y))pausePlay();
                else if(second.contains(x,y))returnToPractice();
                else if(third.contains(x,y))restartPlayback();
                else if(fourth.contains(x,y))changeVolume();
            }else if(stage==Stage.RESULT){
                if(first.contains(x,y))next();
                else if(second.contains(x,y))restartPlayback();
                else if(third.contains(x,y))pickFile(PICK_GUIDE);
            }
            invalidate();return true;
        }
    }
}
