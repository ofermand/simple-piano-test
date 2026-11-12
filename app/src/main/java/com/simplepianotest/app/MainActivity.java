package com.simplepianotest.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import java.util.Locale;

/** Simple Piano TEST: native Android microphone + score + backing band. */
public final class MainActivity extends Activity {
    private static final int RECORD_PERMISSION = 77;
    private static final int[] MELODY=SongData.MELODY;
    private static final int[] LENGTHS=SongData.LENGTHS;
    private static final int[] STARTS=SongData.STARTS;
    private static final int TOTAL_BEATS=SongData.TOTAL_BEATS;
    private int levelIndex=0; // 0: C/G, then A, F, E, D in song order
    private int[] practiceNotes=SongProgression.practice(0);
    private int[] learnedPitches(){return SongProgression.ownedPitches(levelIndex);}
    private boolean childOwns(int midi){return SongProgression.owns(midi,levelIndex);}
    private enum Phase { WELCOME, TEACH_C, TEACH_G, TEACH_EXTRA, PRACTICE, SONG, RESULTS }
    private Phase phase=Phase.WELCOME;
    private boolean lessonRecognized=false;
    private int practiceStep=0;
    private long lastPracticeHitAt=0;
    private static final String[] NAMES={"דו","רה","מי","פה","סול","לה","סי"};
    private static final int[] COLORS={0xfff46a7b,0xffe99b49,0xffd5b52d,0xff2ac48f,0xff23acae,0xff5089e5,0xff9a71da};

    private GameView gameView;
    private BackingPlayer player;
    private String audioError="";
    private PitchDetector pitchDetector;
    private final Handler mainHandler=new Handler(Looper.getMainLooper());
    private final int[] marks=new int[MELODY.length]; // 0 pending, 1 child hit, 2 child missed, 3 app plays
    private boolean playing=false,everStarted=false,micOn=false,finished=false;
    // 0=silent, 1=quiet backing, 2=full volume.
    private int backingLevel=1;
    private float backingVolume(){return backingLevel==0?0f:backingLevel==1?0.25f:0.65f;}
    private long pitchWindows=0, stableWindows=0, wrongPitchWindows=0, timingWindows=0;
    private String monitorReason="ממתינים לנגינה";
    private boolean flexibleTiming=true; // default for children ages 6–9
    private int bpm=72;
    private double beat=-4,lastPitch=-1,signalRms=0;
    private double lastPitchHz=-1,lastPitchConfidence=0;
    private boolean diagnosticsMode=false;
    private int current=0,lastHitMidi=-1;
    private long lastHitAt=0,lastPitchAt=0;
    private String status="הפסנתר שלך מנגן את המלודיה, אנחנו את הליווי";
    private boolean pausedByLifecycle=false;
    private TextToSpeech voice;
    private boolean voiceAvailable=false,voiceEnabled=true,voiceBusy=false;
    private String activeVoiceId="";
    private int voiceSerial=0;
    private long ignoreMicUntil=0;
    private int spokenSongHitMilestone=0;

    /**
     * Short Hebrew prompts, using the device's own speech engine.
     * The microphone is ignored while the phone itself is speaking, so that
     * spoken prompts cannot receive credit for a piano note.
     */
    private void initVoice(){
        voiceEnabled=getPreferences(MODE_PRIVATE).getBoolean("voice_enabled",true);
        voice=new TextToSpeech(this, status->mainHandler.post(()->{
            if(status!=TextToSpeech.SUCCESS || voice==null)return;
            int lang=voice.setLanguage(Locale.forLanguageTag("he-IL"));
            voiceAvailable=lang!=TextToSpeech.LANG_MISSING_DATA &&
                lang!=TextToSpeech.LANG_NOT_SUPPORTED;
            if(!voiceAvailable)return;
            voice.setSpeechRate(.87f);
            voice.setPitch(1.08f);
            voice.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).build());
            voice.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                @Override public void onStart(String id){
                    mainHandler.post(()->{
                        if(id.equals(activeVoiceId))voiceBusy=true;
                    });
                }
                @Override public void onDone(String id){
                    mainHandler.post(()->finishVoice(id));
                }
                @Override public void onError(String id){
                    mainHandler.post(()->finishVoice(id));
                }
            });
            speak(phase==Phase.WELCOME?
                "ברוכים הבאים! נלמד כמה תווים וננגן את כל כוכב קטן. לחצו על הכפתור כדי להתחיל.":
                "מוכנים לנגן? לחצו להתחלה!");
        }));
    }
    private void finishVoice(String id){
        if(!id.equals(activeVoiceId))return;
        voiceBusy=false;
        ignoreMicUntil=SystemClock.elapsedRealtime()+240;
    }
    private void stopVoice(){
        activeVoiceId="";
        voiceBusy=false;
        ignoreMicUntil=SystemClock.elapsedRealtime()+200;
        if(voice!=null)voice.stop();
    }
    private void speak(String message){
        if(!voiceEnabled || !voiceAvailable || voice==null || isFinishing())return;
        activeVoiceId="piano_he_"+(++voiceSerial);
        voiceBusy=true;
        int result=voice.speak(message,TextToSpeech.QUEUE_FLUSH,null,activeVoiceId);
        if(result==TextToSpeech.ERROR)finishVoice(activeVoiceId);
    }
    private void promptPractice(){
        int target=practiceNotes[Math.min(practiceStep,practiceNotes.length-1)];
        speak("בואו נתרגל. הסתכלו על התו בחמשה ועל הקליד המואר, ונגנו "+
            name(target)+".");
    }
    private void spokenSongFeedback(){
        int count=hits();
        if(count<4 || count/4<=spokenSongHitMilestone)return;
        // Speak only when there is enough time until the next child's note,
        // to avoid interrupting recognition during a quick passage.
        double nextRequired=Double.POSITIVE_INFINITY;
        for(int i=0;i<MELODY.length;i++){
            if(childOwns(MELODY[i]) && marks[i]==0 && STARTS[i]>=beat){
                nextRequired=STARTS[i]-beat;
                break;
            }
        }
        if(nextRequired>2.2){
            spokenSongHitMilestone=count/4;
            speak("מעולה!");
        }
    }

    private final Runnable ticker=new Runnable(){
        @Override public void run(){
            if(phase==Phase.SONG && playing && player!=null){
                beat=player.getBeatPosition();
                if(beat>=0){
                    for(int i=0;i<MELODY.length;i++)
                        if(childOwns(MELODY[i]) && marks[i]==0 &&
                                NoteTimingMatcher.shouldMiss(STARTS[i],LENGTHS[i],beat,flexibleTiming))marks[i]=2;
                    current=0;
                    while(current<MELODY.length-1 && beat>=STARTS[current]+LENGTHS[current])current++;
                }
                if(beat>=TOTAL_BEATS){
                    for(int i=0;i<marks.length;i++)
                        if(childOwns(MELODY[i]) && marks[i]==0)marks[i]=2;
                    playing=false;finished=true;phase=Phase.RESULTS;
                    status="כל הכבוד! ניגנת שיר שלם!";
                    player.pause();
                    if(SongProgression.hasNext(levelIndex))
                        speak("איזה יופי! סיימתם את כל השיר. רוצים ללמוד עוד תו? לחצו על הוספת תו.");
                    else
                        speak("אלופים! למדתם את כל תווי השיר!");
                }
            }
            if(gameView!=null)gameView.invalidate();
            mainHandler.postDelayed(this,33);
        }
    };

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        // Keep the proven Little Star microphone/lesson flow until a REAL
        // Golden content pack is bundled. Never present fake Golden notes.
        if(GoldenPack.isInstalled(this)){
            startActivity(new android.content.Intent(this,GoldenActivity.class));
            finish();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        levelIndex=SongProgression.clamp(
            getPreferences(MODE_PRIVATE).getInt("little_star_level",0));
        practiceNotes=SongProgression.practice(levelIndex);
        gameView=new GameView();setContentView(gameView);
        mainHandler.post(ticker);
        try {
            player=new BackingPlayer(this,bpm,learnedPitches());
            player.setVolume(backingVolume());
            audioError="";
        } catch(Exception e) {
            player=null;
            audioError=e.toString();
            status="שגיאה באתחול הליווי: "+audioError;
        }
        status=levelIndex==0?"מוכנים ללמוד שני תווים ולנגן שיר שלם?":
            "מוכנים ללמוד עוד תו ולנגן שוב את השיר?";
        initVoice();
        requestMicrophone();
    }
    private void immersive(){
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }
    private void requestMicrophone(){
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED){startMicrophone();}
        else requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},RECORD_PERMISSION);
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(code,permissions,grants);
        if(code==RECORD_PERMISSION){
            if(grants.length>0 && grants[0]==PackageManager.PERMISSION_GRANTED)startMicrophone();
            else{micOn=false;status="צריך לאשר הרשאת מיקרופון כדי לזהות נגינה";gameView.invalidate();}
        }
    }
    private void startMicrophone(){
        if(pitchDetector==null)pitchDetector=new PitchDetector(this,(hz,confidence,rms,attack)->
            mainHandler.post(()->onPitch(hz,confidence,rms,attack)));
        micOn=pitchDetector.start();
        if(!micOn)status="המיקרופון אינו זמין. בדקו הרשאות ומכשיר.";
        if(gameView!=null)gameView.invalidate();
    }
    private void onPitch(double hz,double confidence,double rms,boolean attack){
        if(isFinishing()||isDestroyed())return;
        signalRms=rms;
        lastPitchConfidence=confidence;
        if(voiceBusy || SystemClock.elapsedRealtime()<ignoreMicUntil)return;
        if(playing && beat>=0 && beat<TOTAL_BEATS)pitchWindows++;
        if(hz<0 || confidence<.52){
            lastPitchHz=-1;
            if(SystemClock.elapsedRealtime()-lastPitchAt>500) {
                lastPitch=-1;
                if(playing && beat>=0 && beat<TOTAL_BEATS)monitorReason="לא זוהה צליל יציב";
            }
            if(diagnosticsMode && gameView!=null)gameView.invalidate();
            return;
        }
        double midi=69+12*Math.log(hz/440.0)/Math.log(2);
        lastPitchHz=hz;
        lastPitch=midi;
        lastPitchAt=SystemClock.elapsedRealtime();
        if(playing && beat>=0 && beat<TOTAL_BEATS)stableWindows++;
        // Short, rhythm-free lessons run before the song begins.
        if(phase==Phase.TEACH_C || phase==Phase.TEACH_G ||
                phase==Phase.TEACH_EXTRA || phase==Phase.PRACTICE) {
            onLessonPitch(midi,attack);
            return;
        }
        if(phase!=Phase.SONG || !playing || !micOn || beat>=TOTAL_BEATS ||
                beat< -NoteTimingMatcher.earlyGrace(flexibleTiming))return;
        int hit=NoteTimingMatcher.findMatch(MELODY,STARTS,LENGTHS,marks,
                midi,beat,bpm,flexibleTiming);
        if(hit<0){
            if(current<MELODY.length){
                int expected=MELODY[current];
                double difference=Math.abs(midi-expected);
                if(flexibleTiming)difference=Math.min(difference,Math.min(
                    Math.abs(midi-expected-12.0),Math.abs(midi-expected+12.0)));
                if(difference<0.48){
                    timingWindows++;
                    monitorReason="תו נכון, אבל מחוץ לחלון או כבר זוהה";
                }else{
                    wrongPitchWindows++;
                    monitorReason="נשמע "+name((int)Math.round(midi))+
                        " | צריך: "+name(expected);
                }
            }
            return;
        }
        long now=SystemClock.elapsedRealtime();
        if(now-lastHitAt<190)return; // Ignore duplicate callbacks from the same strike.
        int target=MELODY[hit];
        long repeatSpacing=(long)(60000.0/bpm * 0.63);
        if(lastHitMidi==target && !attack && now-lastHitAt<repeatSpacing)return;
        marks[hit]=1;
        monitorReason="✓ נקלט "+name(target)+" (תו "+(hit+1)+")";
        lastHitMidi=target;
        lastHitAt=now;
        boolean otherOctave=Math.abs(midi-target)>=11.5;
        status=otherOctave
                ? "נכון! "+name(target)+" באוקטבה אחרת"
                : "מעולה! ניגנת "+name(target)+"! ✓";
        spokenSongFeedback();
        gameView.invalidate();
    }
    private boolean isMatchingPracticeNote(double detectedMidi,int target) {
        double diff=Math.abs(detectedMidi-target);
        diff=Math.min(diff,Math.abs(detectedMidi-target-12));
        diff=Math.min(diff,Math.abs(detectedMidi-target+12));
        return diff<0.55;
    }
    private void onLessonPitch(double midi,boolean attack) {
        if(phase==Phase.TEACH_C || phase==Phase.TEACH_G || phase==Phase.TEACH_EXTRA) {
            int expected=phase==Phase.TEACH_EXTRA?SongProgression.newestPitch(levelIndex):
                phase==Phase.TEACH_C?60:67;
            if(isMatchingPracticeNote(midi,expected)) {
                if(!lessonRecognized){
                    lessonRecognized=true;
                    status="כל הכבוד! זיהיתי את "+name(expected)+" בפסנתר שלך!";
                    speak("יופי! זה "+name(expected)+". לחצו על המשך.");
                }
                gameView.invalidate();
            }
            return;
        }
        if(phase==Phase.PRACTICE && practiceStep<practiceNotes.length) {
            int expected=practiceNotes[practiceStep];
            if(isMatchingPracticeNote(midi,expected)) {
                long now=SystemClock.elapsedRealtime();
                if(now-lastPracticeHitAt<350)return;
                practiceStep++;
                lastPracticeHitAt=now;
                status=practiceStep==practiceNotes.length?
                    "נהדר! עכשיו מנגנים את השיר המלא!" :
                    "יופי! עכשיו נגן "+name(practiceNotes[practiceStep]);
                if(practiceStep==practiceNotes.length)
                    speak("כל הכבוד! סיימתם לתרגל. עכשיו מנגנים את כל השיר!");
                else speak("מצוין! עכשיו "+name(practiceNotes[practiceStep])+".");
                gameView.invalidate();
            }
        }
    }
    private void returnToPractice(){
        if(playing && player!=null)player.pause();
        playing=false;
        phase=Phase.PRACTICE;
        practiceStep=0;
        lastPracticeHitAt=0;
        status="בוא נתרגל שוב את התווים שלמדת";
        promptPractice();
        gameView.invalidate();
    }
    private void advanceLesson(){
        if(phase==Phase.WELCOME){
            phase=levelIndex==0?Phase.TEACH_C:Phase.TEACH_EXTRA;
            lessonRecognized=false;
            int target=levelIndex==0?60:SongProgression.newestPitch(levelIndex);
            status="נגן "+name(target)+" על הפסנתר כדי להתחיל";
            speak("הסתכלו על החמשה ועל הפסנתר הקטן. מצאו את "+
                name(target)+" ונגנו אותו בפסנתר האמיתי.");
        }else if(phase==Phase.TEACH_C){
            phase=Phase.TEACH_G;
            lessonRecognized=false;
            status="מצא את סול ונגן אותו";
            speak("עכשיו נלמד את סול. הוא על הקו השני מלמטה. מצאו את הקליד המואר ונגנו סול.");
        }else if(phase==Phase.TEACH_G || phase==Phase.TEACH_EXTRA){
            phase=Phase.PRACTICE;
            practiceStep=0;
            lastPracticeHitAt=0;
            status="בוא נתרגל את התווים לפני השיר";
            promptPractice();
        }else if(phase==Phase.PRACTICE){
            phase=Phase.SONG;
            reset();
            playPause();
        }
        gameView.invalidate();
    }
    private void learnAnotherNote(){
        if(phase!=Phase.RESULTS || !SongProgression.hasNext(levelIndex))return;
        if(player!=null){player.close();player=null;}
        playing=false;finished=false;everStarted=false;
        levelIndex++;
        getPreferences(MODE_PRIVATE).edit().putInt("little_star_level",levelIndex).apply();
        practiceNotes=SongProgression.practice(levelIndex);
        practiceStep=0;
        lastPracticeHitAt=0;
        lessonRecognized=false;
        phase=Phase.TEACH_EXTRA;
        int newPitch=SongProgression.newestPitch(levelIndex);
        status="תו חדש! נמצא את "+name(newPitch)+" וננגן אותו";
        speak("כל הכבוד! הרווחתם תו חדש: "+name(newPitch)+
            ". הסתכלו על החמשה, מצאו את הקליד המואר ונגנו אותו.");
        try {
            player=new BackingPlayer(this,bpm,learnedPitches());
            player.setVolume(backingVolume());
            audioError="";
        } catch(Exception e) {
            audioError=e.toString();
            status="שגיאה בהכנת הליווי: "+audioError;
        }
        gameView.invalidate();
    }
    private static String name(int midi){return NAMES[diatonicIndex(midi)];}
    private static int diatonicIndex(int midi){
        switch(Math.floorMod(midi,12)){case 0:return 0;case 2:return 1;case 4:return 2;case 5:return 3;case 7:return 4;case 9:return 5;case 11:return 6;default:return 0;}
    }
    private static int color(int midi){return COLORS[diatonicIndex(midi)];}
    private void playPause(){
        if(phase==Phase.RESULTS){
            phase=Phase.SONG;
            reset();
        }
        if(phase!=Phase.SONG)return;
        diagnosticsMode=false;
        if(player==null) {
            showAudioFailure("לא ניתן להפעיל את נגן הליווי",audioError);
            return;
        }
        if(finished){reset();}
        if(playing){player.pause();playing=false;status="הלהקה מושהית. נמשיך כשתרצה.";}
        else{
            if(!everStarted){everStarted=true;beat=-4;}
            try {
                player.play();playing=true;
                if(beat<0)speak("הקשיבו לספירה. מוכנים? מנגנים!");
                status="הקשב לספירה ואז נגן את התו המואר!";
                audioError="";
            } catch(Exception e) {
                playing=false;everStarted=false;
                audioError=e.toString();
                showAudioFailure("הפעלת הליווי נכשלה",audioError);
            }
        }
        gameView.invalidate();
    }
    private void reset(){
        if(player!=null) {
            try{player.restart();}
            catch(Exception e){
                audioError=e.toString();
                player.close();player=null;
                showAudioFailure("איפוס הליווי נכשל",audioError);
            }
        }
        playing=false;everStarted=false;finished=false;beat=-4;current=0;lastHitMidi=-1;lastHitAt=0;
        spokenSongHitMilestone=0;
        for(int i=0;i<marks.length;i++)
            marks[i]=childOwns(MELODY[i])?0:3;
        pitchWindows=stableWindows=wrongPitchWindows=timingWindows=0;
        monitorReason="ממתינים לנגינה";
        status="מוכנים? ארבע פעימות ונתחיל!";
        gameView.invalidate();
    }
    private void setBpm(int newBpm){
        if(newBpm==bpm)return;
        reset();bpm=newBpm;
        if(player!=null)player.close();
        try {
            player=new BackingPlayer(this,bpm,learnedPitches());
            player.setVolume(backingVolume());
            audioError="";
        } catch(Exception e) {
            player=null;
            audioError=e.toString();
            status="שגיאה בהכנת הליווי: "+audioError;
        }
    }
    private void showAudioFailure(String title,String details) {
        String message=(details==null || details.isEmpty())?"Audio playback unavailable":details;
        status=title+": "+message;
        if(gameView!=null)gameView.invalidate();
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("סגירה",(d,w)->d.dismiss()).show();
    }
    private int hits(){int n=0;for(int v:marks)if(v==1)n++;return n;}
    @Override public void onPause(){super.onPause();pausedByLifecycle=playing;if(playing)playPause();stopVoice();if(pitchDetector!=null)pitchDetector.stop();micOn=false;}
    @Override public void onResume(){super.onResume();immersive();if(gameView!=null && checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED && !micOn)startMicrophone();}
    @Override public void onDestroy(){
        mainHandler.removeCallbacks(ticker);
        if(pitchDetector!=null)pitchDetector.stop();
        if(player!=null)player.close();
        if(voice!=null){voice.stop();voice.shutdown();voice=null;}
        super.onDestroy();
    }

    private final class GameView extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF playRect=new RectF(),resetRect=new RectF(),slowRect=new RectF(),normalRect=new RectF(),soundRect=new RectF(),permissionRect=new RectF(),diagnosticRect=new RectF(),flexibleRect=new RectF();
        private final RectF learnPrimaryRect=new RectF(),learnBackRect=new RectF(),practiceReturnRect=new RectF(),nextNoteRect=new RectF();
        private float scale=1,logicalWidth=1080;
        GameView(){super(MainActivity.this);setLayerType(View.LAYER_TYPE_SOFTWARE,null);}
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            scale=getHeight()/600f;logicalWidth=getWidth()/scale;
            canvas.save();canvas.scale(scale,scale);
            canvas.drawColor(0xfff0effa);
            round(canvas,15,13,logicalWidth-15,489,23,0xff113747);
            Paint gradient=new Paint(Paint.ANTI_ALIAS_FLAG);
            gradient.setShader(new LinearGradient(0,10,logicalWidth,493,0xff102d4b,0xff236463,Shader.TileMode.CLAMP));
            canvas.drawRoundRect(new RectF(15,13,logicalWidth-15,489),23,23,gradient);
            drawHeader(canvas);
            if(phase==Phase.WELCOME || phase==Phase.TEACH_C ||
                    phase==Phase.TEACH_G || phase==Phase.TEACH_EXTRA ||
                    phase==Phase.PRACTICE){
                drawLearning(canvas);
                drawLearningControls(canvas);
            }else {
                if(phase==Phase.RESULTS)drawResult(canvas);else drawStaff(canvas);
                drawControls(canvas);
            }
            canvas.restore();
        }
        private void drawHeader(Canvas c){
            text(c,"⭐  כוכב קטן",logicalWidth-43,69,32,Color.WHITE,true,Paint.Align.RIGHT);
            text(c,"Simple Piano DEMO v1.1",44,57,19,0xffb5efde,true,Paint.Align.LEFT);
            text(c,micOn?"●  🎙  מיקרופון פעיל":"●  🎙  מיקרופון כבוי",44,81,13,micOn?0xff70ecc4:0xffffb1b9,false,Paint.Align.LEFT);
            text(c,"רמה "+(levelIndex+1)+"/"+SongProgression.levels()+"  •  "+SongProgression.ownedCount(levelIndex)+" תווים שלך",logicalWidth-45,94,14,0xffb2d9db,false,Paint.Align.RIGHT);
            float x=48,w=logicalWidth-96,y=120;
            round(c,x,y,x+w,y+10,5,0x55ffffff);
            if(phase==Phase.SONG || phase==Phase.RESULTS) {
                if(beat>0)round(c,x,y,x+w*(float)Math.min(1,beat/TOTAL_BEATS),y+10,5,0xff69e4c6);
                text(c,beat<0 && playing?"מתחילים בעוד "+(int)Math.ceil(-beat)+"...":
                    (phase==Phase.RESULTS?"הושלם":""+Math.round(Math.max(0,beat)/TOTAL_BEATS*100)+"%"),
                    logicalWidth-48,145,12,0xffbee8e3,false,Paint.Align.RIGHT);
            }else{
                float progress=phase==Phase.WELCOME?0:phase==Phase.TEACH_C?.20f:
                    phase==Phase.TEACH_G || phase==Phase.TEACH_EXTRA?.48f:.77f;
                if(progress>0)round(c,x,y,x+w*progress,y+10,5,0xff69e4c6);
                text(c,phase==Phase.WELCOME?"בוחרים לנגן":
                    phase==Phase.TEACH_C?"דו":
                    phase==Phase.TEACH_G?"סול":
                    phase==Phase.TEACH_EXTRA?"תו חדש: "+name(SongProgression.newestPitch(levelIndex)):
                    "מתרגלים",
                    logicalWidth-48,145,12,0xffbee8e3,false,Paint.Align.RIGHT);
            }
        }
        private void drawLearning(Canvas c) {
            float x=38,r=logicalWidth-38,mid=(x+r)/2;
            round(c,x,160,r,398,20,0xfffcfbff);
            if(phase==Phase.WELCOME) {
                text(c,"⭐  🎵  ⭐",mid,226,46,0xffffbb49,true,Paint.Align.CENTER);
                text(c,"בוא ננגן את כוכב קטן!",mid,272,35,0xff463982,true,Paint.Align.CENTER);
                text(c,"כל פעם לומדים עוד תו ומנגנים את כל השיר",mid,318,21,
                    0xff746a9e,false,Paint.Align.CENTER);
                round(c,mid-196,340,mid-18,378,17,0xffe9f8ee);
                round(c,mid+18,340,mid+196,378,17,0xffe9f3fd);
                text(c,levelIndex==0?"🎹 דו + סול":"🎹 "+SongProgression.ownedCount(levelIndex)+" תווים",mid-107,366,17,0xff278963,true,Paint.Align.CENTER);
                text(c,"🎶 שיר שלם",mid+107,366,17,0xff4678bd,true,Paint.Align.CENTER);
            }else if(phase==Phase.TEACH_C || phase==Phase.TEACH_G ||
                    phase==Phase.TEACH_EXTRA) {
                int target=phase==Phase.TEACH_EXTRA?SongProgression.newestPitch(levelIndex):
                    phase==Phase.TEACH_C?60:67;
                text(c,"לומדים את התו "+name(target)+"!",mid,206,28,0xff554285,true,Paint.Align.CENTER);
                text(c,"אותו התו מופיע גם על החמשה וגם על הפסנתר",mid,238,17,
                    0xff6e6595,false,Paint.Align.CENTER);
                drawStaffKeyboardPair(c,mid,target);
            }else if(phase==Phase.PRACTICE) {
                int target=practiceNotes[Math.min(practiceStep,practiceNotes.length-1)];
                text(c,"קרא את התו ונגן אותו!",mid,205,27,0xff554285,true,Paint.Align.CENTER);
                text(c,practiceStep>=practiceNotes.length?
                    "מצוין! עכשיו מנגנים את השיר המלא.":
                    "תרגיל "+(practiceStep+1)+" מתוך "+practiceNotes.length+
                    "  •  התו הבא: "+name(target),
                    mid,237,17,0xff736899,false,Paint.Align.CENTER);
                for(int i=0;i<practiceNotes.length;i++){
                    float bx=mid+(i-1.5f)*50;
                    circle(c,bx,255,9,i<practiceStep?0xff27b48d:
                        i==practiceStep?0xff7f5bdf:0xffded9eb);
                    if(i<practiceStep)text(c,"✓",bx,260,12,Color.WHITE,true,Paint.Align.CENTER);
                }
                drawStaffKeyboardPair(c,mid,target);
            }
            text(c,status,logicalWidth-43,435,18,0xffffffff,true,Paint.Align.RIGHT);
            String detected=lastPitch<0 || SystemClock.elapsedRealtime()-lastPitchAt>650?
                "—":name((int)Math.round(lastPitch));
            text(c,"🎙 המיקרופון שומע: "+detected+"     "+(micOn?"מחובר":"לא פעיל"),
                logicalWidth-43,470,15,micOn?0xffb6efde:0xffffaeb6,true,Paint.Align.RIGHT);
        }
        private void drawStaffKeyboardPair(Canvas c,float mid,int target) {
            float left=38,right=logicalWidth-38;
            float staffCenter=(left+mid)/2;
            float keyboardCenter=(mid+right)/2;
            text(c,"על החמשה (מפתח סול)",staffCenter,278,15,
                0xff554285,true,Paint.Align.CENTER);
            text(c,"על הפסנתר",keyboardCenter,278,15,
                0xff554285,true,Paint.Align.CENTER);
            drawSingleTeachingNote(c,staffCenter,target);
            drawKeyboard(c,keyboardCenter,target);
            text(c,"↔",mid,349,36,0xff8b6cdd,true,Paint.Align.CENTER);
            text(c,"אותו צליל",mid,371,13,0xff77679a,true,Paint.Align.CENTER);
            String location=target==60?"קו עזר":target==62?"מתחת לחמשה":
                target==64?"קו תחתון":target==65?"רווח ראשון":
                target==67?"קו שני":target==69?"רווח שני":"";
            text(c,name(target)+" • "+location,staffCenter,392,14,
                0xff6e6392,true,Paint.Align.CENTER);
        }
        private void drawSingleTeachingNote(Canvas c,float cx,int midi) {
            float top=294,gap=15,bottom=top+4*gap;
            float left=cx-132,right=cx+132;
            for(int i=0;i<5;i++)
                line(c,left,top+i*gap,right,top+i*gap,0xffaaa6bc,1.8f);
            // Treble clef, five staff lines: E4 G4 B4 D5 F5 (bottom to top).
            text(c,"𝄞",left+24,bottom-8,52,0xff685997,false,Paint.Align.CENTER);
            float noteX=cx+28;
            float noteY=StaffNoteLayout.yForMidi(midi,bottom,gap);
            if(StaffNoteLayout.needsLedgerLine(midi))
                line(c,noteX-27,noteY,noteX+27,noteY,0xff686082,2.2f);
            int clr=color(midi);
            p.reset();p.setAntiAlias(true);p.setColor(clr);p.setStyle(Paint.Style.FILL);
            c.save();c.rotate(-18,noteX,noteY);
            c.drawOval(new RectF(noteX-20,noteY-12,noteX+20,noteY+12),p);
            c.restore();
            line(c,noteX+16,noteY-3,noteX+16,noteY-59,clr,3.5f);
            text(c,name(midi),noteX+69,noteY+7,18,clr,true,Paint.Align.LEFT);
        }
        private void drawKeyboard(Canvas c,float mid,int target) {
            float keyW=Math.min(58f,(logicalWidth/2f-92)/7f);
            float top=291,height=91,first=mid-keyW*3.5f;
            String[] labels={"דו","רה","מי","פה","סול","לה","סי"};
            int[] whiteMidi={60,62,64,65,67,69,71};
            int selected=-1;
            for(int i=0;i<whiteMidi.length;i++)
                if(whiteMidi[i]==target){selected=i;break;}
            for(int i=0;i<7;i++){
                float l=first+i*keyW,r=l+keyW-2;
                int keyColor=i==selected?0xffe7d3f8:0xfff8f8fd;
                round(c,l,top,r,top+height,5,keyColor);
                p.reset();p.setAntiAlias(true);
                p.setColor(i==selected?color(target):0xffc3bfd1);
                p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(i==selected?3.5f:1.4f);
                c.drawRoundRect(new RectF(l,top,r,top+height),5,5,p);
                text(c,labels[i],l+keyW/2,top+78,12,
                    i==selected?0xff604093:0xff736b8e,true,Paint.Align.CENTER);
            }
            for(int i:new int[]{0,1,3,4,5}) {
                float bx=first+(i+1)*keyW-2;
                round(c,bx-13,top,bx+13,top+49,4,0xff283345);
            }
        }
        private void drawLearningControls(Canvas c) {
            round(c,15,502,logicalWidth-15,585,17,0xffffffff);
            float right=logicalWidth-40;
            learnPrimaryRect.set(right-283,515,right,568);
            String title=phase==Phase.WELCOME?
                levelIndex==0?"🎹 לומדים דו":"🎹 התו הבא":
                phase==Phase.TEACH_C?"ממשיכים לסול":
                phase==Phase.TEACH_G || phase==Phase.TEACH_EXTRA?
                    "מתרגלים יחד":"▶ מנגנים שיר שלם";
            button(c,learnPrimaryRect,title,0xff7555e8,Color.WHITE);
            learnBackRect.set(36,520,198,565);
            if(phase!=Phase.WELCOME)
                button(c,learnBackRect,"↩ חזרה",0xffeeeaf8,0xff6954ad);
            else
                text(c,"זמן לימוד קצר, והמון מוזיקה",49,550,17,0xff77699f,true,Paint.Align.LEFT);
        }
        private void drawStaff(Canvas c){
            float x=38,r=logicalWidth-38;
            round(c,x,160,r,391,18,0xffffffff);
            text(c,"🎼  התווים שלי",r-22,186,16,0xff514475,true,Paint.Align.RIGHT);
            float center=(x+r)/2;
            flexibleRect.set(center-100,164,center+100,200);
            button(c,flexibleRect,flexibleTiming?"✓ מצב גמיש":"🎯 מצב מדויק",
                    flexibleTiming?0xffe0f8ed:0xffeeeafa,
                    flexibleTiming?0xff188c69:0xff6549b4);
            practiceReturnRect.set(center+113,164,center+270,200);
            button(c,practiceReturnRect,"↩ לתרגול",0xffe8f4ff,0xff345ba5);
            String n=beat<0?"מוכנים?":
                childOwns(MELODY[current])?"התור שלך: "+name(MELODY[current]):
                "הלהקה מנגנת";
            text(c,n,x+23,186,16,0xff704dd0,true,Paint.Align.LEFT);
            float top=218,sep=21,bottom=top+4*sep;
            for(int i=0;i<5;i++)line(c,x+26,top+i*sep,r-25,top+i*sep,0xffb2b0c0,1.6f);
            float lineX=Math.min(logicalWidth*.30f,330),pxPerBeat=100;
            // Subtle stationary play-head shows the exact beat to play.
            line(c,lineX,206,lineX,346,0xff9b76ea,2.7f);
            circle(c,lineX,209,7,0xff7653df);
            // Draw notes with precise duration-based horizontal spacing.
            for(int i=0;i<MELODY.length;i++){
                float nx=lineX+(float)((STARTS[i]-Math.max(0,beat))*pxPerBeat);
                if(beat<0)nx=lineX+STARTS[i]*pxPerBeat;
                if(nx<x+63 || nx>r-39)continue;
                int midi=MELODY[i],m=marks[i];
                float ny=StaffNoteLayout.yForMidi(midi,bottom,sep);
                if(i==current && beat>=0 && m!=3){
                    round(c,nx-34,193,nx+34,356,14,0xfff0e9ff);
                }
                if(midi==60)line(c,nx-23,ny,nx+24,ny,0xff938a9e,2);
                int clr=m==3?0xffa5a7b7:m==1?0xff24b28b:m==2?0xffe96c7d:color(midi);
                p.reset();p.setAntiAlias(true);p.setColor(clr);p.setStyle(LENGTHS[i]==2&&m==0?Paint.Style.STROKE:Paint.Style.FILL);p.setStrokeWidth(3.2f);
                c.save();c.rotate(-18,nx,ny);c.drawOval(new RectF(nx-17,ny-11,nx+17,ny+11),p);c.restore();
                line(c,nx+14,ny-2,nx+14,ny-52,clr,3);
                if(m==1){circle(c,nx+27,ny-32,13,0xff24b28b);text(c,"✓",nx+27,ny-27,17,Color.WHITE,true,Paint.Align.CENTER);}
                if(m==2){circle(c,nx+27,ny-32,13,0xffe96c7d);text(c,"×",nx+27,ny-26,19,Color.WHITE,true,Paint.Align.CENTER);}
                text(c,name(midi),nx,368,15,m==3?0xffaaa6b7:m==1?0xff168e6f:
                    m==2?0xffd55a6c:0xff6a588d,true,Paint.Align.CENTER);
            }
            text(c,"צבעוני = אתה מנגן     אפור = הלהקה משלימה     ירוק = הצלחה",
                (x+r)/2,384,12,0xff8b8098,false,Paint.Align.CENTER);
            if(diagnosticsMode)drawMicDiagnostic(c,x,r);
            String detectedLabel=lastPitch<0 ||
                    SystemClock.elapsedRealtime()-lastPitchAt>650
                ? "אין תו מזוהה"
                : name((int)Math.round(lastPitch))+
                    " MIDI "+Math.round(lastPitch)+
                    " "+String.format(Locale.US,"%.0f",lastPitchHz)+"Hz";
            text(c,"🎙 זוהה: "+detectedLabel+"     "+Math.round(lastPitchConfidence*100)+"%",
                    logicalWidth-43,412,15,0xffadf4e3,true,Paint.Align.RIGHT);
            text(c,"אבחון: "+monitorReason,logicalWidth-43,439,17,0xffffe0a0,true,Paint.Align.RIGHT);
            text(c,"✓ "+hits()+" הצלחות  ✕ "+misses()+" פספוסים"+
                "     קלט יציב "+stableWindows+"/"+pitchWindows+
                "  צליל שונה "+wrongPitchWindows+"  תזמון "+timingWindows,
                logicalWidth-43,468,13,0xffc4e7df,true,Paint.Align.RIGHT);
            if(beat<0 && playing){
                round(c,logicalWidth*.41f,240,logicalWidth*.59f,345,18,0xee7455e8);
                text(c,""+(int)Math.max(1,Math.ceil(-beat)),logicalWidth*.5f,320,77,Color.WHITE,true,Paint.Align.CENTER);
            }
        }
        private void drawMicDiagnostic(Canvas c,float x,float r) {
            round(c,x+10,199,r-10,375,16,0xfff6f1ff);
            text(c,"🎙 בדיקת תווים מהפסנתר",r-28,231,23,0xff5a409e,true,Paint.Align.RIGHT);
            boolean fresh=lastPitch>=0 && lastPitchHz>0 &&
                SystemClock.elapsedRealtime()-lastPitchAt<850;
            String note=fresh?name((int)Math.round(lastPitch))+" (MIDI "+Math.round(lastPitch)+")":"לא זוהה תו יציב";
            String frequency=fresh?String.format(Locale.US,"%.1f",lastPitchHz)+" Hz":"—";
            text(c,"נקלט: "+note+"     "+frequency,r-28,267,20,
                fresh?0xff167f68:0xffae5671,true,Paint.Align.RIGHT);
            text(c,"עוצמה RMS: "+String.format(Locale.US,"%.4f",signalRms)+
                "     ביטחון: "+Math.round(lastPitchConfidence*100)+"%",r-28,303,16,
                0xff6a5c88,false,Paint.Align.RIGHT);
            text(c,"נגנו דו אמצעי (C4 ≈ 262Hz), ואז סול (G4 ≈ 392Hz)",r-28,334,16,
                0xff5e5289,false,Paint.Align.RIGHT);
            String input=pitchDetector==null?"not started":pitchDetector.getSourceName();
            text(c,"מקור מיקרופון: "+input,r-28,360,13,0xff80749a,false,Paint.Align.RIGHT);
        }
        private void drawResult(Canvas c){
            float mid=logicalWidth/2;
            text(c,"🏆  ⭐  🎉",mid,210,55,0xffffdb77,true,Paint.Align.CENTER);
            text(c,"כל הכבוד! סיימת את השיר!",mid,270,33,Color.WHITE,true,Paint.Align.CENTER);
            text(c,"ניגנת נכון "+hits()+" מתוך "+SongProgression.requiredNotes(levelIndex)+
                " התווים שלך",mid,316,25,0xffc0f3de,true,Paint.Align.CENTER);
            text(c,"רמה "+(levelIndex+1)+" • "+SongProgression.ownedCount(levelIndex)+
                " תווים מתוך "+SongProgression.ownedCount(SongProgression.levels()-1),
                mid,365,20,0xffd4d0fa,true,Paint.Align.CENTER);
            if(SongProgression.hasNext(levelIndex)){
                int next=SongProgression.nextPitch(levelIndex);
                text(c,"הגיע הזמן להוסיף עוד תו!",mid,403,18,0xffcce1ee,true,Paint.Align.CENTER);
                nextNoteRect.set(mid-209,421,mid+209,483);
                button(c,nextNoteRect,"＋  לומדים "+name(next),
                    0xff45dbbd,0xff10394a);
            }else {
                nextNoteRect.setEmpty();
                text(c,"⭐ למדת את כל תווי השיר! ⭐",
                    mid,454,25,0xffffe69a,true,Paint.Align.CENTER);
            }
            practiceReturnRect.set(41,435,229,481);
            button(c,practiceReturnRect,"↩ חזרה לתרגול",0xffe8f4ff,0xff345ba5);
        }
        private int misses(){int n=0;for(int v:marks)if(v==2)n++;return n;}
        private void drawControls(Canvas c){
            round(c,15,502,logicalWidth-15,585,17,0xffffffff);
            float right=logicalWidth-40;
            playRect.set(right-203,521,right,565);
            button(c,playRect,finished?"▶  לנגן שוב":playing?"⏸  השהיה":everStarted?"▶  המשך":"▶  התחלה",0xff7555e8,Color.WHITE);
            resetRect.set(right-311,521,right-215,565);button(c,resetRect,"↻  מחדש",0xffeeeaf8,0xff6a54b4);
            normalRect.set(right-410,521,right-319,565);button(c,normalRect,"רגיל 96",bpm==96?0xff7555e8:0xffeeeaf8,bpm==96?Color.WHITE:0xff6a54b4);
            slowRect.set(right-502,521,right-418,565);button(c,slowRect,"איטי 72",bpm==72?0xff7555e8:0xffeeeaf8,bpm==72?Color.WHITE:0xff6a54b4);
            soundRect.set(35,521,125,565);
            String volLabel=backingLevel==0?"🔇 OFF":backingLevel==1?"🔉 חלש":"🔊 חזק";
            button(c,soundRect,volLabel,0xffeeeaf8,0xff6a54b4);
            String detected=lastPitch<0 || SystemClock.elapsedRealtime()-lastPitchAt>600?"—":name((int)Math.round(lastPitch));
            text(c,"🎙  נשמע: "+detected,138,540,16,micOn?0xff298f70:0xffc46173,true,Paint.Align.LEFT);
            float meter=(float)Math.min(1,signalRms*13);
            round(c,140,553,225,559,3,0xffe1dfed);round(c,140,553,140+85*meter,559,3,0xff40cba6);
            permissionRect.set(123,514,275,577);
            diagnosticRect.set(290,521,478,565);
            button(c,diagnosticRect,diagnosticsMode?"✕ סגור בדיקה":"🎙 בדיקת תווים",
                diagnosticsMode?0xffd9f4e8:0xffeeeaf8,diagnosticsMode?0xff157e64:0xff6a54b4);
        }
        private void button(Canvas c,RectF r,String label,int bg,int fg){round(c,r.left,r.top,r.right,r.bottom,11,bg);text(c,label,r.centerX(),r.centerY()+6,17,fg,true,Paint.Align.CENTER);}
        private void round(Canvas c,float x,float y,float r,float b,float radius,int color){p.reset();p.setAntiAlias(true);p.setColor(color);p.setStyle(Paint.Style.FILL);c.drawRoundRect(new RectF(x,y,r,b),radius,radius,p);}
        private void circle(Canvas c,float x,float y,float rad,int col){p.reset();p.setAntiAlias(true);p.setColor(col);c.drawCircle(x,y,rad,p);}
        private void line(Canvas c,float x1,float y1,float x2,float y2,int col,float stroke){p.reset();p.setAntiAlias(true);p.setColor(col);p.setStrokeWidth(stroke);c.drawLine(x1,y1,x2,y2,p);}
        private void text(Canvas c,String s,float x,float y,float size,int color,boolean bold,Paint.Align align){
            p.reset();p.setAntiAlias(true);p.setColor(color);p.setTextSize(size);
            p.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",bold?Typeface.BOLD:Typeface.NORMAL));
            p.setTextAlign(align);c.drawText(s,x,y,p);
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            if(event.getAction()!=MotionEvent.ACTION_UP)return true;
            float x=event.getX()/scale,y=event.getY()/scale;
            if(phase==Phase.WELCOME || phase==Phase.TEACH_C ||
                    phase==Phase.TEACH_G || phase==Phase.TEACH_EXTRA ||
                    phase==Phase.PRACTICE){
                if(learnPrimaryRect.contains(x,y))advanceLesson();
                else if(learnBackRect.contains(x,y) && phase!=Phase.WELCOME){
                    if(phase==Phase.TEACH_C || phase==Phase.TEACH_EXTRA)
                        phase=Phase.WELCOME;
                    else if(phase==Phase.TEACH_G)phase=Phase.TEACH_C;
                    else if(phase==Phase.PRACTICE)
                        phase=levelIndex==0?Phase.TEACH_G:Phase.TEACH_EXTRA;
                    lessonRecognized=false;
                    status="מוכנים? נלמד עוד צעד קטן";
                }
                invalidate();
                return true;
            }
            if(phase==Phase.RESULTS && nextNoteRect.contains(x,y))
                learnAnotherNote();
            else if(practiceReturnRect.contains(x,y))returnToPractice();
            else if(playRect.contains(x,y))playPause();
            else if(resetRect.contains(x,y)){
                if(phase==Phase.RESULTS)phase=Phase.SONG;
                reset();
            }
            else if(slowRect.contains(x,y))setBpm(72);
            else if(normalRect.contains(x,y))setBpm(96);
            else if(diagnosticRect.contains(x,y)){
                if(playing)playPause();
                diagnosticsMode=!diagnosticsMode;
                if(!micOn)requestMicrophone();
                status=diagnosticsMode?"בדיקת מיקרופון ללא ליווי: נגנו דו אמצעי":"מוכנים לנגן עם הליווי";
            }
            else if(flexibleRect.contains(x,y)){
                flexibleTiming=!flexibleTiming;
                status=flexibleTiming?"מצב גמיש: אפשר לנגן מעט לפני או אחרי הקצב"
                    :"מצב מדויק: חלון הזמן מצומצם יותר";
            }
            else if(soundRect.contains(x,y)){
                backingLevel=(backingLevel+1)%3;
                if(player!=null)player.setVolume(backingVolume());
                status=backingLevel==0?"ליווי כבוי לבדיקת המיקרופון":
                    backingLevel==1?"ליווי חלש להפחתת זליגה למיקרופון":"ליווי בעוצמה רגילה";
            }else if(permissionRect.contains(x,y) && !micOn)requestMicrophone();
            invalidate();return true;
        }
    }
}
