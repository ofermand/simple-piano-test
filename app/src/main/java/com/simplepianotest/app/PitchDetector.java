package com.simplepianotest.app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;
import android.os.SystemClock;

/**
 * Monophonic piano pitch estimation with overlapping YIN windows.
 * Captures with UNPROCESSED / VOICE_RECOGNITION where supported, rather than
 * relying only on MIC processing that may remove piano transients.
 */
final class PitchDetector {
    interface Listener { void onPitch(double frequencyHz, double confidence, double rms, boolean freshAttack); }
    private static final int SR = 22050;
    private static final int FRAME = 4096;
    private static final int HOP = 1024;
    private static final double MIN_RMS = 0.0025;
    private static final double MIN_CONFIDENCE = 0.52;
    private final Context context;
    private final Listener listener;
    private volatile boolean running;
    private Thread thread;
    private AudioRecord recorder;
    private String sourceName="not started";
    private double runningEnergy=0.001;
    private long lastAttackMs;
    private double lastFrequency=-1;
    private int stableCount;

    PitchDetector(Context context, Listener listener) {
        this.context=context.getApplicationContext();
        this.listener=listener;
    }
    String getSourceName(){return sourceName;}

    synchronized boolean start() {
        if(running)return true;
        int min=AudioRecord.getMinBufferSize(SR,AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if(min<=0){sourceName="Unsupported input configuration: "+min;return false;}
        AudioManager manager=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        boolean raw=manager!=null && "true".equalsIgnoreCase(
            manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        int[] attempts=raw
             ? new int[]{MediaRecorder.AudioSource.UNPROCESSED,
                         MediaRecorder.AudioSource.VOICE_RECOGNITION,
                         MediaRecorder.AudioSource.MIC}
             : new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION,
                         MediaRecorder.AudioSource.MIC};
        for(int source:attempts){
            AudioRecord candidate=null;
            try {
                candidate=new AudioRecord(source, SR, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,Math.max(min,FRAME*4));
                if(candidate.getState()!=AudioRecord.STATE_INITIALIZED) {
                    candidate.release();
                    continue;
                }
                candidate.startRecording();
                if(candidate.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING) {
                    candidate.release();
                    continue;
                }
                recorder=candidate;
                sourceName=source==MediaRecorder.AudioSource.UNPROCESSED?"UNPROCESSED":
                    source==MediaRecorder.AudioSource.VOICE_RECOGNITION?"VOICE_RECOGNITION":"MIC";
                runningEnergy=.001;
                lastFrequency=-1;
                stableCount=0;
                lastAttackMs=0;
                running=true;
                thread=new Thread(this::loop,"SimplePiano-PitchDetector");
                thread.start();
                return true;
            }catch(RuntimeException ex){
                if(candidate!=null)try{candidate.release();}catch(Exception ignored){}
                sourceName="Input error: "+ex.getClass().getSimpleName();
            }
        }
        return false;
    }
    private void loop(){
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        final short[] raw=new short[FRAME];
        final double[] signal=new double[FRAME];
        int filled=0;
        while(running){
            if(filled==FRAME){
                System.arraycopy(raw,HOP,raw,0,FRAME-HOP);
                filled=FRAME-HOP;
            }
            AudioRecord input=recorder;
            if(input==null)break;
            int n;
            try {
                n=input.read(raw,filled,FRAME-filled,AudioRecord.READ_BLOCKING);
            }catch(IllegalStateException ex){break;}
            if(n<=0){
                if(!running)break;
                continue;
            }
            filled+=n;
            if(filled<FRAME)continue;
            double mean=0, power=0;
            for(short sample:raw)mean+=sample/32768.0;
            mean/=FRAME;
            for(int i=0;i<FRAME;i++){
                double value=raw[i]/32768.0-mean;
                signal[i]=value;
                power+=value*value;
            }
            double rms=Math.sqrt(power/FRAME);
            long now=SystemClock.elapsedRealtime();
            boolean attack=rms>.004 && rms>runningEnergy*1.35 && now-lastAttackMs>120;
            if(attack)lastAttackMs=now;
            runningEnergy=runningEnergy*.85+rms*.15;
            if(rms<MIN_RMS){
                stableCount=0;
                lastFrequency=-1;
                listener.onPitch(-1,0,rms,false);
                continue;
            }
            Result result=yin(signal);
            if(result.frequency>0 && result.confidence>=MIN_CONFIDENCE){
                if(lastFrequency>0 &&
                   Math.abs(12*Math.log(result.frequency/lastFrequency)/Math.log(2))<0.65)
                    stableCount++;
                else stableCount=1;
                lastFrequency=result.frequency;
                if(stableCount>=2)
                    listener.onPitch(result.frequency,result.confidence,rms,
                        attack || now-lastAttackMs<220);
                else listener.onPitch(-1,result.confidence,rms,attack);
            }else{
                stableCount=0;
                lastFrequency=-1;
                listener.onPitch(-1,result.confidence,rms,attack);
            }
        }
    }
    private static class Result {
        final double frequency,confidence;
        Result(double frequency,double confidence){
            this.frequency=frequency;
            this.confidence=confidence;
        }
    }
    private static Result yin(double[] x){
        int half=x.length/2;
        // Include the octave below middle C (C3 ~= 131 Hz).
        int minTau=SR/900,maxTau=SR/110;
        double[] normalized=new double[maxTau+2];
        double sum=0;
        for(int tau=1;tau<=maxTau+1;tau++){
            double difference=0;
            for(int j=0;j<half;j++){
                double delta=x[j]-x[j+tau];
                difference+=delta*delta;
            }
            sum+=difference;
            normalized[tau]=sum<1e-10?1:difference*tau/sum;
        }
        int chosen=-1;
        for(int tau=minTau;tau<=maxTau;tau++){
            if(normalized[tau]<.26){
                while(tau<maxTau && normalized[tau+1]<normalized[tau])tau++;
                chosen=tau;
                break;
            }
        }
        if(chosen<0){
            double best=.40;
            for(int tau=minTau;tau<=maxTau;tau++){
                if(normalized[tau]<best){best=normalized[tau];chosen=tau;}
            }
        }
        if(chosen<0)return new Result(-1,0);
        double center=normalized[chosen],prev=normalized[chosen-1],next=normalized[chosen+1];
        double denom=2*(2*center-prev-next);
        double shift=Math.abs(denom)>1e-10?(next-prev)/denom:0;
        shift=Math.max(-.5,Math.min(.5,shift));
        return new Result(SR/(chosen+shift),Math.max(0,1-center));
    }
    synchronized void stop(){
        running=false;
        AudioRecord input=recorder;
        if(input!=null)try{input.stop();}catch(Exception ignored){}
        Thread t=thread;
        thread=null;
        if(t!=null && t!=Thread.currentThread()){
            try{t.join(350);}catch(InterruptedException ex){
                Thread.currentThread().interrupt();
            }
        }
        recorder=null;
        if(input!=null)try{input.release();}catch(Exception ignored){}
    }
}
