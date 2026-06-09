/*
 * Puzzle Alerter -- screen on/off receiver, gates the poll loop.
 * Copyright (c) 2026 Elod Pal Csirmaz. MIT licence; see LICENSE.
 */

package com.epcsirmaz.puzzlealerter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/*
 * Toggles the poll loop with the screen. Registered at runtime by PollService:
 * ACTION_SCREEN_ON / ACTION_SCREEN_OFF cannot be declared in the manifest (the
 * system refuses to deliver them to manifest-registered receivers since API 26).
 */
public class ScreenStateReceiver extends BroadcastReceiver {

    public interface ScreenListener {
        void on_screen_on();
        void on_screen_off();
    }

    private final ScreenListener listener;

    public ScreenStateReceiver(ScreenListener listener){
        this.listener = listener;
    }

    @Override
    public void onReceive(Context context, Intent intent){
        String action = intent.getAction();
        if(Intent.ACTION_SCREEN_ON.equals(action)){ listener.on_screen_on(); }
        else if(Intent.ACTION_SCREEN_OFF.equals(action)){ listener.on_screen_off(); }
    }

}
