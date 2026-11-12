package com.simplepianotest.app;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Production-like content loading: the child is never asked to browse for
 * MIDI or audio. A build is Golden-ready only when both files are included.
 *
 * app/src/main/assets/golden_melody.mid
 * app/src/main/assets/golden_instrumental.mp3
 *
 * Optional, separately prepared:
 * app/src/main/assets/golden_guide_level_1.wav etc.
 */
final class GoldenPack {
    static final String MIDI="golden_melody.mid";
    static final String AUDIO="golden_instrumental.mp3";

    static boolean isInstalled(Context context){
        try(InputStream midi=context.getAssets().open(MIDI);
            InputStream audio=context.getAssets().open(AUDIO)){
            return midi.read()!=-1 && audio.read()!=-1;
        }catch(IOException missing){
            return false;
        }
    }

    static GoldenMidi.Song loadNotes(Context context)throws IOException {
        try(InputStream stream=context.getAssets().open(MIDI)) {
            return GoldenMidi.parse(stream);
        }
    }

    static Uri extractInstrumental(Context context)throws IOException {
        File output=new File(context.getFilesDir(),AUDIO);
        if(!output.exists() || output.length()<1024)
            copy(context,AUDIO,output);
        return Uri.fromFile(output);
    }

    static Uri extractGuide(Context context,int level)throws IOException {
        String name="golden_guide_level_"+(level+1)+".wav";
        try(InputStream check=context.getAssets().open(name)){
            File target=new File(context.getFilesDir(),name);
            if(!target.exists()||target.length()<1024)copy(context,name,target);
            return Uri.fromFile(target);
        }catch(IOException notIncluded){return null;}
    }

    private static void copy(Context context,String asset,File file)throws IOException{
        try(InputStream in=context.getAssets().open(asset);
            FileOutputStream out=new FileOutputStream(file)){
            byte[] buffer=new byte[8192];
            int bytes;
            while((bytes=in.read(buffer))!=-1)out.write(buffer,0,bytes);
        }
    }
    private GoldenPack(){}
}
