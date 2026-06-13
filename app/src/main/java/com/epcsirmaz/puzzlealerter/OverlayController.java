/*
 * Puzzle Alerter -- owns the overlay windows (sentinel + active WebView).
 * Copyright (c) 2026 Elod Pal Csirmaz. MIT licence; see LICENSE.
 */

package com.epcsirmaz.puzzlealerter;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/*
 * All WindowManager view work lives here. The overlay has two states (plan
 * section 2):
 *   - sentinel : a persistent ~1x1, effectively invisible window, always added.
 *                Keeping a visible overlay alive at all times preserves the
 *                SAW->FGS background-start exemption under Android 16 (4.1).
 *   - active   : a full-screen window hosting the WebView, added on demand.
 *
 * Every method that touches a view is, or posts to, the main thread -- view
 * mutation off the main thread is illegal and the bridge callbacks arrive on a
 * binder thread.
 */
public class OverlayController {

    /* Implemented by the service; fired (on the main thread) when the page
       reports the puzzle solved. */
    public interface PuzzleListener {
        void on_puzzle_solved();
    }

    private static final String TAG = PollService.TAG;
    private static final String BRIDGE_NAME = "PuzzleAlerter"; // window.PuzzleAlerter in the page

    private final Context context;       // service context (valid for overlay views)
    private final Handler main_handler;
    private final PuzzleListener listener;
    private final Speaker speaker;       // native TTS, shared via the bridge
    private final WindowManager window_manager;

    private View sentinel;               // always present once added
    private FrameLayout active_root;     // non-null only while a puzzle is shown
    private WebView web_view;

    public OverlayController(Context context, Handler main_handler, PuzzleListener listener, Speaker speaker){
        this.context = context;
        this.main_handler = main_handler;
        this.listener = listener;
        this.speaker = speaker;
        this.window_manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
    }

    // -------------------------- Sentinel ---------------------------------

    /* Add the always-present 1x1 sentinel. Not focusable and not touchable, so
       it is invisible to the user yet keeps a live overlay window in existence. */
    public void add_sentinel(){
        if(sentinel != null){ return; }
        sentinel = new View(context);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        try {
            window_manager.addView(sentinel, lp);
        }
        catch(Exception e){
            // Almost always a missing SYSTEM_ALERT_WINDOW grant.
            Log.e(TAG, "Could not add sentinel overlay; is the overlay permission granted?", e);
            sentinel = null;
        }
    }

    // -------------------------- Active view ------------------------------

    /* Expand to a full-screen overlay and load the URL. Safe to call from any
       thread; the view work is posted to main. */
    public void show_puzzle(final String url){
        main_handler.post(new Runnable(){
            public void run(){ show_puzzle_main(url); }
        });
    }

    private void show_puzzle_main(String url){
        if(active_root != null){ return; } // a puzzle is already shown (service guards this too)

        web_view = new WebView(context);
        // JavaScript is enabled ONLY while a page is shown (plan section 7).
        web_view.getSettings().setJavaScriptEnabled(true);
        web_view.getSettings().setDomStorageEnabled(true);
        // Allow the page to start audio (new Audio().play(), Web Audio) without a
        // user gesture. The WebView default is true, which silently drops any
        // playback not begun synchronously inside a tap handler -- so an alert
        // sound played on load or after a timer would never be heard (plan 7.1).
        web_view.getSettings().setMediaPlaybackRequiresUserGesture(false);
        // The page talks back to native through this one bridge object.
        web_view.addJavascriptInterface(new WebBridge(main_handler, listener, speaker), BRIDGE_NAME);
        // Keep navigation to http/https inside the WebView; never hand a URL to the
        // system browser (e.g. tel:, mailto:, intent:, market: links).
        web_view.setWebViewClient(new WebViewClient(){
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request){
                String target = request.getUrl().toString();
                if(target.startsWith("http://") || target.startsWith("https://")){ return false; } // let the WebView load it
                Log.w(TAG, "Blocked navigation to unsupported scheme");
                return true; // swallow it
            }
        });

        active_root = new FrameLayout(context);
        // Grey fills active_root, so the inset strip outside the WebView (the
        // system-bar + cutout padding added below) shows grey rather than white.
        active_root.setBackgroundColor(Color.GRAY);
        active_root.addView(web_view, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        // Swallow the Back key so the soft lock is not trivially dismissed. Home
        // and recents need no handling: an overlay window never leaves the screen
        // on Home press, which is the whole point of the overlay design (plan 2).
        active_root.setFocusableInTouchMode(true);
        active_root.setOnKeyListener(new View.OnKeyListener(){
            public boolean onKey(View v, int key_code, KeyEvent event){
                return key_code == KeyEvent.KEYCODE_BACK; // true == consumed
            }
        });

        // The overlay window stays full-bleed (grey fills the strip behind the
        // bars), but pad active_root in by the system bars + any display cutout so
        // the WebView child is shrunk clear of them -- otherwise the top of the page
        // renders behind the status icons. Padding the FrameLayout, not the WebView,
        // means the WebView itself is laid out smaller rather than scrolled.
        active_root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener(){
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets){
                Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            }
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Focusable (no NOT_FOCUSABLE flag) so the WebView can take input and we
            // receive the Back key; full-bleed over the system bars.
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE);
        lp.gravity = Gravity.TOP | Gravity.START;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P){
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }

        try {
            window_manager.addView(active_root, lp);
            active_root.requestFocus();
            active_root.requestApplyInsets(); // fire the inset listener for the initial layout
            web_view.loadUrl(url);
        }
        catch(Exception e){
            Log.e(TAG, "Could not add active overlay", e);
            teardown_active();
        }
    }

    /* Collapse back to the bare sentinel. Safe to call from any thread. */
    public void dismiss(){
        main_handler.post(new Runnable(){
            public void run(){ teardown_active(); }
        });
    }

    /* Remove and destroy the active overlay. Destroying the WebView frees the
       Chromium renderer rather than leaving it resident (plan section 7). Must
       run on the main thread. */
    private void teardown_active(){
        // Silence any alert the dismissed page was still speaking.
        if(speaker != null){ speaker.stop(); }
        if(web_view != null){
            web_view.getSettings().setJavaScriptEnabled(false);
            web_view.loadUrl("about:blank");
        }
        if(active_root != null){
            try {
                window_manager.removeView(active_root);
            }
            catch(Exception e){
                Log.w(TAG, "active overlay already removed");
            }
        }
        if(web_view != null){
            web_view.destroy();
            web_view = null;
        }
        active_root = null;
    }

    // -------------------------- Lifecycle --------------------------------

    /* Tear everything down on service destroy. */
    public void teardown(){
        teardown_active();
        if(sentinel != null){
            try {
                window_manager.removeView(sentinel);
            }
            catch(Exception e){
                Log.w(TAG, "sentinel already removed");
            }
            sentinel = null;
        }
    }

}
