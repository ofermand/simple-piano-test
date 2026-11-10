package com.simplepianotest.app;

/**
 * Treble-clef staff coordinates. The lowest line is E4 (MIDI 64).
 * Each diatonic step moves half a staff-line spacing.
 */
final class StaffNoteLayout {
    private StaffNoteLayout() {}

    static float yForMidi(int midi,float bottomLineY,float lineGap) {
        // In this first song, pitches are the natural notes C4 through A4.
        switch(midi) {
            case 60: return bottomLineY + lineGap;       // middle C, ledger line below
            case 62: return bottomLineY + lineGap/2;     // D4 below the staff
            case 64: return bottomLineY;                 // E4 lowest line
            case 65: return bottomLineY - lineGap/2;     // F4 first space
            case 67: return bottomLineY - lineGap;       // G4 second line
            case 69: return bottomLineY - lineGap*1.5f;  // A4 second space
            case 71: return bottomLineY - lineGap*2;     // B4 middle line
            case 72: return bottomLineY - lineGap*2.5f;  // C5 third space
            default: throw new IllegalArgumentException("Unsupported teaching pitch: "+midi);
        }
    }

    static boolean needsLedgerLine(int midi) {return midi==60;}
}
