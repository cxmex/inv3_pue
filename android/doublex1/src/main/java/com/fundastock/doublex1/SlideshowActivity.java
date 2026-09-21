package com.fundastock.doublex1;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Unattended marketing display: pulls the images-colores bucket from Supabase
 * and cycles a random one every 2s full-screen. Meant to run on a secondary
 * display next to the terex3 POS app (see Rockchip's dual-display guide —
 * each app targets its own screen independently, no inter-app wiring).
 */
public class SlideshowActivity extends Activity {

    private static final String TAG = "SlideshowActivity";
    private static final long ROTATE_INTERVAL_MS = 2000;
    private static final long LIST_REFRESH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long LIST_RETRY_INTERVAL_MS = TimeUnit.SECONDS.toMillis(15);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private final List<String> imageUrls = new ArrayList<>();
    private final Random random = new Random();
    private int lastIndex = -1;

    private ImageView imageView;

    private final Runnable rotateRunnable = new Runnable() {
        @Override
        public void run() {
            showRandomImage();
            mainHandler.postDelayed(this, ROTATE_INTERVAL_MS);
        }
    };

    private final Runnable refreshListRunnable = new Runnable() {
        @Override
        public void run() {
            fetchImageList();
            mainHandler.postDelayed(this, LIST_REFRESH_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_slideshow);
        imageView = findViewById(R.id.image_slideshow);
        hideSystemBars();

        fetchImageList();
        mainHandler.postDelayed(refreshListRunnable, LIST_REFRESH_INTERVAL_MS);
        mainHandler.postDelayed(rotateRunnable, ROTATE_INTERVAL_MS);
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

    private void fetchImageList() {
        executor.execute(() -> {
            try {
                JSONObject sortBy = new JSONObject()
                        .put("column", "name")
                        .put("order", "asc");
                JSONObject body = new JSONObject()
                        .put("limit", 1000)
                        .put("offset", 0)
                        .put("sortBy", sortBy);

                Request request = new Request.Builder()
                        .url(Config.SUPABASE_URL + "/storage/v1/object/list/" + Config.IMAGES_BUCKET)
                        .header("apikey", Config.SUPABASE_ANON_KEY)
                        .header("Authorization", "Bearer " + Config.SUPABASE_ANON_KEY)
                        .header("Content-Type", "application/json")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();

                try (Response resp = http.newCall(request).execute()) {
                    String respBody = resp.body() != null ? resp.body().string() : "[]";
                    if (!resp.isSuccessful()) {
                        Log.w(TAG, "List bucket failed: " + resp.code() + " " + respBody);
                        scheduleRetryIfEmpty();
                        return;
                    }
                    JSONArray arr = new JSONArray(respBody.isEmpty() ? "[]" : respBody);
                    List<String> urls = new ArrayList<>();
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject item = arr.getJSONObject(i);
                        String name = item.optString("name", "");
                        // Folders come back with a null id; skip them, we only want files.
                        if (name.isEmpty() || item.isNull("id") || !isImageFile(name)) continue;
                        String encodedName = Uri.encode(name);
                        urls.add(Config.SUPABASE_URL + "/storage/v1/object/public/"
                                + Config.IMAGES_BUCKET + "/" + encodedName);
                    }
                    if (!urls.isEmpty()) {
                        synchronized (imageUrls) {
                            imageUrls.clear();
                            imageUrls.addAll(urls);
                        }
                    } else {
                        scheduleRetryIfEmpty();
                    }
                }
            } catch (IOException | org.json.JSONException e) {
                Log.w(TAG, "fetchImageList error", e);
                scheduleRetryIfEmpty();
            }
        });
    }

    private void scheduleRetryIfEmpty() {
        synchronized (imageUrls) {
            if (!imageUrls.isEmpty()) return;
        }
        mainHandler.postDelayed(this::fetchImageList, LIST_RETRY_INTERVAL_MS);
    }

    private boolean isImageFile(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".png") || lower.endsWith(".webp");
    }

    private void showRandomImage() {
        String url;
        synchronized (imageUrls) {
            if (imageUrls.isEmpty()) return;
            int index = 0;
            if (imageUrls.size() > 1) {
                do {
                    index = random.nextInt(imageUrls.size());
                } while (index == lastIndex);
            }
            lastIndex = index;
            url = imageUrls.get(index);
        }
        Glide.with(this)
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .transition(DrawableTransitionOptions.withCrossFade(400))
                .into(imageView);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }
}
