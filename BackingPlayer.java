package com.simplepianotest.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

import java.util.Random;

/** A small, self-contained accompaniment synthesizer. No audio assets or network needed. */
final class BackingPlayer {
    private static final int SR = 22050;
    private static final int BEATS = 16;
    private static final int COUNT_IN = 4;
    private final int bpm;
    private AudioTrack track;

    BackingPlayer(int bpm) {
        this.bpm = bpm;
        final int frames = (int) Math.ceil((BEATS + COUNT_IN) * (60.0 / bpm) * SR) + SR / 8;
        float[] mix = new float[frames];
        double beatSec = 60.0 / bpm;
        int[][] chords = {{48, 52, 55}, {41, 45, 48}, {41, 45, 48}, {48, 52, 55}};
        for (int bar = 0; bar < 4; bar++) {
            double t = (COUNT_IN + bar * 4) * beatSec;
            for (int note : chords[bar]) addPad(mix, t, note, 3.7 * beatSec, 0.036f);
        }
        int[] bass = {36,36,43,43,45,45,43,43,41,41,40,40,43,43,36,36};
        Random random = new Random(10);
        for (int b = 0; b < BEATS; b++) {
            double t = (COUNT_IN + b) * beatSec;
            if (b % 2 == 0) addBass(mix, t, bass[b], .50 * beatSec);
            if (b % 4 == 0) addKick(mix, t);
            if (b % 4 == 2) addSnare(mix, t, random);
            addHat(mix, t, random);
        }
        for (int b=0;b<COUNT_IN;b++) addCountIn(mix, b*beatSec, b==0);
        short[] pcm = new short[mix.length];
        for (int i = 0; i < mix.length; i++) {
            double shaped = Math.tanh(mix[i] * 1.2);
            pcm[i] = (short) Math.round(Math.max(-1.0, Math.min(1.0, shaped)) * 28000);
        }
        AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build();
        AudioFormat format = new AudioFormat.Builder().setSampleRate(SR)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
        track = new AudioTrack.Builder().setAudioAttributes(attributes).setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length * 2).build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) throw new IllegalStateException("AudioTrack initialization failed");
        int offset = 0;
        while (offset < pcm.length) {
            int count = track.write(pcm, offset, pcm.length - offset, AudioTrack.WRITE_BLOCKING);
            if (count <= 0) throw new IllegalStateException("AudioTrack write failed: " + count);
            offset += count;
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
    private static void addCountIn(float[] mix, double start, boolean accent) {
        int first=(int)(start*SR); int len=Math.min((int)(SR*.08),mix.length-first);
        for(int i=0;i<len;i++) {double t=i/(double)SR;
            mix[first+i]+=(float)((accent?.12:.085)*Math.exp(-t*45)*Math.sin(2*Math.PI*(accent?1000:750)*t));
        }
    }
    void setVolume(float volume) { if (track != null) track.setVolume(volume); }
    void play() { if (track != null) track.play(); }
    void pause() { if (track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) track.pause(); }
    void restart() {
        if (track == null) return;
        track.pause();track.flush();
        track.stop();track.setPlaybackHeadPosition(0);
    }
    double getBeatPosition() {
        return track == null ? 0 : Math.min(16.0, (track.getPlaybackHeadPosition() / (double)SR) * bpm / 60.0 - COUNT_IN);
    }
    void close() { if(track!=null){try{track.stop();}catch(Exception ignored){} track.release();track=null;} }
}
