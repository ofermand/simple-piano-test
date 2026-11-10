package com.simplepianotest.app;

public final class NoteTimingMatcherTest {
    private static final int[] MELODY={60,60,67,67,69,69,67,65,65,64,64,62,62,60};
    private static final int[] LENGTHS={1,1,1,1,1,1,2,1,1,1,1,1,1,2};
    private static final int[] STARTS=new int[MELODY.length];
    static {
        int beats=0;
        for(int i=0;i<MELODY.length;i++){ STARTS[i]=beats; beats+=LENGTHS[i];}
    }
    private static int match(double midi, double beat, boolean flexible, int... credited) {
        int[] marks=new int[MELODY.length];
        for(int i:credited)marks[i]=1;
        return NoteTimingMatcher.findMatch(MELODY,STARTS,LENGTHS,
                marks,midi,beat,72,flexible);
    }
    private static void check(String label, int expected, int actual) {
        if(expected!=actual) throw new AssertionError(
                label+" expected "+expected+" but got "+actual);
    }
    public static void main(String[] args) {
        check("first note",0,match(60.08,0.2,true));
        check("second repeated C",1,match(60.05,1.18,true,0));
        check("late correct note",1,match(60.02,2.38,true,0));
        check("early next note",2,match(67.02,1.78,true));
        check("wrong pitch", -1,match(62.05,0.25,true));
        check("octave up in flexible mode",0,match(72.0,.30,true));
        check("octave down in flexible mode",0,match(48.0,.30,true));
        check("octave down is not exact mode",-1,match(48.0,.30,false));
        check("octave not counted in accurate mode",-1,match(72.0,.30,false));
        check("too late even in flexible mode",-1,match(60.0,2.95,true));
        check("accurate mode is less forgiving",-1,match(67.0,1.78,false));
        if(NoteTimingMatcher.shouldMiss(0,1,1.3,true))
            throw new AssertionError("Flexible note was marked missed too soon");
        if(!NoteTimingMatcher.shouldMiss(0,1,1.65,true))
            throw new AssertionError("Flexible note was not marked missed");
        if(SongData.MELODY.length!=42 || SongData.TOTAL_BEATS!=48 ||
                SongData.REQUIRED_NOTES!=16)
            throw new AssertionError("Full-song melody or child ownership mismatch");
        int[] fullMarks=new int[SongData.MELODY.length];
        for(int i=0;i<fullMarks.length;i++)
            fullMarks[i]=SongData.isChildNote(SongData.MELODY[i])?0:3;
        int autoNote=NoteTimingMatcher.findMatch(
            SongData.MELODY,SongData.STARTS,SongData.LENGTHS,
            fullMarks,69,4.3,72,true);
        check("auto-played A must not earn success",-1,autoNote);
        int playedChild=NoteTimingMatcher.findMatch(
            SongData.MELODY,SongData.STARTS,SongData.LENGTHS,
            fullMarks,60,0.25,72,true);
        check("child C still counts",0,playedChild);
        System.out.println("Simple Piano: timing, full-song and ownership tests passed");
    }
}
