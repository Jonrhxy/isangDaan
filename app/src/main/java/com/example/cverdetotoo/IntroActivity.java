package com.example.cverdetotoo;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;

public class IntroActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_intro_walk);

        // We simply wait for a tap anywhere on the screen.
        // You could also set an OnClickListener on a specific button or text view.
        View root = findViewById(R.id.introRoot);
        root.setOnClickListener(v -> {
            // When the user taps, go to the TrackingWalk Activity
            Intent intent = new Intent(IntroActivity.this, GPS.class);
            startActivity(intent);
            finish(); // close this screen so user doesn't come back to it
        });
    }
}
