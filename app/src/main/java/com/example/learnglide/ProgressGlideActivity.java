package com.example.learnglide;

import android.app.ProgressDialog;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.GlideDrawable;
import com.bumptech.glide.request.animation.GlideAnimation;
import com.bumptech.glide.request.target.GlideDrawableImageViewTarget;
import com.bumptech.glide.request.target.Target;
import com.example.learnglide.util.ProgressInterceptor;

public class ProgressGlideActivity extends AppCompatActivity {

    private ImageView ivShow;
    private ProgressDialog progressDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_progress_glide);
        ivShow = findViewById(R.id.iv_show);
        progressDialog = new ProgressDialog(this);
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMessage("加载中");
    }

    public void loadImage(View view) {
        final String url = "http://guolin.tech/book.png";
        ProgressInterceptor.addListener(url, progress -> progressDialog.setProgress(progress));

        Glide.with(this)
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                //                .into(new SimpleTarget<GlideDrawable>() {
                //                    @Override
                //                    public void onLoadStarted(Drawable placeholder) {
                //                        super.onLoadStarted(placeholder);
                //                        progressDialog.show();
                //                    }
                //
                //                    @Override
                //                    public void onResourceReady(GlideDrawable resource, GlideAnimation<? super GlideDrawable> glideAnimation) {
                //                        ivShow.setImageDrawable(resource);
                //                        progressDialog.dismiss();
                //                        ProgressInterceptor.removeListener(url);
                //                    }
                //                });
                .into(new GlideDrawableImageViewTarget(ivShow) {

                    @Override
                    public void onLoadStarted(Drawable placeholder) {
                        super.onLoadStarted(placeholder);
                        progressDialog.show();
                    }

                    @Override
                    public void onResourceReady(GlideDrawable resource, GlideAnimation<? super GlideDrawable> animation) {
                        super.onResourceReady(resource, animation);
                        progressDialog.dismiss();
                        ProgressInterceptor.removeListener(url);
                    }
                });
    }
}
