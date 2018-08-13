package com.example.learnglide;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.GlideDrawable;
import com.bumptech.glide.request.animation.GlideAnimation;
import com.bumptech.glide.request.target.SimpleTarget;
import com.example.learnglide.view.TargetLayout;

public class GlideTargetActivity extends AppCompatActivity {
    private final String URL = "http://cn.bing.com/az/hprichbg/rb/TOAD_ZH-CN7336795473_1920x1080.jpg";

    private ImageView ivImgShow;
    private TargetLayout targetLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_glide_target);
        ivImgShow = findViewById(R.id.iv_img_show);
        targetLayout = findViewById(R.id.targetLayout);

        findViewById(R.id.btn_load_img).setOnClickListener(v -> {
            GlideCustomerTarget();
        });
    }

    private void Glideinto() {
        Glide.with(this)
                .load(URL)
                .into(ivImgShow);
    }

    private void GlideSimpleTarget() {
        Glide.with(this)
                .load(URL)
                .into(new SimpleTarget<GlideDrawable>() {
                    @Override
                    public void onResourceReady(GlideDrawable resource, GlideAnimation<? super GlideDrawable> glideAnimation) {
                        ivImgShow.setBackground(resource);
                    }
                });
    }

    private void GlideCustomerTarget() {
        Glide.with(this)
                .load(URL)
                .into(targetLayout.getTarget());
    }
}
