package com.simplepianotest.app;

/**
 * Public-domain nursery melody "Twinkle Twinkle Little Star".
 * Six phrases, 42 melody notes, 48 quarter-note beats.
 * Level 1: the child plays middle C and G; the app fills the other notes.
 */
final class SongData {
    static final int COUNT_IN = 4;
    static final int[] MELODY = {
        60,60,67,67,69,69,67,   // C C G G A A G
        65,65,64,64,62,62,60,   // F F E E D D C
        67,67,65,65,64,64,62,   // G G F F E E D
        67,67,65,65,64,64,62,   // G G F F E E D
        60,60,67,67,69,69,67,   // C C G G A A G
        65,65,64,64,62,62,60    // F F E E D D C
    };
    static final int[] LENGTHS = new int[MELODY.length];
    static final int[] STARTS = new int[MELODY.length];
    static final int TOTAL_BEATS;
    static final int REQUIRED_NOTES;

    static {
        int t=0, owned=0;
        for(int i=0;i<MELODY.length;i++) {
            LENGTHS[i]=(i%7==6)?2:1;
            STARTS[i]=t;
            t+=LENGTHS[i];
            if(isChildNote(MELODY[i]))owned++;
        }
        TOTAL_BEATS=t;
        REQUIRED_NOTES=owned;
    }

    static boolean isChildNote(int midi) {
        return midi==60 || midi==67;
    }

    private SongData() {}
}
