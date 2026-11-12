package com.simplepianotest.app;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Song-first micro-learning: the student starts with two pitches and
 * unlocks one additional pitch every time they finish the whole song.
 *
 * The order follows the first appearance in the Twinkle melody:
 * C, G, A, F, E, D. No note is added that is not in this song.
 */
final class SongProgression {
    private static final int[] ORDER=buildPitchOrder();

    private SongProgression() {}

    private static int[] buildPitchOrder(){
        List<Integer> distinct=new ArrayList<>();
        for(int pitch:SongData.MELODY){
            if(!distinct.contains(pitch))distinct.add(pitch);
        }
        int[] result=new int[distinct.size()];
        for(int i=0;i<result.length;i++)result[i]=distinct.get(i);
        return result;
    }

    static int levels(){return Math.max(1,ORDER.length-1);}

    static int clamp(int level){return Math.max(0,Math.min(level,levels()-1));}

    static int ownedCount(int level){return Math.min(ORDER.length,2+clamp(level));}

    static int[] ownedPitches(int level){return Arrays.copyOf(ORDER,ownedCount(level));}

    static boolean owns(int midi,int level){
        for(int pitch:ownedPitches(level))if(pitch==midi)return true;
        return false;
    }

    static boolean hasNext(int level){return clamp(level)<levels()-1;}

    static int nextPitch(int level){
        if(!hasNext(level))throw new IllegalStateException("All melody pitches learned");
        return ORDER[ownedCount(level)];
    }

    static int newestPitch(int level){
        return ORDER[ownedCount(level)-1];
    }

    static int[] practice(int level){
        if(clamp(level)==0)return new int[]{ORDER[0],ORDER[1],ORDER[0],ORDER[1]};
        int count=ownedCount(level);
        int fresh=ORDER[count-1];
        return new int[]{fresh,ORDER[count-2],fresh,ORDER[count-3]};
    }

    static int requiredNotes(int level){
        int result=0;
        for(int pitch:SongData.MELODY)if(owns(pitch,level))result++;
        return result;
    }
}
