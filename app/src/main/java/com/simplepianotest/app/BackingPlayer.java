package com.simplepianotest.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;

import java.util.Random;

/** A small, self-contained accompaniment synthesizer. No audio assets or network needed. */
final class BackingPlayer {
    private static final int SR = 22050;
    private static final int BEATS = 16;
    private static final int COUNT_IN = 4;
    private final int bpm;
    private AudioTrack track;
    private final short[] pcm;
    private Thread writerThread;
    private volatile int streamGeneration = 0;
    private boolean streamStarted = false;
    private float volume = 1.0f;

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
        pcm = new short[mix.length];
        for (int i = 0; i < mix.length; i++) {
            double shaped = Math.tanh(mix[i] * 1.2);
            pcm[i] = (short) Math.round(Math.max(-1.0, Math.min(1.0, shaped)) * 28000);
        }
        initializeStreamingTrack();
    }

    // MODE_STATIC requires Android to reserve the complete 15–20 second song as a
    // device audio buffer. Some devices (including Samsung models) reject that size.
    // MODE_STREAM reserves only a short buffer, filled by a dedicated audio thread.
    private void initializeStreamingTrack() {
        int minBuffer = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            throw new IllegalStateException("Unsupported audio output configuration: " + minBuffer);
        }
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(SR)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        track = new AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(minBuffer, SR / 2))
                .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            track.release();
            track = null;
            throw new IllegalStateException("Streaming AudioTrack initialization failed");
        }
        track.setVolume(volume);
        streamStarted = false;
    }

    void setVolume(float newVolume) {
        volume = newVolume;
        if (track != null) track.setVolume(newVolume);
    }

    void play() {
        final AudioTrack output = track;
        if (output == null) throw new IllegalStateException("Backing audio is unavailable");
        output.play();
        if (streamStarted) return; // Resume the existing playback position.
        streamStarted = true;
        final int generation = ++streamGeneration;
        writerThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            int offset = 0;
            while (generation == streamGeneration && offset < pcm.length) {
                int n = output.write(pcm, offset, Math.min(2048, pcm.length - offset),
                        AudioTrack.WRITE_BLOCKING);
                if (n <= 0) break;
                offset += n;
            }
        }, "SimplePiano-BackingWriter");
        writerThread.start();
    }

    void pause() {
        if (track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING)
            track.pause();
    }

    void restart() {
        discardTrack();
        initializeStreamingTrack();
    }

    double getBeatPosition() {
        AudioTrack t = track;
        return t == null ? 0 : Math.min(16.0,
                (t.getPlaybackHeadPosition() / (double) SR) * bpm / 60.0 - COUNT_IN);
    }

    private void discardTrack() {
        ++streamGeneration;
        AudioTrack old = track;
        track = null;
        if (old != null) {
            try { old.pause(); } catch (Exception ignored) {}
            try { old.flush(); } catch (Exception ignored) {}
            try { old.stop(); } catch (Exception ignored) {}
            old.release();
        }
        Thread writer = writerThread;
        writerThread = null;
        if (writer != null && writer != Thread.currentThread()) {
            try { writer.join(250); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        streamStarted = false;
    }

    void close() {
        discardTrack();
    }
}
