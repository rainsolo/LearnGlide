package com.example.learnglide;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.tv_glide_target).setOnClickListener(
                v -> startActivity(new Intent(MainActivity.this, GlideTargetActivity.class)));
    }
}
