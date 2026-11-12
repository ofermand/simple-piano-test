#!/usr/bin/env python3
"""Render short, offline Hebrew voice prompts into Android APK assets.

This is a TEST BUILD voice pack only. Before commercial distribution, replace
this generator with a licensed synthesis endpoint and retain the output
under the applicable provider terms.
"""
import asyncio
from pathlib import Path
import sys

OUT = Path("app/src/main/assets/voice_he")
VOICE = "he-IL-HilaNeural"

SCRIPTS = {
    "welcome": "היי! ברוכים הבאים לסימפל פיאנו! היום נלמד לנגן את כוכב קטן. מוכנים? בואו נתחיל!",
    "welcome_resume": "מוכנים? בואו ננגן! לחצו על הכפתור כדי להתחיל.",
    "lesson_success": "יופי! זה בדיוק הצליל הנכון. אפשר להמשיך!",
    "practice_start": "ועכשיו, תרגול קצר! הסתכלו על החמשה, מצאו את הקליד המואר, ונגנו.",
    "practice_done": "איזה יופי! סיימתם את התרגול. עכשיו מנגנים את השיר כולו!",
    "try_again": "כמעט! הביטו בתו ובקליד המואר, ונסו שוב. אני כאן איתכם!",
    "song_start": "זה הזמן לנגן עם הלהקה! הקשיבו לספירה, ומתחילים!",
    "song_intro": "מנגנים יחד עם המוזיקה. התווים הצבעוניים הם התווים שלכם!",
    "song_cheer": "וואו! מעולה!",
    "song_end_next": "איזה כיף! ניגנתם שיר שלם! רוצים לגלות עוד תו? לחצו על הוספת תו!",
    "song_end_master": "איזה אלופים! למדתם את כל התווים של השיר! כל הכבוד!",
    "result_options": "אפשר לנסות שוב את השיר, או להוסיף תו חדש. מה בא לכם?",
}
NAMES = {"do":"דו", "re":"רה", "mi":"מי",
         "fa":"פה", "sol":"סול", "la":"לה"}
for key, name in NAMES.items():
    SCRIPTS[f"lesson_{key}"] = (
        f"בואו נכיר את התו {name}. הסתכלו איפה הוא נמצא בחמשה, "
        f"ומצאו את הקליד המואר. עכשיו, נגנו {name}!"
    )
    SCRIPTS[f"next_{key}"] = f"כל הכבוד! ועכשיו, נגנו {name}!"
for key in ("la", "fa", "mi", "re"):
    name = NAMES[key]
    SCRIPTS[f"unlock_{key}"] = (
        f"יש! הרווחתם תו חדש! זה התו {name}! "
        "הביטו בחמשה ובפסנתר, מצאו אותו, ונגנו!"
    )

async def render_edge(key, text):
    import edge_tts
    target = OUT / f"{key}.mp3"
    for attempt in range(3):
        try:
            speaker = edge_tts.Communicate(
                text, VOICE, rate="-4%", pitch="+3Hz")
            await asyncio.wait_for(speaker.save(str(target)), timeout=40)
            if target.stat().st_size < 2000:
                raise ValueError(f"{key}: audio file is unexpectedly small")
            return
        except Exception as error:
            if attempt == 2:
                raise RuntimeError(f"{key}: neural speech failed: {error}") from error
            await asyncio.sleep(1.2 * (attempt+1))

async def main():
    OUT.mkdir(parents=True, exist_ok=True)
    sem = asyncio.Semaphore(3)
    async def limited(key, txt):
        async with sem:
            await render_edge(key, txt)
            print(f"VOICE {key}: {(OUT / (key+'.mp3')).stat().st_size} bytes", flush=True)
    await asyncio.gather(*(limited(k, t) for k, t in SCRIPTS.items()))
    missing = [k for k in SCRIPTS if not (OUT/f"{k}.mp3").is_file()]
    if missing:
        raise RuntimeError(f"Missing spoken prompts: {missing}")
    print(f"RENDERED {len(SCRIPTS)} offline Hebrew prompts with {VOICE}")

if __name__ == "__main__":
    if "--list" in sys.argv:
        for key in SCRIPTS: print(key)
    else:
        asyncio.run(main())
