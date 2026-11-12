package com.simplepianotest.app;

import java.util.Arrays;

/** Regression tests for full-song replay with one extra child-owned pitch per level. */
public final class SongProgressionTest {
    private static void check(boolean ok,String message){
        if(!ok)throw new AssertionError(message);
    }
    public static void main(String[] args){
        check(SongProgression.levels()==5,"Song must have five micro-learning levels");
        int[] order={60,67,69,65,64,62};
        int[] prev=SongProgression.ownedPitches(0);
        check(Arrays.equals(prev,new int[]{60,67}),"First level must teach C and G");
        check(SongProgression.requiredNotes(0)==16,"First level regression: 16 child notes");
        for(int level=1;level<SongProgression.levels();level++){
            int[] owned=SongProgression.ownedPitches(level);
            check(owned.length==prev.length+1,"Every level adds exactly one pitch");
            check(owned[owned.length-1]==order[level+1],"Incorrect next pitch");
            for(int pitch:prev)check(SongProgression.owns(pitch,level),
                "Previously learned pitch must stay child-owned");
            int[] practice=SongProgression.practice(level);
            check(practice.length==4,"Keep micro-practice to four notes");
            check(practice[0]==SongProgression.newestPitch(level),
                "New note must appear first in practice");
            for(int pitch:practice)check(SongProgression.owns(pitch,level),
                "Never practice an unlearned pitch");
            check(SongProgression.requiredNotes(level)>SongProgression.requiredNotes(level-1),
                "Child should play more notes in each complete song replay");
            prev=owned;
        }
        check(SongProgression.requiredNotes(4)==SongData.MELODY.length,
            "Last level must give child every note in the song");
        check(!SongProgression.hasNext(4),"Progression must stop after all pitches");
        check(SongProgression.clamp(900)==4,"Saved level out of range must be clamped");
        System.out.println("Little Star: five full-song levels and growing child note ownership passed");
    }
}
