/*
 * Puzzle Alerter -- first-run configuration screen.
 * Copyright (c) 2026 Elod Pal Csirmaz. MIT licence; see LICENSE.
 */

package com.epcsirmaz.puzzlealerter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/*
 * The only ordinary UI. Captures and persists the poll URL, walks the user through
 * the two permissions that cannot be granted silently (overlay + notifications),
 * then starts the foreground service. On a later launch it just shows status; the
 * service itself re-reads the persisted URL on respawn (plan section 11, step 1).
 */
public class ConfigActivity extends Activity {

    private static final int REQ_NOTIFICATIONS = 100;

    private EditText url_input;
    private CheckBox go_home_checkbox;
    private TextView status;

    @Override
    protected void onCreate(Bundle saved){
        super.onCreate(saved);
        setContentView(R.layout.activity_config);

        url_input = findViewById(R.id.url_input);
        go_home_checkbox = findViewById(R.id.go_home_checkbox);
        status = findViewById(R.id.status);
        Button save = findViewById(R.id.save_button);
        Button grant = findViewById(R.id.grant_overlay_button);
        Button poll_now = findViewById(R.id.poll_now_button);

        Prefs prefs = new Prefs(this);
        String existing = prefs.get_poll_url();
        if(existing != null){ url_input.setText(existing); }
        go_home_checkbox.setChecked(prefs.get_go_home_on_trigger());

        save.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){ on_save(); }
        });
        grant.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){ request_overlay_permission(); }
        });
        poll_now.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){ on_poll_now(); }
        });
    }

    @Override
    protected void onResume(){
        super.onResume();
        update_status();
    }

    /* Validate and persist the URL, ensure the notification permission, then start. */
    private void on_save(){
        String url = url_input.getText().toString().trim();
        if(url.length() == 0){ toast("Please enter a poll URL"); return; }
        if(!url.startsWith("http://") && !url.startsWith("https://")){ toast("The poll URL must start with http:// or https://"); return; }

        Prefs prefs = new Prefs(this);
        prefs.set_poll_url(url);
        // Persisted here so the service picks it up on the (re)start below; the service
        // re-reads it in refresh_config (plan section 6.5).
        prefs.set_go_home_on_trigger(go_home_checkbox.isChecked());

        // POST_NOTIFICATIONS backs the foreground-service notification (Android 13+).
        if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, REQ_NOTIFICATIONS);
            return; // continues in onRequestPermissionsResult
        }
        start_service_if_ready();
    }

    /* Manual one-shot poll. Hand the service a POLL_NOW command; it fetches the URL
       right away (no screen/Wi-Fi gating, no de-dup) and either shows the puzzle in
       the overlay or toasts whatever non-URL text came back. We need a URL set, but
       not the overlay grant -- without it the non-URL text path still works and the
       service just logs the failed overlay add for the URL path. */
    private void on_poll_now(){
        if(new Prefs(this).get_poll_url() == null){ toast("Set a poll URL first"); return; }
        Intent intent = new Intent(this, PollService.class);
        intent.setAction(PollService.ACTION_POLL_NOW);
        startForegroundService(intent);
        toast("Polling...");
    }

    @Override
    public void onRequestPermissionsResult(int request_code, String[] permissions, int[] results){
        super.onRequestPermissionsResult(request_code, permissions, results);
        start_service_if_ready();
    }

    /* Start the poller only once both a URL and the overlay grant are in place.
       The overlay grant needs a Settings round-trip, so we may bounce the user
       there and pick up again in onResume. */
    private void start_service_if_ready(){
        if(new Prefs(this).get_poll_url() == null){ return; }
        if(!Settings.canDrawOverlays(this)){
            toast("Grant 'display over other apps' to finish");
            request_overlay_permission();
            return;
        }
        startForegroundService(new Intent(this, PollService.class));
        toast("Puzzle Alerter is running");
        update_status();
    }

    private void request_overlay_permission(){
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void update_status(){
        boolean overlay_ok = Settings.canDrawOverlays(this);
        boolean notif_ok =
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean url_ok = new Prefs(this).get_poll_url() != null;
        status.setText(
            "Overlay permission: " + yes_no(overlay_ok) + "\n"
            + "Notifications: " + yes_no(notif_ok) + "\n"
            + "Poll URL set: " + yes_no(url_ok));
    }

    private static String yes_no(boolean ok){ return ok ? "granted" : "not granted"; }

    private void toast(String msg){ Toast.makeText(this, msg, Toast.LENGTH_LONG).show(); }

}
