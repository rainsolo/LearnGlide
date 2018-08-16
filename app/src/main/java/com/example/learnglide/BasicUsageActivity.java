package com.example.learnglide;

import android.content.Context;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.GlideDrawable;
import com.bumptech.glide.request.FutureTarget;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.animation.GlideAnimation;
import com.bumptech.glide.request.target.SimpleTarget;
import com.bumptech.glide.request.target.Target;

import java.io.File;

public class BasicUsageActivity extends AppCompatActivity {

    private final String TAG = getClass().getSimpleName();
    private final String URL = "https://bing.ioliu.cn/photo/MountainDayJapan_EN-AU8690491173?force=home_1";

    private ImageView ivShow;
    private TextView tvShow;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_basic_usage);

        ivShow = findViewById(R.id.iv_show);
        tvShow = findViewById(R.id.tv_show);
        downloadImage(ivShow);
    }

    /**
     * 需要指定DiskCacheStrategy
     *
     * @see com.bumptech.glide.request.target.PreloadTarget
     */
    private void preLoad() {
        Glide.with(this)
                .load(URL)
                .diskCacheStrategy(DiskCacheStrategy.SOURCE)
                .preload();
    }

    /**
     * downloadOnly(int width, int height) 需开启线程
     * 其实是
     * RequestFutureTarget要求必须在子线程当中执行
     *
     * @param view
     */
    public void downloadImage(View view) {
        new Thread(() -> {
            try {
                Context context = getApplicationContext();
                FutureTarget<File> target = Glide.with(context)
                        .load(URL)
                        .downloadOnly(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL);

                final File imageFile = target.get();
                runOnUiThread(() -> {
                    tvShow.setText(imageFile.getPath());
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    public void loadImage(ImageView view) {
        Glide.with(this)
                .load(URL)
                .diskCacheStrategy(DiskCacheStrategy.SOURCE)
                .into(view);
    }

    /**
     * downloadOnly(Y target)是用于在主线程中下载图片
     *
     * @param view
     */
    public void downloadImage2(View view) {
        Glide.with(this)
                .load(URL)
                .downloadOnly(new SimpleTarget<File>() {
                    @Override
                    public void onResourceReady(File resource, GlideAnimation<? super File> glideAnimation) {
                        Log.d(TAG, resource.getPath());
                    }
                });
    }

    /**
     * listener 配合 into 一起食用
     *
     * @param view
     * @see com.bumptech.glide.request.GenericRequest
     */
    public void loadImageAddListener(ImageView view) {
        Glide.with(this)
                .load(URL)
                .listener(new RequestListener<String, GlideDrawable>() {
                    @Override
                    public boolean onException(Exception e, String model, Target<GlideDrawable> target, boolean isFirstResource) {
                        // 返回 false 表示不消费事件
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(GlideDrawable resource, String model, Target<GlideDrawable> target, boolean isFromMemoryCache, boolean isFirstResource) {
                        return false;
                    }
                })
                .into(view);
    }


}
