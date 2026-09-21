package com.fundastock.doublex1;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.View;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Unattended marketing display: cycles a random photo from assets/photos/
 * every 2s full-screen. Meant to run on a secondary display next to the
 * terex3 POS app (see Rockchip's dual-display guide — each app targets its
 * own screen independently, no inter-app wiring).
 *
 * Photos are bundled into the APK (not fetched from Supabase Storage) —
 * simpler and immune to bucket/network failures on unattended store
 * hardware. To refresh the photo set: drop new files in
 * android/doublex1/src/main/assets/photos/ and rebuild.
 *
 * This must never occupy the main POS screen — terex3 runs there. On
 * launch it checks which physical display it landed on; if that's not a
 * secondary/presentation display, it relaunches itself onto one and closes
 * this instance, so it self-corrects no matter how it was opened.
 */
public class SlideshowActivity extends Activity {

    private static final String TAG = "SlideshowActivity";
    private static final String PHOTOS_DIR = "photos";
    private static final long ROTATE_INTERVAL_MS = 2000;
    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<String> photoNames = new ArrayList<>();
    private final Random random = new Random();
    private int lastIndex = -1;

    private ImageView imageView;
    private View statusScroll;
    private TextView statusText;

    private final Runnable rotateRunnable = new Runnable() {
        @Override
        public void run() {
            showRandomImage();
            mainHandler.postDelayed(this, ROTATE_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // UI must exist before anything risky runs, so a failure below has
        // somewhere to show itself instead of crashing to a blank screen.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_slideshow);
        imageView = findViewById(R.id.image_slideshow);
        statusScroll = findViewById(R.id.status_scroll);
        statusText = findViewById(R.id.text_status);
        hideSystemBars();

        try {
            if (relocateToSecondaryDisplayIfNeeded()) {
                finish();
                return;
            }
        } catch (Exception e) {
            // Non-privileged apps can be denied cross-display launches on
            // some Android security configs. Fall back to running right
            // here instead of crashing — at least something shows up.
            showStatus("No se pudo mover a la pantalla secundaria, sigue aquí:\n" + e);
        }

        loadPhotoNames();
        mainHandler.postDelayed(rotateRunnable, ROTATE_INTERVAL_MS);
        showRandomImage();
    }

    /**
     * If a secondary/presentation display exists and this activity is not
     * already running on it, relaunches itself there via setLaunchDisplayId()
     * (same mechanism as Rockchip's dual-display guide) and returns true so
     * the caller can finish() this instance. Returns false if there's only
     * one display (e.g. testing on a single-screen device) or we're already
     * on the right one — nothing to do.
     */
    private boolean relocateToSecondaryDisplayIfNeeded() {
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (dm == null) return false;
        Display[] presentationDisplays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (presentationDisplays == null || presentationDisplays.length == 0) {
            return false;
        }
        int targetDisplayId = presentationDisplays[presentationDisplays.length - 1].getDisplayId();
        int currentDisplayId = getWindowManager().getDefaultDisplay().getDisplayId();
        if (currentDisplayId == targetDisplayId) {
            return false;
        }

        Log.i(TAG, "Relocating from display " + currentDisplayId + " to " + targetDisplayId);
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(targetDisplayId);
        Intent intent = new Intent(this, SlideshowActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(intent, options.toBundle());
        return true;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** Shows the full-screen error/status overlay so a failure can just be photographed. */
    private void showStatus(String message) {
        String stamped = "[" + TIME_FMT.format(new Date()) + "] " + message;
        Log.w(TAG, stamped);
        mainHandler.post(() -> {
            statusText.setText(stamped);
            statusScroll.setVisibility(View.VISIBLE);
        });
    }

    private void hideStatus() {
        mainHandler.post(() -> statusScroll.setVisibility(View.GONE));
    }

    private void loadPhotoNames() {
        try {
            String[] names = getAssets().list(PHOTOS_DIR);
            photoNames.clear();
            if (names != null) {
                for (String name : names) {
                    if (isImageFile(name)) photoNames.add(name);
                }
            }
            if (photoNames.isEmpty()) {
                showStatus("No hay fotos empaquetadas en assets/" + PHOTOS_DIR + "/");
            }
        } catch (IOException e) {
            showStatus("Error leyendo assets/" + PHOTOS_DIR + "/:\n" + e);
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

        Glide.with(this)
                .load(assetPath)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .transition(DrawableTransitionOptions.withCrossFade(400))
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(GlideException e, Object model, Target<Drawable> target,
                                                 boolean isFirstResource) {
                        showStatus("No se pudo cargar la imagen empaquetada:\n" + name + "\n\n"
                                + (e != null ? e.toString() : "error desconocido"));
                        return false;
                    }

                    @Override
                    public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target,
                                                    DataSource dataSource, boolean isFirstResource) {
                        hideStatus();
                        return false;
                    }
                })
                .into(imageView);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
    }
}
