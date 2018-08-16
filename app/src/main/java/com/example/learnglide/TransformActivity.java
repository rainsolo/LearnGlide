package com.example.learnglide;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;
import com.bumptech.glide.request.target.Target;

/**
 * 更加复杂的 transform 推荐使用
 * compile 'jp.wasabeef:glide-transformations:2.0.2'
 */
public class TransformActivity extends AppCompatActivity {

    private final String URL = "https://bing.ioliu.cn/photo/OtterChillin_EN-AU10154811440?force=download";
    private final String URL_BAIDU = "https://www.baidu.com/img/bd_logo1.png";


    private ImageView imageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transform);
        imageView = findViewById(R.id.iv_show); // default scaleType = fitCenter
    }

    public void loadImage(View view) {
        testMyTransform();
    }

    private void testNormalLoad() {
        Glide.with(this)
                .load(URL_BAIDU)
                .into(imageView);
    }

    private void testDontTransform() {
        Glide.with(this)
                .load(URL_BAIDU)
                .dontTransform()
                .into(imageView);
    }

    private void testOverrideOriginLoad() {
        Glide.with(this)
                .load(URL_BAIDU)
                .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                .into(imageView);
    }

    private void testNormalLoad2() {
        Glide.with(this)
                .load(URL)
                .into(imageView);
    }

    private void testCenterCrop() {
        Glide.with(this)
                .load(URL)
                .centerCrop()
                .override(500, 500)
                .into(imageView);
    }


    private void testMyTransform() {
        Glide.with(this)
                .load(URL)
                .transform(new CircleCrop(this))
                .into(imageView);
    }

    public class CircleCrop extends BitmapTransformation {

        public CircleCrop(Context context) {
            super(context);
        }

        public CircleCrop(BitmapPool bitmapPool) {
            super(bitmapPool);
        }


        @Override
        protected Bitmap transform(BitmapPool pool, Bitmap toTransform, int outWidth, int outHeight) {
            int diameter = Math.min(toTransform.getWidth(), toTransform.getHeight());

            final Bitmap toReuse = pool.get(outWidth, outHeight, Bitmap.Config.ARGB_8888);
            final Bitmap result;
            if (toReuse != null) {
                result = toReuse;
            } else {
                result = Bitmap.createBitmap(diameter, diameter, Bitmap.Config.ARGB_8888);
            }

            int dx = (toTransform.getWidth() - diameter) / 2;
            int dy = (toTransform.getHeight() - diameter) / 2;
            Canvas canvas = new Canvas(result);
            Paint paint = new Paint();
            BitmapShader shader = new BitmapShader(toTransform, BitmapShader.TileMode.CLAMP, BitmapShader.TileMode.CLAMP);
            if (dx != 0 || dy != 0) {
                Matrix matrix = new Matrix();
                matrix.setTranslate(-dx, -dy);
                shader.setLocalMatrix(matrix);
            }
            paint.setShader(shader);
            paint.setAntiAlias(true);
            float radius = diameter / 2f;
            canvas.drawCircle(radius, radius, radius, paint);

            // no use
            //            if (toReuse != null && !pool.put(toReuse)) {
            //                toReuse.recycle();
            //            }
            return result;
        }

        @Override
        public String getId() {
            return "com.example.glidetest.CircleCrop";
        }
    }
}
