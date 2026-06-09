/*
 * Puzzle Alerter -- post-boot / periodic re-assertion alarm.
 * Copyright (c) 2026 Elod Pal Csirmaz. MIT licence; see LICENSE.
 */

package com.epcsirmaz.puzzlealerter;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

/*
 * A self-chaining exact alarm that periodically makes sure the service is alive,
 * as a backstop to START_STICKY (plan sections 3 / 12.2). It mainly covers two
 * cases: an OS build that refuses the foreground-service start from BOOT_COMPLETED,
 * and general re-assertion if the process is gone.
 *
 * It does NOT recover from a user *Force stop*: that cancels the app's alarms and
 * holds the app in the stopped state until it is launched by hand. Force stop
 * therefore stays the accepted residual gap (plan section 3).
 *
 * Why exact: only setExact / setExactAndAllowWhileIdle / setAlarmClock alarms earn
 * the exemption that lets the alarm start a foreground service from the background
 * (hence the USE_EXACT_ALARM permission). Why a non-wakeup type: recovery is
 * pointless while the device sleeps (polling needs the screen on anyway), so we add
 * no standby wakeups -- the alarm simply fires the next time the device is awake,
 * which is exactly when the app matters (plan section 8 battery hygiene).
 */
public class RecoveryAlarm {

    public static final String ACTION_TICK = "com.epcsirmaz.puzzlealerter.action.RECOVERY_TICK";

    private static final long INTERVAL_MS = 15 * 60 * 1000L; // 15 min

    private RecoveryAlarm(){ } // static helper only

    /* Schedule (or reschedule) the next recovery check. Idempotent: the
       PendingIntent is reused, so there is only ever one pending alarm. */
    public static void schedule(Context context){
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if(am == null){ return; }
        long trigger = SystemClock.elapsedRealtime() + INTERVAL_MS;
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, trigger, build_pi(context));
        }
        catch(Exception e){
            // SecurityException if the exact-alarm permission is missing; just log.
            Log.w(PollService.TAG, "Could not schedule recovery alarm: " + e.getMessage());
        }
    }

    private static PendingIntent build_pi(Context context){
        Intent intent = new Intent(context, RecoveryReceiver.class);
        intent.setAction(ACTION_TICK);
        return PendingIntent.getBroadcast(context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

}
