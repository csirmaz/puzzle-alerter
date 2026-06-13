/*
 * Puzzle Alerter -- native text-to-speech for the overlay page.
 * Copyright (c) 2026 Elod Pal Csirmaz. MIT licence; see LICENSE.
 */

package com.epcsirmaz.puzzlealerter;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

/*
 * Wraps the Android TextToSpeech engine so the overlay page can speak text.
 *
 * Why this exists: the Web Speech API (window.speechSynthesis) is a feature of
 * Chrome the browser, not of the Android WebView component -- in a WebView the
 * object is present but getVoices() is empty and speak() is silent. So pages
 * route TTS through the native bridge (WebBridge.speak) into this class instead.
 *
 * Lifecycle: the engine is bound for the whole service lifetime (created in
 * PollService.onCreate, released in onDestroy) rather than per-puzzle, because
 * binding the engine is asynchronous -- onInit can land well after construction,
 * and a per-puzzle engine would routinely miss a speak() fired as the page
 * loads. Bound once up front, it is ready long before any puzzle appears.
 *
 * Threading: construct on, and call speak()/stop() from, the main thread (the
 * bridge hops there first). onInit is delivered on the main thread too, so the
 * ready/pending fields need no synchronisation.
 */
public class Speaker {

    private final TextToSpeech tts;
    private boolean ready = false;     // set true once onInit reports SUCCESS
    private String pending = null;     // text requested before the engine was ready

    public Speaker(Context context){
        // onInit fires (on the main thread) when the engine has bound, or with an
        // error if no engine is available -- see the TTS_SERVICE <queries> entry in
        // the manifest, without which API 30+ cannot find the default engine.
        tts = new TextToSpeech(context, new TextToSpeech.OnInitListener(){
            public void onInit(int status){
                if(status != TextToSpeech.SUCCESS){ Log.e(PollService.TAG, "TextToSpeech init failed: " + status); return; }
                // UK English to match the app's spelling; LANG_MISSING_DATA / not-supported
                // is non-fatal, the engine simply falls back to its default voice.
                int lang = tts.setLanguage(Locale.UK);
                if(lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED){ Log.w(PollService.TAG, "UK English TTS unavailable; using engine default"); }
                ready = true;
                // Speak anything that arrived during init, then clear it.
                if(pending != null){ speak_now(pending); pending = null; }
            }
        });
    }

    /* Speak text aloud, interrupting anything currently being spoken. Safe to
       call before the engine has finished binding: the text is held and spoken
       once onInit completes. */
    public void speak(String text){
        if(text == null || text.length() == 0){ return; }
        if(!ready){ pending = text; return; }  // not bound yet; speak on init
        speak_now(text);
    }

    private void speak_now(String text){
        // QUEUE_FLUSH: each alert replaces the previous utterance rather than queueing.
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "puzzle_alerter");
    }

    /* Halt any in-progress speech without releasing the engine (used when the
       overlay is dismissed). */
    public void stop(){
        if(ready){ tts.stop(); }
    }

    /* Release the engine binding. The Speaker is unusable afterwards. */
    public void shutdown(){
        tts.stop();
        tts.shutdown();
    }

}
