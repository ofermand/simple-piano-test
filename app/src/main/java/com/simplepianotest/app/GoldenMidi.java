package com.simplepianotest.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a user-provided, locally licensed Standard MIDI File (format 0 or 1).
 * Nothing is downloaded. Note timings are converted with the MIDI tempo map.
 */
final class GoldenMidi {
    static final class Note {
        final int midi;
        final long startMs,endMs;
        Note(int midi,long startMs,long endMs) {
            this.midi=midi; this.startMs=startMs;
            this.endMs=Math.max(startMs+90,endMs);
        }
    }
    static final class Track {
        final String name;
        final List<Note> notes;
        Track(String name,List<Note> notes){this.name=name;this.notes=notes;}
    }
    static final class Song {
        final List<Track> tracks;
        final int ticksPerQuarter;
        Song(List<Track> tracks,int ticksPerQuarter) {
            this.tracks=tracks;this.ticksPerQuarter=ticksPerQuarter;
        }
    }
    private static final class TickNote {
        final int midi;
        final long start,end;
        TickNote(int midi,long start,long end){this.midi=midi;this.start=start;this.end=end;}
    }
    private static final class Tempo {
        final long tick;
        final long microsPerQuarter;
        Tempo(long tick,long us){this.tick=tick;this.microsPerQuarter=us;}
    }
    private static final class RawTrack {
        String name="";
        final List<TickNote> notes=new ArrayList<>();
    }
    private static final class Cursor {
        final byte[] data;
        int p;
        Cursor(byte[] data){this.data=data;}
        void need(int n)throws IOException {
            if(n<0 || p+n>data.length)throw new IOException("MIDI file truncated");
        }
        int u8()throws IOException{need(1);return data[p++]&255;}
        int u16()throws IOException{return (u8()<<8)|u8();}
        long u32()throws IOException{return ((long)u16()<<16)|u16();}
        int var()throws IOException{
            int n=0;
            for(int i=0;i<4;i++){
                int b=u8();
                n=(n<<7)|(b&127);
                if((b&128)==0)return n;
            }
            throw new IOException("Invalid MIDI variable-length integer");
        }
        void skip(int n)throws IOException{need(n);p+=n;}
        String str(int n)throws IOException{need(n);
            String s=new String(data,p,n,StandardCharsets.UTF_8);
            p+=n;return s;
        }
    }
    static Song parse(InputStream stream)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        byte[] block=new byte[8192];
        int n,total=0;
        while((n=stream.read(block))!=-1) {
            total+=n;
            if(total>5_000_000)throw new IOException("MIDI must be under 5 MB");
            out.write(block,0,n);
        }
        return parse(out.toByteArray());
    }
    static Song parse(byte[] bytes)throws IOException{
        Cursor c=new Cursor(bytes);
        if(!"MThd".equals(c.str(4)))throw new IOException("Not a Standard MIDI file");
        int headerLength=(int)c.u32();
        if(headerLength<6 || headerLength>64)throw new IOException("Invalid MIDI header");
        int format=c.u16(),trackCount=c.u16(),division=c.u16();
        c.skip(headerLength-6);
        if(format>1)throw new IOException("Only MIDI format 0 or 1 supported");
        if((division&0x8000)!=0 || division==0)
            throw new IOException("SMPTE-timed MIDI is not supported");
        if(trackCount<1 || trackCount>128)throw new IOException("Invalid MIDI track count");
        List<Tempo> tempos=new ArrayList<>();
        tempos.add(new Tempo(0,500000));
        List<RawTrack> raw=new ArrayList<>();
        for(int t=0;t<trackCount;t++){
            if(!"MTrk".equals(c.str(4)))throw new IOException("Invalid MIDI track");
            long rawLength=c.u32();
            if(rawLength<0 || rawLength>c.data.length-c.p)
                throw new IOException("Invalid MIDI track size");
            int end=c.p+(int)rawLength;
            RawTrack track=new RawTrack();
            long ticks=0;
            int running=0;
            Map<Integer,Long> active=new HashMap<>();
            while(c.p<end) {
                ticks+=c.var();
                int cmd=c.u8();
                if(cmd<128){
                    if(running==0)throw new IOException("Invalid MIDI running status");
                    c.p--;
                    cmd=running;
                }else if(cmd<240){
                    running=cmd;
                }
                if(cmd==0xFF){
                    int kind=c.u8(),length=c.var();
                    c.need(length);
                    if(kind==0x51 && length==3){
                        long mpq=((long)c.u8()<<16)|(c.u8()<<8)|c.u8();
                        if(mpq>0)tempos.add(new Tempo(ticks,mpq));
                    }else if(kind==0x03){
                        track.name=c.str(length);
                    }else c.skip(length);
                    if(kind==0x2F){c.p=end;break;}
                    running=0;
                }else if(cmd==0xF0 || cmd==0xF7){
                    c.skip(c.var());
                    running=0;
                }else if(cmd>=0xF0){
                    throw new IOException("Unsupported MIDI system event");
                }else {
                    int kind=cmd&0xF0, channel=cmd&15;
                    int a=c.u8();
                    int b=(kind==0xC0 || kind==0xD0)?0:c.u8();
                    if(kind==0x90 && b>0){
                        int key=channel*128+a;
                        Long previous=active.put(key,ticks);
                        if(previous!=null && ticks>previous)
                            track.notes.add(new TickNote(a,previous,ticks));
                    }else if(kind==0x80 || (kind==0x90 && b==0)){
                        Long start=active.remove(channel*128+a);
                        if(start!=null && ticks>start)
                            track.notes.add(new TickNote(a,start,ticks));
                    }
                }
            }
            if(c.p!=end)c.p=end;
            for(Map.Entry<Integer,Long> entry:active.entrySet())
                if(ticks>entry.getValue())
                    track.notes.add(new TickNote(entry.getKey()%128,entry.getValue(),ticks));
            raw.add(track);
        }
        tempos.sort(Comparator.comparingLong(x->x.tick));
        List<Track> tracks=new ArrayList<>();
        for(int i=0;i<raw.size();i++) {
            RawTrack rt=raw.get(i);
            List<Note> notes=new ArrayList<>();
            for(TickNote tn:rt.notes){
                long start=Math.round(ms(tn.start,tempos,division));
                long end=Math.round(ms(tn.end,tempos,division));
                if(tn.midi>=36 && tn.midi<=108 && end>start)
                    notes.add(new Note(tn.midi,start,end));
            }
            notes.sort(Comparator.comparingLong(x->x.startMs));
            if(notes.size()>=4)
                tracks.add(new Track(rt.name.isEmpty()?"Track "+(i+1):rt.name,notes));
        }
        if(tracks.isEmpty())throw new IOException("No playable melody tracks found");
        return new Song(tracks,division);
    }
    private static double ms(long tick,List<Tempo> tempos,int ppq) {
        long lastTick=0,mpq=500000;
        double micros=0;
        for(Tempo tempo:tempos){
            if(tempo.tick>tick)break;
            if(tempo.tick>lastTick)micros+=(tempo.tick-lastTick)*(mpq/(double)ppq);
            lastTick=tempo.tick;
            mpq=tempo.microsPerQuarter;
        }
        micros+=(tick-lastTick)*(mpq/(double)ppq);
        return micros/1000;
    }
    static int[] twoTeachingPitches(List<Note> notes) {
        // First two distinct musical pitches, transposed to the C4-B4 region.
        // The child can play either octave; lessons focus on note identity.
        if(notes.isEmpty())return new int[]{60,67};
        int first=notes.get(0).midi%12;
        int second=-1;
        for(Note n:notes){
            if(n.midi%12!=first){second=n.midi%12;break;}
        }
        if(second<0)second=(first+7)%12;
        return new int[]{60+first,60+second};
    }
    static boolean childOwns(int midi,int[] teachingPitches){
        int pitchClass=midi%12;
        return pitchClass==teachingPitches[0]%12 ||
            pitchClass==teachingPitches[1]%12;
    }
    private GoldenMidi(){}
}
