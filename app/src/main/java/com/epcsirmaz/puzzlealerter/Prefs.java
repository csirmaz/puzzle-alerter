/*
 * Puzzle Alerter -- durable configuration and de-duplication state.
 * Copyright (c) 2026 Elod Pal Csirmaz. MIT licence; see LICENSE.
 */

package com.epcsirmaz.puzzlealerter;

import android.content.Context;
import android.content.SharedPreferences;

/*
 * Thin wrapper over SharedPreferences. Two pieces of state MUST survive a
 * START_STICKY respawn (plan section 6.3): the configured poll URL, and the
 * last_dismissed_id that stops an already-solved puzzle re-appearing. Without
 * persistence a respawn after a process kill would either re-prompt for the URL
 * or re-show a solved page.
 */
public class Prefs {

    private static final String FILE = "puzzle_alerter_prefs";
    private static final String KEY_POLL_URL = "poll_url";
    private static final String KEY_FALLBACK_POLL_URL = "fallback_poll_url";
    private static final String KEY_LAST_DISMISSED_ID = "last_dismissed_id";
    private static final String KEY_GO_HOME_ON_TRIGGER = "go_home_on_trigger";
    private static final String KEY_SCREEN_OFF_TIMEOUT_MIN = "screen_off_timeout_min";

    public static final int DEFAULT_SCREEN_OFF_TIMEOUT_MIN = 20;

    private final SharedPreferences sp;

    public Prefs(Context context){
        // Application context so the store outlives any single component.
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String get_poll_url(){ return sp.getString(KEY_POLL_URL, null); }

    public void set_poll_url(String url){ sp.edit().putString(KEY_POLL_URL, url).apply(); }

    /* Optional second poll URL used as a failover: when a poll of the primary URL errors
       (network failure, non-200, timeout) and a fallback is configured, the very next
       minute polls the fallback instead, then returns to the primary -- a single detour
       regardless of the fallback's own result (the loop still does just one poll a minute).
       null/absent means "no fallback configured". */
    public String get_fallback_poll_url(){ return sp.getString(KEY_FALLBACK_POLL_URL, null); }

    /* Store null (or an empty string) to clear the fallback; ConfigActivity passes null
       for a blank field so get_fallback_poll_url reports "none" cleanly. */
    public void set_fallback_poll_url(String url){ sp.edit().putString(KEY_FALLBACK_POLL_URL, url).apply(); }

    public String get_last_dismissed_id(){ return sp.getString(KEY_LAST_DISMISSED_ID, null); }

    public void set_last_dismissed_id(String id){ sp.edit().putString(KEY_LAST_DISMISSED_ID, id).apply(); }

    /* Optional behaviour: when a puzzle is shown, also send the currently-foreground
       app to the background (a one-shot Home launch) so a media player stops rather
       than just being covered by the overlay. Off by default -- the overlay alone is
       the locking mechanism; this is an extra eviction the user opts into. */
    public boolean get_go_home_on_trigger(){ return sp.getBoolean(KEY_GO_HOME_ON_TRIGGER, false); }

    public void set_go_home_on_trigger(boolean on){ sp.edit().putBoolean(KEY_GO_HOME_ON_TRIGGER, on).apply(); }

    /* Minutes a triggered puzzle may sit behind an off screen before it is auto-dismissed
       on the next screen-on (plan section 6.6). 0 disables the feature; default 20. */
    public int get_screen_off_timeout_min(){ return sp.getInt(KEY_SCREEN_OFF_TIMEOUT_MIN, DEFAULT_SCREEN_OFF_TIMEOUT_MIN); }

    public void set_screen_off_timeout_min(int minutes){ sp.edit().putInt(KEY_SCREEN_OFF_TIMEOUT_MIN, minutes).apply(); }

}
