package com.example.adbfreereceiver;

import android.content.Intent;
import android.os.Bundle;
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
        Intent i = new Intent(this, ReceiverService.class);
        androidx.core.content.ContextCompat.startForegroundService(this, i);
        status.setText("Receiver running");
        startStop.setText("Stop Receiver");
    }
}
