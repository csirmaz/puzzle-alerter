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
 * Optional auto-start on boot (plan section 9). Only starts the service once a
 * poll URL has been configured.
 *
 * Caveat: starting a foreground service straight from BOOT_COMPLETED can be
 * refused on modern Android because, at boot, no overlay window is yet visible to
 * satisfy the Android 16 SAW->FGS exemption (plan section 4.1). We therefore
 * attempt it but swallow the failure; the user can always reopen the app. A
 * sturdier post-boot / post-force-stop recovery trigger is a deferred decision
 * (plan section 12.2).
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
    }

}
