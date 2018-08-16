package com.example.learnglide;

import android.content.Intent;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;

public class MainActivity extends AppCompatActivity {


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    public void clickGlideBasicUsage(View view) {
        startActivity(new Intent(MainActivity.this, BasicUsageActivity.class));
    }

    public void clickGlideTarget(View view) {
        startActivity(new Intent(MainActivity.this, GlideTargetActivity.class));
    }

    public void clickGlideTransform(View view) {
        startActivity(new Intent(MainActivity.this, TransformActivity.class));
    }

    public void clickGlideProgress(View view) {
        startActivity(new Intent(MainActivity.this, ProgressGlideActivity.class));
    }
}
