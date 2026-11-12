package com.simplepianotest.app;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Plays pre-rendered voice clips from the APK. Playback does not require
 * internet, speech-engine installation, a user account or microphone access.
 */
final class RecordedVoicePlayer {
    private final Context context;
    private final Set<String> available=new HashSet<>();
    private MediaPlayer current;

    RecordedVoicePlayer(Context context){
        this.context=context.getApplicationContext();
        try {
            String[] files=this.context.getAssets().list("voice_he");
            if(files!=null)for(String name:files){
                if(name.endsWith(".mp3"))available.add(
                    name.substring(0,name.length()-4));
            }
        }catch(IOException ignored){}
    }
    boolean hasClips(){return !available.isEmpty();}
    boolean has(String id){return id!=null && available.contains(id);}
    boolean play(String id,Runnable completion){
        if(!has(id))return false;
        stop();
        MediaPlayer player=new MediaPlayer();
        try(AssetFileDescriptor fd=context.getAssets()
                .openFd("voice_he/"+id+".mp3")){
            player.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).build());
            player.setDataSource(fd.getFileDescriptor(),fd.getStartOffset(),fd.getLength());
            player.setOnCompletionListener(done->{
                if(current!=done)return;
                stop();
                completion.run();
            });
            player.setOnErrorListener((failed,what,extra)->{
                if(current==failed){stop();completion.run();}
                return true;
            });
            player.prepare();
            current=player;
            current.start();
            return true;
        }catch(Exception error){
            if(current==player)current=null;
            player.release();
            return false;
        }
    }
    void stop(){
        MediaPlayer playing=current;
        current=null;
        if(playing!=null){
            try{playing.stop();}catch(IllegalStateException ignored){}
            playing.release();
        }
    }
}
