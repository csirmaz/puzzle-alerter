/*
 * Puzzle Alerter -- the foreground service: poll loop, overlay, state.
 * Copyright (c) 2026 Elod Pal Csirmaz. MIT licence; see LICENSE.
 */

package com.epcsirmaz.puzzlealerter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

/*
 * The heart of the app. A START_STICKY foreground service that:
 *   - re-reads durable state (poll URL, last_dismissed_id) on create, so a respawn
 *     after a process kill neither re-prompts nor re-shows a solved page (6.3);
 *   - keeps the sentinel overlay alive at all times (4.1);
 *   - runs the screen-gated, Wi-Fi-gated poll loop (5) via Poller;
 *   - expands the overlay on a fresh poll result and collapses it on the WebView's
 *     bridge event -- two independent triggers (6.1).
 *
 * Show/dismiss state machine (plan section 6):
 *   expand  iff  result != NONE && result != last_dismissed_id && nothing shown
 *   dismiss iff  the page calls the bridge, OR the screen stays off past the timeout
 *               (plan section 6.6) -- never the poll
 * On dismiss, last_dismissed_id := the shown id, persisted immediately.
 *
 * Accepted residual gap: a user-initiated *Force stop* defeats START_STICKY and
 * is not auto-recovered here (plan section 3 / 12.2).
 */
public class PollService extends Service
    implements Poller.Listener, OverlayController.PuzzleListener, ScreenStateReceiver.ScreenListener {

    public static final String TAG = "PuzzleAlerter";

    /* Intent action from ConfigActivity's "Poll now" button: fire one immediate
       poll and surface the result (see on_manual_poll_result). */
    public static final String ACTION_POLL_NOW = "com.epcsirmaz.puzzlealerter.action.POLL_NOW";

    private static final String NONE = "NONE";          // poll "do nothing" sentinel value
    private static final String CHANNEL_ID = "puzzle_alerter_status";
    private static final int NOTIF_ID = 1;

    private Handler main_handler;
    private Prefs prefs;
    private OverlayController overlay;
    private Speaker speaker;
    private Poller poller;
    private ScreenStateReceiver screen_receiver;

    private String poll_url;
    private String fallback_poll_url;   // optional failover URL (Poller does the failover)
    private String last_dismissed_id;
    private String currently_shown_id;  // in-memory only; null == nothing shown
    private boolean go_home_on_trigger;  // re-read from Prefs; see go_home()
    private long screen_off_at;          // elapsedRealtime() at last screen-off; 0 == screen on / unset
    private long screen_off_timeout_ms;  // from Prefs (minutes * 60_000); 0 == auto-dismiss disabled

    // -------------------------- Lifecycle --------------------------------

    @Override
    public void onCreate(){
        super.onCreate();
        main_handler = new Handler(Looper.getMainLooper());
        prefs = new Prefs(this);

        // Re-read durable state up front (plan section 6.3).
        poll_url = prefs.get_poll_url();
        fallback_poll_url = prefs.get_fallback_poll_url();
        last_dismissed_id = prefs.get_last_dismissed_id();
        go_home_on_trigger = prefs.get_go_home_on_trigger();
        screen_off_timeout_ms = prefs.get_screen_off_timeout_min() * 60_000L;

        start_foreground();

        // Bind the TTS engine up front so it is ready before any puzzle appears;
        // pages drive it through the bridge in lieu of window.speechSynthesis.
        speaker = new Speaker(this);

        overlay = new OverlayController(this, main_handler, this, speaker);
        overlay.add_sentinel();

        poller = new Poller(this, main_handler, this);
        poller.set_poll_url(poll_url);
        poller.set_fallback_url(fallback_poll_url);

        register_screen_receiver();

        // Only poll while the screen is on; match the current state at startup.
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if(pm != null && pm.isInteractive()){ poller.start(); }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int start_id){
        // ConfigActivity may have just saved a new URL; pick it up.
        refresh_config();
        // Keep the recovery alarm armed while we are alive, so it re-asserts the
        // service after a refused boot-start or a process kill (plan section 12.2).
        if(poll_url != null){ RecoveryAlarm.schedule(this); }
        // Manual "Poll now" from the config screen: a user-initiated one-shot that
        // bypasses the loop's screen/Wi-Fi gating (intent is null on a START_STICKY
        // respawn, so null-check before reading the action).
        if(intent != null && ACTION_POLL_NOW.equals(intent.getAction()) && poller != null){
            poller.poll_now();
        }
        // START_STICKY: on a process kill the OS recreates the service, which
        // re-adds the sentinel and reloads state. This recreation latency is the
        // ~5-10 s reappearance window (plan section 3).
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent){ return null; } // not a bound service

    @Override
    public void onTaskRemoved(Intent root_intent){
        // Swiping the app from recents must NOT stop the lock: the foreground
        // service and its overlay survive task removal (plan section 3).
        Log.i(TAG, "Task removed; service and overlay remain");
        super.onTaskRemoved(root_intent);
    }

    @Override
    public void onDestroy(){
        if(poller != null){ poller.stop(); }
        if(screen_receiver != null){
            try {
                unregisterReceiver(screen_receiver);
            }
            catch(Exception e){
                // Not registered; nothing to do.
            }
        }
        if(overlay != null){ overlay.teardown(); }
        if(speaker != null){ speaker.shutdown(); }
        super.onDestroy();
    }

    private void refresh_config(){
        poll_url = prefs.get_poll_url();
        // The fallback URL, the "send app to background" toggle and the dismiss timeout may
        // have just changed in ConfigActivity, which restarts the service to land here; pick
        // them up for the next poll / trigger / screen-off stretch.
        fallback_poll_url = prefs.get_fallback_poll_url();
        go_home_on_trigger = prefs.get_go_home_on_trigger();
        screen_off_timeout_ms = prefs.get_screen_off_timeout_min() * 60_000L;
        if(poller == null){ return; }
        poller.set_poll_url(poll_url);
        poller.set_fallback_url(fallback_poll_url);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if(pm != null && pm.isInteractive() && poll_url != null){ poller.start(); }
    }

    // -------------------------- Poll result handling ---------------------

    @Override
    public void on_poll_result(String result){
        // Runs on the main thread (Poller guarantees it).
        if(result == null){ return; }                   // network failure, already logged
        if(result.length() == 0){ return; }
        if(NONE.equals(result)){ return; }               // explicit do-nothing; never collapses (6.1)
        if(result.equals(last_dismissed_id)){ return; }  // de-dup: already solved this id (6.2)
        if(currently_shown_id != null){ return; }        // a puzzle is up; do not interrupt it
        if(!result.startsWith("http://") && !result.startsWith("https://")){ Log.e(TAG, "Refusing puzzle URL with unsupported scheme"); return; }

        // The id is the returned URL itself.
        expand_overlay(result);
    }

    @Override
    public void on_manual_poll_result(String result){
        // Runs on the main thread (Poller guarantees it). The manual "Poll now"
        // button is a deliberate one-shot, so unlike on_poll_result it ignores the
        // de-dup against last_dismissed_id and force-shows a usable URL. Anything
        // that is not a usable URL (NONE, a status string, an empty body, a network
        // failure) is surfaced as text so the user can see what the server returned.
        if(result == null){ toast("Poll failed -- no response"); return; }
        boolean usable = result.length() > 0 && !NONE.equals(result)
            && (result.startsWith("http://") || result.startsWith("https://"));
        if(usable){
            if(currently_shown_id != null){ toast("A puzzle is already showing"); return; }
            expand_overlay(result);
            return;
        }
        toast(result.length() > 0 ? result : "Empty response");
    }

    @Override
    public void on_puzzle_solved(){
        // Runs on the main thread (WebBridge hops here). The page-driven collapse path;
        // the poll never collapses the overlay (plan section 6.1).
        dismiss_current();
    }

    // -------------------------- Screen gating ----------------------------

    @Override
    public void on_screen_on(){
        // A puzzle that has sat behind a dark screen past the configured timeout is
        // dropped as if solved (lazy timeout, plan section 6.6): the lock only meant to
        // interrupt active use, and a long screen-off means the user walked away. The
        // timeout is user-set (Prefs, minutes); 0 disables the feature. We check on
        // screen-on rather than via a Handler -- a postDelayed runs on uptimeMillis,
        // which stalls during Doze, so it would not measure wall-clock screen-off time;
        // elapsedRealtime (used below) does count sleep.
        if(screen_off_timeout_ms > 0 && currently_shown_id != null && screen_off_at != 0
            && SystemClock.elapsedRealtime() - screen_off_at > screen_off_timeout_ms){
            dismiss_current();
        }
        screen_off_at = 0;
        if(poller != null){ poller.start(); }
    }

    @Override
    public void on_screen_off(){
        // Stamp when the screen went dark so on_screen_on can measure how long a still
        // showing puzzle was ignored. A puzzle can only appear while the screen is on
        // (polling is screen-gated), so this never overwrites a meaningful stamp.
        screen_off_at = SystemClock.elapsedRealtime();
        if(poller != null){ poller.stop(); }
    }

    private void register_screen_receiver(){
        screen_receiver = new ScreenStateReceiver(this);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        // Protected system broadcasts; the receiver is internal, so NOT_EXPORTED.
        registerReceiver(screen_receiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    // -------------------------- Helpers ----------------------------------

    /* The single expand path shared by the loop and the manual one-shot. Callers have
       already done all the gating (de-dup, "nothing shown", scheme check); this just
       records the id, optionally evicts the foreground app, and raises the overlay.
       Main thread only. */
    private void expand_overlay(String id){
        currently_shown_id = id;
        if(go_home_on_trigger){ go_home(); }
        overlay.show_puzzle(id);
    }

    /* The single collapse path: record the shown id as dismissed (so the next poll does
       not immediately re-show it -- de-dup, plan section 6.2), then drop the overlay.
       Shared by the page's bridge solve and the screen-off timeout (plan section 6.6).
       No-op if nothing is shown. Main thread only. */
    private void dismiss_current(){
        if(currently_shown_id == null){ return; }
        last_dismissed_id = currently_shown_id;
        prefs.set_last_dismissed_id(last_dismissed_id);  // persist before we forget it
        currently_shown_id = null;
        overlay.dismiss();
    }

    /* One-shot "send the current app to the background" at trigger time (opt-in via
       Prefs). The overlay only *covers* the foreground app, so e.g. a video keeps
       playing underneath; going Home gives that app onStop, which makes most players
       pause. This is an Activity launch from the background, permitted because we hold
       SYSTEM_ALERT_WINDOW with a live sentinel -- the same BAL exemption section 4.1
       relies on to (re)start the service. It is NOT load-bearing for the lock: if the
       launch is ever refused the overlay still covers the screen, we just lose the
       playback-stop. It also backgrounds only the single foreground task; audio from a
       background media service is unaffected. */
    private void go_home(){
        Intent home = new Intent(Intent.ACTION_MAIN);
        home.addCategory(Intent.CATEGORY_HOME);
        home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(home);
        }
        catch(Exception e){
            // Refused (e.g. a future BAL tightening); the overlay still locks the screen.
            Log.w(TAG, "Home launch refused; overlay still covers the screen", e);
        }
    }

    /* Surface a short message to the user. Used by the manual poll to report a
       non-URL response or a failure; call on the main thread. */
    private void toast(String msg){ Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); }

    // -------------------------- Foreground notification ------------------

    private void start_foreground(){
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel channel = new NotificationChannel(
            CHANNEL_ID, "Puzzle Alerter status", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Keeps the puzzle poller running.");
        nm.createNotificationChannel(channel);

        Notification notif = new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Puzzle Alerter")
            .setContentText("Watching for puzzles")
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setOngoing(true)
            .build();

        // specialUse: no built-in FGS type fits "poll a URL" (plan section 4).
        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    }

}
