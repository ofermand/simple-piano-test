package com.simplepianotest.app;

import java.util.List;

final class GoldenLesson {
    static int[] choose(List<GoldenMidi.Note> melody) {
        if(melody==null || melody.isEmpty())return new int[]{60,67};
        // Choose frequently recurring notes near the beginning, so that
        // the child gets many chances to participate from the first chorus.
        int[] count=new int[12];
        long firstStart=melody.get(0).startMs;
        for(GoldenMidi.Note n:melody) {
            if(n.startMs-firstStart>32000)break;
            count[Math.floorMod(n.midi,12)]++;
        }
        int a=0,b=0;
        for(int i=0;i<12;i++){
            if(count[i]>count[a])a=i;
        }
        b=(a+7)%12;
        for(int i=0;i<12;i++){
            if(i!=a && count[i]>count[b])b=i;
        }
        if(b==a || count[b]==0){
            for(GoldenMidi.Note n:melody){
                if(n.midi%12!=a){b=n.midi%12;break;}
            }
        }
        return new int[]{60+a,60+b};
    }
    static boolean owns(int midi,int[] pitches) {
        return Math.floorMod(midi,12)==Math.floorMod(pitches[0],12) ||
               Math.floorMod(midi,12)==Math.floorMod(pitches[1],12);
    }
    static boolean sameNote(double heard,int target){
        double diff=Math.abs(heard-target);
        diff=Math.min(diff,Math.abs(heard-target-12));
        diff=Math.min(diff,Math.abs(heard-target+12));
        return diff<=.58;
    }
    static boolean withinTime(long measuredMs,GoldenMidi.Note note){
        return measuredMs>=note.startMs-380 && measuredMs<=note.endMs+570;
    }
    static String name(int midi){
        String[] names={"דו","דו♯","רה","רה♯","מי","פה","פה♯","סול","סול♯","לה","לה♯","סי"};
        return names[Math.floorMod(midi,12)];
    }
    static int normalized(int midi){return 60+Math.floorMod(midi,12);}
    static boolean sharp(int midi){
        int pitch=Math.floorMod(midi,12);
        return pitch==1 || pitch==3 || pitch==6 || pitch==8 || pitch==10;
    }
    static float staffY(int midi,float bottom,float gap) {
        int m=normalized(midi);
        // Draw sharp notes on their natural note's staff position.
        int pitch=Math.floorMod(m,12);
        int base=pitch==1?0:pitch==3?2:pitch==6?5:
                 pitch==8?7:pitch==10?9:pitch;
        switch(base){
            case 0:return bottom+gap;
            case 2:return bottom+gap*.5f;
            case 4:return bottom;
            case 5:return bottom-gap*.5f;
            case 7:return bottom-gap;
            case 9:return bottom-gap*1.5f;
            case 11:return bottom-gap*2;
            default:return bottom;
        }
    }
    private GoldenLesson(){}
}
