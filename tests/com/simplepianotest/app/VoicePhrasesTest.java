package com.simplepianotest.app;

public final class VoicePhrasesTest {
    private static void eq(String actual,String expected){
        if(!expected.equals(actual))
            throw new AssertionError("Expected "+expected+" but mapped "+actual);
    }
    public static void main(String[] ignored){
        eq(VoicePhrases.clipFor("ברוכים הבאים! נלמד כמה תווים"),"welcome");
        eq(VoicePhrases.clipFor("מוכנים לנגן? לחצו להתחלה!"),"welcome_resume");
        eq(VoicePhrases.clipFor("עכשיו נלמד את סול. הוא על הקו השני"),"lesson_sol");
        eq(VoicePhrases.clipFor("הסתכלו על החמשה ועל הפסנתר הקטן. מצאו את דו ונגנו"),"lesson_do");
        eq(VoicePhrases.clipFor("מצאו את פה בחמשה ובפסנתר, ונגנו אותו."),"lesson_fa");
        eq(VoicePhrases.clipFor("כל הכבוד! הרווחתם תו חדש: לה. הסתכלו"),"unlock_la");
        eq(VoicePhrases.clipFor("כל הכבוד! הרווחתם תו חדש: רה. הסתכלו"),"unlock_re");
        eq(VoicePhrases.clipFor("יופי! זה סול. לחצו על המשך."),"lesson_success");
        eq(VoicePhrases.clipFor("מצוין! עכשיו מי."),"next_mi");
        eq(VoicePhrases.clipFor("בואו נתרגל. הסתכלו על התו"),"practice_start");
        eq(VoicePhrases.clipFor("כמעט. חפשו את דו בחמשה"),"try_again");
        eq(VoicePhrases.clipFor("כל הכבוד! סיימתם לתרגל."),"practice_done");
        eq(VoicePhrases.clipFor("הקשיבו לספירה. מוכנים? מנגנים!"),"song_start");
        eq(VoicePhrases.clipFor("מעולה!"),"song_cheer");
        eq(VoicePhrases.clipFor("איזה יופי! סיימתם את כל השיר."),"song_end_next");
        eq(VoicePhrases.clipFor("אלופים! למדתם את כל תווי השיר!"),"song_end_master");
        System.out.println("All Hebrew narration events map to the intended offline clips");
    }
}
