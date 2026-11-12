package com.simplepianotest.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Every level plays the WHOLE song.
 *
 * At level 0 the child owns the two most useful note classes.
 * Every following level unlocks one additional class. The backing
 * plays all other melody notes, and never plays the child's notes.
 */
final class GoldenProgression {
    private final int[] order;

    GoldenProgression(List<GoldenMidi.Note> melody) {
        if (melody == null || melody.isEmpty())
            throw new IllegalArgumentException("A melody is required");
        int[] basic=GoldenLesson.choose(melody);
        boolean[] included=new boolean[12];
        List<Integer> pitches=new ArrayList<>();
        addUnique(pitches,included,basic[0]%12);
        addUnique(pitches,included,basic[1]%12);
        final double[] weights=new double[12];
        long first=melody.get(0).startMs;
        for(GoldenMidi.Note note:melody){
            int p=Math.floorMod(note.midi,12);
            weights[p]+=1.0;
            if(note.startMs-first<32000)weights[p]+=.65;
        }
        List<Integer> remaining=new ArrayList<>();
        for(int p=0;p<12;p++)
            if(!included[p] && weights[p]>0)remaining.add(p);
        remaining.sort((a,b)->{
            int score=Double.compare(weights[b],weights[a]);
            return score==0?Integer.compare(a,b):score;
        });
        for(int p:remaining)addUnique(pitches,included,p);
        order=new int[pitches.size()];
        for(int i=0;i<order.length;i++)order[i]=60+pitches.get(i);
    }

    private static void addUnique(List<Integer> pitches,boolean[] included,int raw) {
        int p=Math.floorMod(raw,12);
        if(!included[p]){included[p]=true;pitches.add(p);}
    }

    int levelCount(){return Math.max(1,order.length-1);}
    int clamp(int level){return Math.max(0,Math.min(level,levelCount()-1));}
    int[] ownedAt(int level){
        int count=Math.min(order.length,2+clamp(level));
        return Arrays.copyOf(order,count);
    }
    int newestPitch(int level){
        int[] owned=ownedAt(level);
        return owned[owned.length-1];
    }
    boolean hasNext(int level){return clamp(level)<levelCount()-1;}
    boolean childOwns(int midi,int level){
        int pitch=Math.floorMod(midi,12);
        for(int n:ownedAt(level))
            if(Math.floorMod(n,12)==pitch)return true;
        return false;
    }
    int totalOwned(List<GoldenMidi.Note> melody,int level){
        int n=0;
        for(GoldenMidi.Note note:melody)if(childOwns(note.midi,level))n++;
        return n;
    }
}
