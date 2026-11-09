package com.simplepianotest.app;

/**
 * A more forgiving score matcher for beginner players.
 * Time is expressed in accompaniment beats. This changes scoring only;
 * the backing track always keeps its original tempo.
 */
final class NoteTimingMatcher {
    private NoteTimingMatcher() {}

    static double earlyGrace(boolean flexible) { return flexible ? 0.45 : 0.16; }
    static double lateGrace(boolean flexible) { return flexible ? 0.60 : 0.16; }

    static boolean shouldMiss(int start, int length, double beat, boolean flexible) {
        return beat > start + length + lateGrace(flexible);
    }

    static int findMatch(int[] melody, int[] starts, int[] lengths,
                         int[] marks, double detectedMidi, double playbackBeat,
                         int bpm, boolean flexible) {
        // Typical capture-window / YIN callback latency is about 100–150 ms.
        // The measured pitch is associated with an earlier musical instant.
        double playedBeat = playbackBeat - (0.12 * bpm / 60.0);
        int best = -1;
        double bestScore = Double.POSITIVE_INFINITY;
        for(int i=0;i<melody.length;i++) {
            if(marks[i]==1)continue;
            double difference=Math.abs(detectedMidi - melody[i]);
            // The beginner mode accepts the same named note one octave higher.
            // This helps children who have a shorter keyboard or start on C5.
            // It never accepts a different pitch class.
            if(flexible) difference=Math.min(difference,
                    Math.abs(detectedMidi - melody[i] - 12.0));
            if(difference > 0.48)continue;
            if(playedBeat < starts[i] - earlyGrace(flexible))continue;
            if(playedBeat > starts[i] + lengths[i] + lateGrace(flexible))continue;

            // Favor the onset closest to the performance, to distinguish
            // consecutive repeated notes without giving both one hit.
            double score=Math.abs(playedBeat - starts[i]);
            if(marks[i]==2)score+=0.08;
            if(score<bestScore){bestScore=score;best=i;}
        }
        return best;
    }
}
