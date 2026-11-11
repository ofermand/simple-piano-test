package com.simplepianotest.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Synthesizes only those imported melody notes the student doesn't play.
 * Parallel local WAV playback can accompany licensed instrumental audio.
 * A future production engine should sample-lock backing and guide tracks.
 */
final class GoldenGuidePlayer {
    private static final int SR=22050;
    private final File file;
    private MediaPlayer player;
    GoldenGuidePlayer(Context context,List<GoldenMidi.Note> notes,int[] teaching)
            throws IOException {
        file=new File(context.getCacheDir(),"golden_guide.wav");
        generate(notes,teaching);
        player=new MediaPlayer();
        try{
            player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            player.setDataSource(file.getAbsolutePath());
            player.prepare();
            player.setVolume(.52f,.52f);
        }catch(IOException | RuntimeException e){
            player.release();
            player=null;
            throw e;
        }
    }
    private void generate(List<GoldenMidi.Note> melody,int[] teaching)throws IOException {
        long last=0;
        for(GoldenMidi.Note n:melody)last=Math.max(last,n.endMs);
        // Limit fixture length to 5 minutes for safe in-memory synthesis.
        int frames=(int)Math.min(5L*60L*SR,
            Math.max(SR,Math.ceil((last+600)/1000.0*SR)));
        float[] mix=new float[frames];
        for(GoldenMidi.Note n:melody){
            if(GoldenLesson.owns(n.midi,teaching))continue;
            int first=(int)(n.startMs/1000.0*SR);
            if(first>=frames || first<0)continue;
            int length=Math.min(frames-first,
                Math.max(1,(int)((n.endMs-n.startMs)/1000.0*SR)));
            double freq=440.0*Math.pow(2,(n.midi-69)/12.0);
            for(int i=0;i<length;i++){
                double t=i/(double)SR;
                double remaining=(length-i)/(double)SR;
                double env=Math.min(1,t/.018)*Math.min(1,remaining/.10)
                    * Math.exp(-t*.34);
                double wave=Math.sin(2*Math.PI*freq*t)
                    +.28*Math.sin(4*Math.PI*freq*t);
                mix[first+i]+=(float)(.11*env*wave);
            }
        }
        try(FileOutputStream out=new FileOutputStream(file)){
            out.write(new byte[]{'R','I','F','F'});
            little32(out,36+frames*2);
            out.write(new byte[]{'W','A','V','E','f','m','t',' '});
            little32(out,16);
            little16(out,1);little16(out,1);
            little32(out,SR);little32(out,SR*2);
            little16(out,2);little16(out,16);
            out.write(new byte[]{'d','a','t','a'});
            little32(out,frames*2);
            byte[] block=new byte[4096];int used=0;
            for(float level:mix){
                int v=(int)(Math.max(-1,Math.min(1,level))*26000);
                block[used++]=(byte)(v&255);
                block[used++]=(byte)((v>>>8)&255);
                if(used==block.length){out.write(block);used=0;}
            }
            if(used>0)out.write(block,0,used);
        }
    }
    private static void little16(FileOutputStream out,int n)throws IOException {
        out.write(n&255);out.write((n>>>8)&255);
    }
    private static void little32(FileOutputStream out,int n)throws IOException {
        out.write(n&255);out.write((n>>>8)&255);
        out.write((n>>>16)&255);out.write((n>>>24)&255);
    }
    void play(){if(player!=null)player.start();}
    void pause(){if(player!=null && player.isPlaying())player.pause();}
    void setVolume(float v){if(player!=null)player.setVolume(v,v);}
    void alignTo(long ms){
        if(player!=null)player.seekTo((int)Math.max(0,ms));
    }
    void close(){
        if(player!=null){player.release();player=null;}
    }
}
