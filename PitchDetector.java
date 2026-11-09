package com.simplepianotest.app;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Process;

/** Microphone-based monophonic piano note detection, using YIN pitch estimation. */
final class PitchDetector {
    interface Listener { void onPitch(double frequencyHz, double confidence, double rms, boolean freshAttack); }
    private static final int SR = 22050;
    private static final int FRAME = 4096;
    private volatile boolean running;
    private Thread thread;
    private AudioRecord recorder;
    private final Listener listener;
    private double runningEnergy = .001;
    private long lastAttackMs = 0;
    private double lastFrequency = -1;
    private int stableCount = 0;

    PitchDetector(Listener listener) { this.listener = listener; }
    synchronized boolean start() {
        if (running) return true;
        int min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return false;
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SR, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 4));
            if (recorder.getState()!=AudioRecord.STATE_INITIALIZED) { recorder.release();recorder=null;return false; }
            recorder.startRecording();
            running=true;
            thread=new Thread(this::loop,"Piano-Pitch-Detector");thread.start();return true;
        } catch (Exception e) { if(recorder!=null){try{recorder.release();}catch(Exception ignored){}recorder=null;}return false; }
    }
    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        final short[] raw=new short[FRAME];
        final double[] signal=new double[FRAME];
        while (running) {
            int pos=0;
            while (running && pos < raw.length) {
                int n=recorder.read(raw,pos,raw.length-pos,AudioRecord.READ_BLOCKING);
                if(n<=0)break;pos+=n;
            }
            if (!running)break;
            if (pos != FRAME) continue;
            double power=0, avg=0;
            for(int i=0;i<FRAME;i++){double v=raw[i]/32768.0;signal[i]=v;avg+=v;}
            avg/=FRAME;
            for(int i=0;i<FRAME;i++){ signal[i]-=avg;power+=signal[i]*signal[i]; }
            double rms=Math.sqrt(power/FRAME);
            long now=android.os.SystemClock.elapsedRealtime();
            boolean attack=rms>.009 && rms>runningEnergy*1.4 && now-lastAttackMs>140;
            if(attack)lastAttackMs=now;
            runningEnergy=runningEnergy*.78+rms*.22;
            if(rms<.007) {stableCount=0;lastFrequency=-1;listener.onPitch(-1,0,rms,false);continue;}
            Result result=yin(signal);
            if(result.frequency>0 && result.confidence>.68){
                if(lastFrequency>0 && Math.abs(12*Math.log(result.frequency/lastFrequency)/Math.log(2)) < .5) stableCount++;
                else stableCount=1;
                lastFrequency=result.frequency;
                if(stableCount>=2)listener.onPitch(result.frequency,result.confidence,rms,attack || (now-lastAttackMs)<350);
            } else {stableCount=0;lastFrequency=-1;listener.onPitch(-1,0,rms,false);}
        }
    }
    private static class Result { final double frequency,confidence;Result(double f,double c){frequency=f;confidence=c;} }
    private static Result yin(double[] x) {
        final int half=x.length/2;
        final int minTau=SR/850, maxTau=SR/240; // C4..C5 with headroom
        double[] diff=new double[maxTau+2],norm=new double[maxTau+2];
        double running=0;
        int chosen=-1;
        for(int tau=1;tau<=maxTau+1;tau++) {
            double sum=0;
            for(int j=0;j<half;j++){double delta=x[j]-x[j+tau];sum+=delta*delta;}
            diff[tau]=sum; running+=sum;
            norm[tau]=running<1e-10?1:sum*tau/running;
        }
        for(int tau=minTau;tau<=maxTau;tau++) {
            if(norm[tau]<.21){
                while(tau<maxTau && norm[tau+1]<norm[tau])tau++;
                chosen=tau;break;
            }
        }
        if(chosen<0){double best=.28;for(int tau=minTau;tau<=maxTau;tau++){if(norm[tau]<best){best=norm[tau];chosen=tau;}}}
        if(chosen<0)return new Result(-1,0);
        double center=norm[chosen],prev=norm[chosen-1],next=norm[chosen+1];
        double denom=2*(2*center-prev-next);
        double shift=Math.abs(denom)>1e-10?(next-prev)/denom:0;
        shift=Math.max(-.5,Math.min(.5,shift));
        return new Result(SR/(chosen+shift),Math.max(0,1-center));
    }
    synchronized void stop(){
        running=false;
        if(recorder!=null){try{recorder.stop();}catch(Exception ignored){}}
        Thread t=thread;thread=null;
        if(t!=null && t!=Thread.currentThread()){ try{t.join(250);}catch(InterruptedException ignored){} }
        if(recorder!=null){recorder.release();recorder=null;}
    }
}
