package com.fundastock.terex3;

import android.app.Presentation;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Marketing carousel shown on the secondary display, following the
 * Android Presentation pattern from Rockchip's Android11 dual-display
 * guide (see MyPresentation.java in their DualScreenDemo). Unlike
 * launching a second Activity with setLaunchDisplayId(), a Presentation
 * is just a Dialog attached to a Display — no cross-display activity
 * launch permission involved, so it can't be denied the way the
 * standalone doublex1 app was.
 *
 * Cycles bundled photos from assets/photos/ every 2s. Owned and shown
 * for the lifetime of the process by Terex3App, independent of whichever
 * Activity is currently in front on the main display.
 */
public class MarketingPresentation extends Presentation {

    private static final String TAG = "MarketingPresentation";
    private static final String PHOTOS_DIR = "photos";
    private static final long ROTATE_INTERVAL_MS = 2000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<String> photoNames = new ArrayList<>();
    private final Random random = new Random();
    private int lastIndex = -1;

    private ImageView imageView;
    private TextView statusText;

    private final Runnable rotateRunnable = new Runnable() {
        @Override
        public void run() {
            showRandomImage();
            mainHandler.postDelayed(this, ROTATE_INTERVAL_MS);
        }
    };

    public MarketingPresentation(Context outerContext, Display display) {
        super(outerContext, display);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.presentation_marketing);
        imageView = findViewById(R.id.image_marketing);
        statusText = findViewById(R.id.text_marketing_status);

        loadPhotoNames();
        mainHandler.postDelayed(rotateRunnable, ROTATE_INTERVAL_MS);
        showRandomImage();
    }

    @Override
    protected void onStop() {
        super.onStop();
        mainHandler.removeCallbacksAndMessages(null);
    }

    private void loadPhotoNames() {
        try {
            String[] names = getContext().getAssets().list(PHOTOS_DIR);
            photoNames.clear();
            if (names != null) {
                for (String name : names) {
                    if (isImageFile(name)) photoNames.add(name);
                }
            }
            if (photoNames.isEmpty() && statusText != null) {
                statusText.setText("No hay fotos empaquetadas en assets/" + PHOTOS_DIR + "/");
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed listing assets/" + PHOTOS_DIR, e);
            if (statusText != null) statusText.setText("Error leyendo fotos:\n" + e);
        }
    }

    private boolean isImageFile(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp");
    }

    private void showRandomImage() {
        if (photoNames.isEmpty()) return;
        int index = 0;
        if (photoNames.size() > 1) {
            do {
                index = random.nextInt(photoNames.size());
            } while (index == lastIndex);
        }
        lastIndex = index;
        String name = photoNames.get(index);
        String assetPath = "file:///android_asset/" + PHOTOS_DIR + "/" + name;

        Glide.with(getContext())
                .load(assetPath)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .transition(DrawableTransitionOptions.withCrossFade(400))
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(GlideException e, Object model, Target<Drawable> target,
                                                 boolean isFirstResource) {
                        Log.w(TAG, "Failed to load " + name, e);
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target,
                                                    DataSource dataSource, boolean isFirstResource) {
                        return false;
                    }
                })
                .into(imageView);
    }
}
