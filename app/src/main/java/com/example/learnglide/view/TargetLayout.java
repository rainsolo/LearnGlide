package com.example.learnglide.view;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import com.bumptech.glide.load.resource.drawable.GlideDrawable;
import com.bumptech.glide.request.animation.GlideAnimation;
import com.bumptech.glide.request.target.ViewTarget;

public class TargetLayout extends FrameLayout {

    private ViewTarget<TargetLayout, GlideDrawable> viewTarget;

    public TargetLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        viewTarget = new ViewTarget<TargetLayout, GlideDrawable>(this) {
            @Override
            public void onResourceReady(GlideDrawable resource, GlideAnimation<? super GlideDrawable> glideAnimation) {
                getView().justDoSomethingWithDrawable(resource);
            }
        };
    }

    public ViewTarget<TargetLayout, GlideDrawable> getTarget() {
        return viewTarget;
    }

    private void justDoSomethingWithDrawable(GlideDrawable resource) {
        setBackground(resource);
    }
}
