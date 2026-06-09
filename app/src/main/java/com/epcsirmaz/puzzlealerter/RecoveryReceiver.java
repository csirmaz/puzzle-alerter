/*
 * Puzzle Alerter -- receives the recovery alarm and re-asserts the service.
 * Copyright (c) 2026 Elod Pal Csirmaz. MIT licence; see LICENSE.
 */

package com.epcsirmaz.puzzlealerter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/*
 * Fired by RecoveryAlarm. Starts the service (if a URL is configured) and chains
 * the next check. Starting an FGS from here is allowed because the alarm is exact
 * (see RecoveryAlarm). The explicit intent targets our own component, so the
 * receiver is not exported.
 */
public class RecoveryReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent){
        if(!RecoveryAlarm.ACTION_TICK.equals(intent.getAction())){ return; }
        if(new Prefs(context).get_poll_url() == null){ return; } // nothing to recover to; let the chain lapse
        try {
            context.startForegroundService(new Intent(context, PollService.class));
        }
        catch(Exception e){
            Log.w(PollService.TAG, "Recovery start failed: " + e.getMessage());
        }
        // Chain the next check regardless of the start succeeding, so recovery keeps trying.
        RecoveryAlarm.schedule(context);
    }

}
