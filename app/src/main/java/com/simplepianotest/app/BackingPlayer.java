package com.simplepianotest.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * Device-compatible accompaniment using a locally generated PCM WAV file
 * and Android MediaPlayer. This avoids AudioTrack initialization limits
 * on some phones while keeping timing tied to the actual audio position.
 */
final class BackingPlayer {
    private static final int SR = 22050;
    private static final int BEATS = SongData.TOTAL_BEATS;
    private static final int COUNT_IN = SongData.COUNT_IN;
    private final int bpm;
    private final int[] ownedPitches;
    private final File waveFile;
    private MediaPlayer mediaPlayer;
    private float volume=0.65f;

    BackingPlayer(Context context, int bpm) throws IOException {
        this(context,bpm,new int[]{60,67});
    }
    BackingPlayer(Context context,int bpm,int[] ownedPitches)throws IOException {
        this.bpm=bpm;
        this.ownedPitches=ownedPitches.clone();
        waveFile=new File(context.getCacheDir(), "simplepiano_backing_"+bpm+".wav");
        generateWav();
        preparePlayer();
    }

    private void generateWav() throws IOException {
        final int frames=(int)Math.ceil((BEATS+COUNT_IN)*(60.0/bpm)*SR)+SR/8;
        float[] mix=new float[frames];
        double beatSec=60.0/bpm;
        int[][] chords={{48,52,55},{41,45,48},{41,45,48},{48,52,55}};
        for(int bar=0;bar<BEATS/4;bar++) {
            double t=(COUNT_IN+bar*4)*beatSec;
            for(int note:chords[bar%chords.length]) addPad(mix,t,note,3.7*beatSec,0.036f);
        }
        int[] bass={36,36,43,43,45,45,43,43,41,41,40,40,43,43,36,36};
        Random random=new Random(10);
        for(int b=0;b<BEATS;b++) {
            double t=(COUNT_IN+b)*beatSec;
            if(b%2==0)addBass(mix,t,bass[b%bass.length],.50*beatSec);
            if(b%4==0)addKick(mix,t);
            if(b%4==2)addSnare(mix,t,random);
            addHat(mix,t,random);
        }
        for(int b=0;b<COUNT_IN;b++)addCountIn(mix,b*beatSec,b==0);
        // Level 1: fill the melody only for notes the child has NOT learned.
        // Never synthesize the child's C4/G4 notes: microphone success must
        // reflect the real instrument, rather than the phone's own speaker.
        for(int i=0;i<SongData.MELODY.length;i++) {
            int midi=SongData.MELODY[i];
            if(!GoldenLesson.owns(midi,ownedPitches)) {
                double start=(COUNT_IN+SongData.STARTS[i])*beatSec;
                double duration=SongData.LENGTHS[i]*beatSec;
                addMelodyNote(mix,start,midi,duration);
            }
        }

        // Write little-endian 16-bit mono WAV. Generated only in app's private cache.
        int bytes=frames*2;
        try(FileOutputStream out=new FileOutputStream(waveFile)) {
            out.write(new byte[]{'R','I','F','F'});
            writeIntLE(out,36+bytes);
            out.write(new byte[]{'W','A','V','E','f','m','t',' '});
            writeIntLE(out,16);
            writeShortLE(out,1); // PCM
            writeShortLE(out,1); // mono
            writeIntLE(out,SR);
            writeIntLE(out,SR*2);
            writeShortLE(out,2);
            writeShortLE(out,16);
            out.write(new byte[]{'d','a','t','a'});
            writeIntLE(out,bytes);
            byte[] block=new byte[8192];int used=0;
            for(float value:mix){
                double shaped=Math.tanh(value*1.2);
                short sample=(short)Math.round(Math.max(-1,Math.min(1,shaped))*28000);
                block[used++]=(byte)(sample&0xff);
                block[used++]=(byte)((sample>>>8)&0xff);
                if(used>=block.length){out.write(block,0,used);used=0;}
            }
            if(used>0)out.write(block,0,used);
        }
    }
    private static void writeIntLE(FileOutputStream out,int v)throws IOException {
        out.write(v&255);out.write((v>>>8)&255);out.write((v>>>16)&255);out.write((v>>>24)&255);
    }
    private static void writeShortLE(FileOutputStream out,int v)throws IOException {
        out.write(v&255);out.write((v>>>8)&255);
    }

    private void preparePlayer() throws IOException {
        MediaPlayer p=new MediaPlayer();
        try {
            p.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
            p.setDataSource(waveFile.getAbsolutePath());
            p.prepare();
            p.setVolume(volume,volume);
            mediaPlayer=p;
        }catch(IOException | RuntimeException ex){
            p.release();
            throw ex;
        }
    }

    private static double freq(int midi) { return 440.0 * Math.pow(2, (midi - 69) / 12.0); }
    private static void addPad(float[] mix, double start, int note, double duration, float amp) {
        int first = (int)(start * SR); int length = Math.min(mix.length - first, (int)(duration * SR));
        double f = freq(note);
        for (int i = 0; i < length; i++) {
            double t = i / (double) SR;
            double envelope = Math.min(1, t / .22) * Math.min(1, (duration - t) / .38);
            mix[first + i] += (float)(amp * envelope * (Math.sin(2 * Math.PI * f * t) + .14 * Math.sin(4 * Math.PI * f * t)));
        }
    }
    private static void addBass(float[] mix, double start, int note, double duration) {
        int first = (int)(start * SR); int len = Math.min(mix.length - first, (int)(duration * SR));
        double f = freq(note);
        for (int i = 0; i < len; i++) {
            double t = i / (double) SR, env = Math.exp(-t * 4.5);
            mix[first + i] += (float)(.115 * env * Math.sin(2 * Math.PI * f * t));
        }
    }
    private static void addKick(float[] mix, double start) {
        int first = (int)(start * SR);int len=Math.min((int)(SR*.2),mix.length-first);
        for(int i=0;i<len;i++) { double t=i/(double)SR; mix[first+i]+=(float)(.17*Math.exp(-t*25)*Math.sin(2*Math.PI*(70-25*t)*t)); }
    }
    private static void addSnare(float[] mix, double start, Random r) {
        int first = (int)(start*SR); int len=Math.min((int)(SR*.13),mix.length-first);
        double smooth=0;
        for(int i=0;i<len;i++) { double t=i/(double)SR; double n=r.nextDouble()*2-1; smooth=.75*smooth+.25*n;
            mix[first+i]+=(float)(.045*Math.exp(-t*29)*(n-smooth)); }
    }
    private static void addHat(float[] mix, double start, Random r) {
        int first=(int)(start*SR); int len=Math.min((int)(SR*.055),mix.length-first);
        double prev=0;
        for(int i=0;i<len;i++) { double t=i/(double)SR;double n=r.nextDouble()*2-1;
            mix[first+i]+=(float)(.023*Math.exp(-t*65)*(n-prev));prev=n; }
    }
    private static void addMelodyNote(float[] mix,double start,int midi,double duration) {
        int first=(int)(start*SR);
        int len=Math.min(mix.length-first,(int)(duration*SR));
        double f=freq(midi);
        for(int i=0;i<len;i++){
            double t=i/(double)SR;
            double attack=Math.min(1.0,t/.018);
            double release=Math.min(1.0,(duration-t)/.11);
            double envelope=Math.max(0,attack*release)*Math.exp(-t*.45);
            double tone=Math.sin(2*Math.PI*f*t)
                +.23*Math.sin(2*Math.PI*2*f*t)+.08*Math.sin(2*Math.PI*3*f*t);
            mix[first+i]+=(float)(.13*envelope*tone);
        }
    }
    private static void addCountIn(float[] mix, double start, boolean accent) {
        int first=(int)(start*SR); int len=Math.min((int)(SR*.08),mix.length-first);
        for(int i=0;i<len;i++) {double t=i/(double)SR;
            mix[first+i]+=(float)((accent?.12:.085)*Math.exp(-t*45)*Math.sin(2*Math.PI*(accent?1000:750)*t));
        }
    }

    void setVolume(float v) {
        volume=v;
        if(mediaPlayer!=null) mediaPlayer.setVolume(v,v);
    }
    void play() {
        if(mediaPlayer==null)throw new IllegalStateException("MediaPlayer is null");
        mediaPlayer.start();
    }
    void pause() {
        if(mediaPlayer!=null && mediaPlayer.isPlaying())mediaPlayer.pause();
    }
    void restart() {
        if(mediaPlayer!=null){mediaPlayer.release();mediaPlayer=null;}
        try {preparePlayer();}
        catch(IOException ex){throw new IllegalStateException("Unable to restart backing WAV",ex);}
    }
    double getBeatPosition() {
        if(mediaPlayer==null)return 0;
        return Math.min(BEATS,mediaPlayer.getCurrentPosition()/(double)1000*bpm/60.0-COUNT_IN);
    }
    void close() {
        if(mediaPlayer!=null){mediaPlayer.release();mediaPlayer=null;}
    }
}
