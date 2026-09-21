package com.fundastock.doublex1;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
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
 *
 * This runs unattended on store hardware nobody is logged into, so on any
 * failure it shows the error full-screen instead of going blank — that way
 * someone can just photograph the screen and send it back for debugging.
 */
public class SlideshowActivity extends Activity {

    private static final String TAG = "SlideshowActivity";
    private static final long ROTATE_INTERVAL_MS = 2000;
    private static final long LIST_REFRESH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long LIST_RETRY_INTERVAL_MS = TimeUnit.SECONDS.toMillis(15);
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

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
    private View statusScroll;
    private TextView statusText;

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
        statusScroll = findViewById(R.id.status_scroll);
        statusText = findViewById(R.id.text_status);
        hideSystemBars();

        showStatus("Iniciando…\nBucket: " + Config.IMAGES_BUCKET);
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

    private void fetchImageList() {
        executor.execute(() -> {
            Request request;
            try {
                JSONObject sortBy = new JSONObject()
                        .put("column", "name")
                        .put("order", "asc");
                JSONObject body = new JSONObject()
                        .put("limit", 1000)
                        .put("offset", 0)
                        .put("sortBy", sortBy);

                request = new Request.Builder()
                        .url(Config.SUPABASE_URL + "/storage/v1/object/list/" + Config.IMAGES_BUCKET)
                        .header("apikey", Config.SUPABASE_ANON_KEY)
                        .header("Authorization", "Bearer " + Config.SUPABASE_ANON_KEY)
                        .header("Content-Type", "application/json")
                        .post(RequestBody.create(body.toString(), JSON))
                        .build();
            } catch (org.json.JSONException e) {
                showStatus("Error interno armando la solicitud:\n" + e);
                scheduleRetryIfEmpty();
                return;
            }

            try (Response resp = http.newCall(request).execute()) {
                String respBody = resp.body() != null ? resp.body().string() : "[]";
                if (!resp.isSuccessful()) {
                    showStatus("Error al listar bucket '" + Config.IMAGES_BUCKET + "'\n"
                            + "HTTP " + resp.code() + "\n"
                            + truncate(respBody, 500) + "\n\n"
                            + "URL: " + request.url());
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
                    hideStatus();
                } else {
                    showStatus("El bucket '" + Config.IMAGES_BUCKET + "' respondió OK pero no "
                            + "tiene archivos .jpg/.jpeg/.png/.webp.\n"
                            + "Respuesta cruda (" + arr.length() + " items):\n"
                            + truncate(respBody, 500));
                    scheduleRetryIfEmpty();
                }
            } catch (IOException e) {
                showStatus("Error de red al listar bucket '" + Config.IMAGES_BUCKET + "':\n"
                        + e + "\n\nURL: " + request.url());
                scheduleRetryIfEmpty();
            } catch (org.json.JSONException e) {
                showStatus("Respuesta inesperada del bucket '" + Config.IMAGES_BUCKET + "':\n" + e);
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

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "…" : s;
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
        final String loadedUrl = url;
        Glide.with(this)
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .transition(DrawableTransitionOptions.withCrossFade(400))
                .listener(new RequestListener<Drawable>() {
                    @Override
                    public boolean onLoadFailed(GlideException e, Object model, Target<Drawable> target,
                                                 boolean isFirstResource) {
                        showStatus("No se pudo cargar la imagen:\n" + loadedUrl + "\n\n"
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
        executor.shutdownNow();
    }
}
