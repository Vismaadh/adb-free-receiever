package com.example.adbfreereceiver;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.net.Uri;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private TextView status, network;
    private Button startStop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        network = findViewById(R.id.network);
        startStop = findViewById(R.id.startStop);

        startService();

        startStop.setOnClickListener(v -> {
            if (ReceiverService.running) {
                stopService(new Intent(this, ReceiverService.class));
                startStop.setText("Start Receiver");
                status.setText("Receiver stopped");
            } else {
                startService();
            }
        });
    }

    private void startService() {
        // Android 11+ requires the user to explicitly grant "All files access"
        // before this app can enumerate the public Downloads folder using
        // java.io.File/listFiles().
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                !Environment.isExternalStorageManager()) {
            status.setText("Storage access required");
            try {
                Intent settingsIntent = new Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(settingsIntent);
            } catch (Exception e) {
                Intent settingsIntent = new Intent(
                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivity(settingsIntent);
            }
            return;
        }

        Intent i = new Intent(this, ReceiverService.class);
        androidx.core.content.ContextCompat.startForegroundService(this, i);
        status.setText("Receiver running");
        startStop.setText("Stop Receiver");
    }
}
