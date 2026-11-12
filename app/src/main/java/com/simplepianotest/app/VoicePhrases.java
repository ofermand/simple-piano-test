package com.simplepianotest.app;

/**
 * Maps the existing gameplay instructions to pre-rendered Hebrew speech clips.
 * Only short fixed phrases are used, so the app needs no network access.
 */
final class VoicePhrases {
    private VoicePhrases(){}

    private static String note(String spoken){
        String[] names={"סול","דו","רה","מי","פה","לה"};
        String[] ids={"sol","do","re","mi","fa","la"};
        for(int i=0;i<names.length;i++){
            if(spoken.contains(" "+names[i]+" ") ||
                spoken.contains(" "+names[i]+".") ||
                spoken.endsWith(" "+names[i]) ||
                spoken.contains(" "+names[i]+","))return ids[i];
        }
        return null;
    }

    static String clipFor(String spoken){
        if(spoken==null)return null;
        if(spoken.startsWith("ברוכים הבאים"))return "welcome";
        if(spoken.startsWith("מוכנים לנגן"))return "welcome_resume";
        if(spoken.startsWith("עכשיו נלמד את סול"))return "lesson_sol";
        String n=note(spoken);
        if(spoken.startsWith("כל הכבוד! הרווחתם תו חדש"))return n==null?null:"unlock_"+n;
        if(spoken.startsWith("הסתכלו על החמשה") ||
            spoken.startsWith("מצאו את"))return n==null?null:"lesson_"+n;
        if(spoken.startsWith("יופי! זה"))return "lesson_success";
        if(spoken.startsWith("בואו נתרגל"))return "practice_start";
        if(spoken.startsWith("מצוין! עכשיו"))return n==null?null:"next_"+n;
        if(spoken.startsWith("כמעט. חפשו"))return "try_again";
        if(spoken.startsWith("כל הכבוד! סיימתם לתרגל"))return "practice_done";
        if(spoken.equals("מעולה!"))return "song_cheer";
        if(spoken.startsWith("הקשיבו לספירה"))return "song_start";
        if(spoken.startsWith("הקשיבו למוזיקה"))return "song_intro";
        if(spoken.startsWith("איזה יופי! סיימתם את כל השיר"))return "song_end_next";
        if(spoken.startsWith("אלופים! למדתם"))return "song_end_master";
        if(spoken.startsWith("כל הכבוד! אפשר לנגן שוב"))return "result_options";
        return null;
    }
}
