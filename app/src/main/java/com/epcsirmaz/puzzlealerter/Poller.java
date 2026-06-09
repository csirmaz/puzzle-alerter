/*
 * Puzzle Alerter -- the in-service poll loop.
 * Copyright (c) 2026 Elod Pal Csirmaz. MIT licence; see LICENSE.
 */

package com.epcsirmaz.puzzlealerter;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/*
 * Runs the ~60 s poll loop on an in-service timer (plan section 5). A main-thread
 * Handler drives the cadence; each network GET runs on a single background worker
 * and its result is posted back to main. Deliberately NOT WorkManager/JobScheduler
 * (15-minute floor, Android 16 job quotas) and NO wakelock.
 *
 * The loop is started/stopped with the screen (by the service, via the screen
 * receiver). Each tick additionally gates on Wi-Fi, so we never poll over
 * cellular -- off Wi-Fi the request is skipped entirely.
 */
public class Poller {

    /* Implemented by the service. Always invoked on the main thread.
       result == null means the fetch failed (already logged). */
    public interface Listener {
        void on_poll_result(String result);
    }

    private static final String TAG = PollService.TAG;
    private static final long POLL_INTERVAL_MS = 60_000L;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;

    private final Context context;
    private final Handler main_handler;
    private final Listener listener;
    private final ConnectivityManager connectivity;
    private final ExecutorService network = Executors.newSingleThreadExecutor();

    private volatile String poll_url;    // read on the worker thread, set from main
    private boolean running = false;     // main-thread only

    private final Runnable tick = new Runnable(){
        public void run(){ do_tick(); }
    };

    public Poller(Context context, Handler main_handler, Listener listener){
        this.context = context.getApplicationContext();
        this.main_handler = main_handler;
        this.listener = listener;
        this.connectivity =
            (ConnectivityManager) this.context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public void set_poll_url(String url){ poll_url = url; }

    /* Start the loop. Idempotent; call on the main thread. */
    public void start(){
        if(running){ return; }
        if(poll_url == null){ Log.w(TAG, "No poll URL configured; not starting loop"); return; }
        running = true;
        main_handler.removeCallbacks(tick); // collapse any stray pending tick to one
        main_handler.post(tick);            // fire the first tick promptly
    }

    /* Stop the loop. Idempotent; call on the main thread. An in-flight fetch is
       allowed to finish, but its result is dropped because running == false. */
    public void stop(){
        running = false;
        main_handler.removeCallbacks(tick);
    }

    private void schedule_next(){
        if(!running){ return; }
        // Keep exactly one tick pending, so a quick screen off/on (or a late
        // in-flight completion) cannot leave two loops running in parallel.
        main_handler.removeCallbacks(tick);
        main_handler.postDelayed(tick, POLL_INTERVAL_MS);
    }

    private void do_tick(){
        if(!running){ return; }
        if(poll_url == null){ schedule_next(); return; }
        if(!wifi_connected()){
            // Off Wi-Fi: skip the request, try again next interval (plan section 5).
            schedule_next();
            return;
        }
        final String url = poll_url;
        network.execute(new Runnable(){
            public void run(){
                final String result = fetch(url);
                main_handler.post(new Runnable(){
                    public void run(){
                        if(running){ listener.on_poll_result(result); }
                        schedule_next();   // chain the next tick only after this one settles
                    }
                });
            }
        });
    }

    /* True only when the active network is Wi-Fi with internet capability. */
    private boolean wifi_connected(){
        if(connectivity == null){ return false; }
        Network net = connectivity.getActiveNetwork();
        if(net == null){ return false; }
        NetworkCapabilities caps = connectivity.getNetworkCapabilities(net);
        if(caps == null){ return false; }
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /* Blocking GET on the worker thread. Returns the trimmed body, or null on any
       failure. We do not call disconnect(), so the socket/TLS session returns to
       the keep-alive pool and successive polls reuse it (plan section 8). */
    private String fetch(String url){
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "text/plain");
            int code = conn.getResponseCode();
            if(code != HttpURLConnection.HTTP_OK){ Log.w(TAG, "Poll returned HTTP " + code); return null; }
            InputStream in = conn.getInputStream();
            String body = read_all(in);
            in.close();
            return body.trim();
        }
        catch(Exception e){
            Log.w(TAG, "Poll request failed: " + e.getMessage());
            return null;
        }
    }

    private static String read_all(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while((n = in.read(buf)) != -1){ out.write(buf, 0, n); }
        return out.toString("UTF-8");
    }

}
