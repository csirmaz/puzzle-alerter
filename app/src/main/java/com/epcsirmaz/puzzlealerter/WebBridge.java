/*
 * Puzzle Alerter -- the WebView -> native JavaScript bridge.
 * Copyright (c) 2026 Elod Pal Csirmaz. MIT licence; see LICENSE.
 */

package com.epcsirmaz.puzzlealerter;

import android.os.Handler;
import android.webkit.JavascriptInterface;

/*
 * The single object exposed to page JavaScript via addJavascriptInterface. The
 * page calls window.PuzzleAlerter.solved() when its task is complete.
 *
 * Trust model (plan section 6.2): we take the page's word that the puzzle is
 * solved, because the user is assumed unable to forge a bridge call. No
 * server-side check and no waiting for an authoritative poll.
 *
 * Threading (plan section 7): @JavascriptInterface methods are invoked on a
 * binder thread, so we hop to the main thread before any overlay mutation.
 */
public class WebBridge {

    private final Handler main_handler;
    private final OverlayController.PuzzleListener listener;

    public WebBridge(Handler main_handler, OverlayController.PuzzleListener listener){
        this.main_handler = main_handler;
        this.listener = listener;
    }

    /* Called by the page when the user has solved the task. Off the binder
       thread, onto main, then dismiss. This is the ONLY dismissal trigger. */
    @JavascriptInterface
    public void solved(){
        main_handler.post(new Runnable(){
            public void run(){ listener.on_puzzle_solved(); }
        });
    }

}
