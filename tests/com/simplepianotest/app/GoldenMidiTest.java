package com.simplepianotest.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

public final class GoldenMidiTest {
    private static void ok(boolean expr,String message) {
        if(!expr)throw new AssertionError(message);
    }
    private static void put32(ByteArrayOutputStream out,int n) {
        out.write((n>>>24)&255);out.write((n>>>16)&255);
        out.write((n>>>8)&255);out.write(n&255);
    }
    private static byte[] fixture()throws IOException {
        ByteArrayOutputStream body=new ByteArrayOutputStream();
        int[] pitches={60,67,60,67};
        for(int pitch:pitches){
            body.write(0);body.write(0x90);body.write(pitch);body.write(90);
            body.write(0x83);body.write(0x60); // delta: 480 ticks
            body.write(0x80);body.write(pitch);body.write(0);
        }
        body.write(0);body.write(0xFF);body.write(0x2F);body.write(0);
        ByteArrayOutputStream midi=new ByteArrayOutputStream();
        midi.write(new byte[]{'M','T','h','d'});put32(midi,6);
        midi.write(new byte[]{0,0,0,1,1,(byte)0xE0}); // format=0, tracks=1, ppq=480
        midi.write(new byte[]{'M','T','r','k'});put32(midi,body.size());
        midi.write(body.toByteArray());
        return midi.toByteArray();
    }
    public static void main(String[] args)throws Exception {
        GoldenMidi.Song song=GoldenMidi.parse(fixture());
        ok(song.tracks.size()==1,"Should parse one melody track");
        List<GoldenMidi.Note> notes=song.tracks.get(0).notes;
        ok(notes.size()==4,"Should parse all four piano notes");
        ok(notes.get(0).midi==60 && notes.get(1).midi==67,"Wrong note pitches");
        ok(notes.get(1).startMs==500,"Tempo conversion failed");
        ok(notes.get(3).endMs==2000,"MIDI duration incorrect");
        int[] beginner=GoldenLesson.choose(notes);
        ok(beginner[0]==60 && beginner[1]==67,"Wrong two-note learning set");
        ok(GoldenLesson.owns(72,beginner),"Same note in another octave must count");
        ok(!GoldenLesson.owns(69,beginner),"Unlearned note must stay with backing");
        ok(GoldenLesson.sameNote(72.2,60),"Octave-tolerant pitch mismatch");
        ok(!GoldenLesson.sameNote(62,60),"Different note was credited");
        ok(GoldenLesson.withinTime(330,notes.get(1)),"Early but correct performance missed");
        ok(GoldenLesson.staffY(60,100,20)==120,"C4 ledger line incorrect");
        ok(GoldenLesson.staffY(67,100,20)==80,"G4 staff line incorrect");
        ok(GoldenLesson.sharp(66) && !GoldenLesson.sharp(67),"Black keyboard note mapping wrong");
        GoldenProgression base=new GoldenProgression(notes);
        ok(base.levelCount()==1,"Two-pitch practice should have just one level");
        ok(base.totalOwned(notes,0)==4,"Beginner must own all notes in two-pitch fixture");
        List<GoldenMidi.Note> advanced=java.util.Arrays.asList(
            new GoldenMidi.Note(60,0,300),
            new GoldenMidi.Note(67,500,800),
            new GoldenMidi.Note(69,1000,1300),
            new GoldenMidi.Note(62,1500,1800),
            new GoldenMidi.Note(60,2000,2300),
            new GoldenMidi.Note(67,2500,2800));
        GoldenProgression campaign=new GoldenProgression(advanced);
        ok(campaign.levelCount()==3,"Four pitch classes should create three song levels");
        ok(campaign.ownedAt(0).length==2,"First level starts with two notes");
        ok(campaign.ownedAt(1).length==3,"First upgrade adds exactly one note");
        ok(campaign.ownedAt(2).length==4,"Last upgrade includes every melody note");
        ok(!campaign.hasNext(2),"Full melody should finish the note-unlock path");
        ok(campaign.totalOwned(advanced,0)==4,"Beginner ownership is too broad");
        ok(campaign.totalOwned(advanced,2)==6,"Highest level must cover whole song");
        ok(GoldenLesson.owns(74,campaign.ownedAt(2)),"All learned pitch classes count across octaves");
        System.out.println("Golden: MIDI, progressively unlocked full-song parts and keyboard passed");
    }
}
