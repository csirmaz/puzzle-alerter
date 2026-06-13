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
    private static final String KEY_LAST_DISMISSED_ID = "last_dismissed_id";
    private static final String KEY_GO_HOME_ON_TRIGGER = "go_home_on_trigger";

    private final SharedPreferences sp;

    public Prefs(Context context){
        // Application context so the store outlives any single component.
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String get_poll_url(){ return sp.getString(KEY_POLL_URL, null); }

    public void set_poll_url(String url){ sp.edit().putString(KEY_POLL_URL, url).apply(); }

    public String get_last_dismissed_id(){ return sp.getString(KEY_LAST_DISMISSED_ID, null); }

    public void set_last_dismissed_id(String id){ sp.edit().putString(KEY_LAST_DISMISSED_ID, id).apply(); }

    /* Optional behaviour: when a puzzle is shown, also send the currently-foreground
       app to the background (a one-shot Home launch) so a media player stops rather
       than just being covered by the overlay. Off by default -- the overlay alone is
       the locking mechanism; this is an extra eviction the user opts into. */
    public boolean get_go_home_on_trigger(){ return sp.getBoolean(KEY_GO_HOME_ON_TRIGGER, false); }

    public void set_go_home_on_trigger(boolean on){ sp.edit().putBoolean(KEY_GO_HOME_ON_TRIGGER, on).apply(); }

}
