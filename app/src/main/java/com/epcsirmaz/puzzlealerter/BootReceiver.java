/*
 * Puzzle Alerter -- optional auto-start on boot.
 * Copyright (c) 2026 Elod Pal Csirmaz. MIT licence; see LICENSE.
 */

package com.epcsirmaz.puzzlealerter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/*
 * Auto-start on boot (plan section 9). Only starts the service once a poll URL has
 * been configured.
 *
 * Receiving BOOT_COMPLETED is itself a documented exemption from the background
 * foreground-service-start restriction, so the specialUse service normally starts
 * fine here -- this does NOT lean on the (now narrowed) SAW->FGS exemption, which
 * would be unavailable at boot anyway since no overlay exists yet. We still guard
 * the call: a few OEM builds restrict specialUse from boot, and on failure the
 * recovery alarm retries the next time the device is awake (RecoveryAlarm).
 *
 * Note: BOOT_COMPLETED is not delivered to an app in the stopped state, so the app
 * must have been launched at least once after install (and not since Force-stopped)
 * for any of this to run.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent){
        if(!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())){ return; }
        if(new Prefs(context).get_poll_url() == null){ return; } // nothing configured yet
        try {
            context.startForegroundService(new Intent(context, PollService.class));
        }
        catch(Exception e){
            Log.w(PollService.TAG, "Could not start service on boot: " + e.getMessage());
        }
        // Arm the recovery alarm even if the direct start was refused, so it retries.
        RecoveryAlarm.schedule(context);
    }

}
