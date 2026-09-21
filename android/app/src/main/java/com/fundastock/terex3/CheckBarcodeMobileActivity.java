package com.fundastock.terex3;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Check Barcode Mobile" — native translation of check_barcode_mobile3.html.
 * Uses the same /api/inventoryxbarcode3 GET/PATCH logic as InventoryCheckActivity
 * (talking to Supabase directly), plus an optional product photo uploaded to the
 * barcode-photos storage bucket for ML training, mirroring
 * /api/check_barcode_mobile3/photo.
 */
public class CheckBarcodeMobileActivity extends AppCompatActivity {

    private static final String TAG = "CheckBarcodeMobile";

    private final SupabaseClient supa = new SupabaseClient();
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final List<String> sessionLog = new ArrayList<>();

    private TextInputEditText etBarcode, etQty;
    private MaterialButton btnChecar, btnScan, btnPhoto;
    private TextView tvError, tvProductName, tvProductMeta;
    private TextView tvStockPrev, tvStockCounted, tvStockDiff, tvPhotoStatus;
    private View resultCard, verdictBanner, progress;
    private TextView tvVerdictIcon, tvVerdictTitle, tvVerdictSub, tvSessionTitle;
    private ImageView imgPreview;
    private LinearLayout sessionList;

    private File pendingPhotoFile;
    private Uri pendingPhotoUri;

    private final ActivityResultLauncher<Intent> scannerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String barcode = result.getData().getStringExtra(ScannerActivity.EXTRA_BARCODE);
                    if (barcode != null) {
                        etBarcode.setText(barcode);
                        etQty.requestFocus();
                    }
                }
            });

    private final ActivityResultLauncher<Uri> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.TakePicture(), success -> {
                if (success && pendingPhotoFile != null) {
                    showPhotoPreview(pendingPhotoFile);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_check_barcode_mobile);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        etBarcode = findViewById(R.id.etBarcode);
        etQty = findViewById(R.id.etQty);
        btnChecar = findViewById(R.id.btnChecar);
        btnScan = findViewById(R.id.btnScan);
        btnPhoto = findViewById(R.id.btnPhoto);
        tvError = findViewById(R.id.tvError);
        resultCard = findViewById(R.id.resultCard);
        tvProductName = findViewById(R.id.tvProductName);
        tvProductMeta = findViewById(R.id.tvProductMeta);
        tvStockPrev = findViewById(R.id.tvStockPrev);
        tvStockCounted = findViewById(R.id.tvStockCounted);
        tvStockDiff = findViewById(R.id.tvStockDiff);
        tvPhotoStatus = findViewById(R.id.tvPhotoStatus);
        verdictBanner = findViewById(R.id.verdictBanner);
        tvVerdictIcon = findViewById(R.id.tvVerdictIcon);
        tvVerdictTitle = findViewById(R.id.tvVerdictTitle);
        tvVerdictSub = findViewById(R.id.tvVerdictSub);
        tvSessionTitle = findViewById(R.id.tvSessionTitle);
        imgPreview = findViewById(R.id.imgPreview);
        sessionList = findViewById(R.id.sessionList);
        progress = findViewById(R.id.progress);

        btnScan.setOnClickListener(v -> scannerLauncher.launch(new Intent(this, ScannerActivity.class)));
        btnPhoto.setOnClickListener(v -> launchCamera());
        btnChecar.setOnClickListener(v -> checar());
        etQty.setOnEditorActionListener((v, actionId, event) -> {
            checar();
            return true;
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bgExecutor.shutdown();
    }

    private void launchCamera() {
        try {
            File dir = new File(getCacheDir(), "photos");
            if (!dir.exists()) dir.mkdirs();
            pendingPhotoFile = new File(dir, "photo_" + System.currentTimeMillis() + ".jpg");
            pendingPhotoUri = FileProvider.getUriForFile(this, "com.fundastock.terex3.fileprovider", pendingPhotoFile);
            cameraLauncher.launch(pendingPhotoUri);
        } catch (Exception e) {
            Log.e(TAG, "launchCamera failed", e);
        }
    }

    private void showPhotoPreview(File file) {
        Bitmap bitmap = decodeSampledBitmap(file, 800, 800);
        if (bitmap != null) {
            imgPreview.setImageBitmap(bitmap);
            imgPreview.setVisibility(View.VISIBLE);
        }
        btnPhoto.setText("📷 Foto capturada — toca para cambiar");
    }

    private Bitmap decodeSampledBitmap(File file, int reqWidth, int reqHeight) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);

        int inSampleSize = 1;
        int halfWidth = options.outWidth / 2;
        int halfHeight = options.outHeight / 2;
        while (halfWidth / inSampleSize >= reqWidth && halfHeight / inSampleSize >= reqHeight) {
            inSampleSize *= 2;
        }
        options.inSampleSize = inSampleSize;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private void checar() {
        String barcode = etBarcode.getText() == null ? "" : etBarcode.getText().toString().trim();
        String qtyRaw = etQty.getText() == null ? "" : etQty.getText().toString().trim();

        if (TextUtils.isEmpty(barcode)) {
            showError("Escanea o escribe un código");
            etBarcode.requestFocus();
            return;
        }
        if (TextUtils.isEmpty(qtyRaw)) {
            showError("Ingresa una cantidad (0 es válido)");
            etQty.requestFocus();
            return;
        }
        int qty;
        try {
            qty = Integer.parseInt(qtyRaw);
            if (qty < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            showError("Cantidad inválida");
            etQty.requestFocus();
            return;
        }

        hideError();
        btnChecar.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        File photoToUpload = pendingPhotoFile != null && pendingPhotoFile.exists() ? pendingPhotoFile : null;

        bgExecutor.execute(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("select", "barcode,name,estilo,estilo_id,marca,color,terex3");
                params.put("barcode", "eq." + barcode);
                params.put("limit", "1");
                JSONArray rows = supa.select("inventario1", params);
                if (rows.length() == 0) {
                    runOnUiThread(() -> {
                        progress.setVisibility(View.GONE);
                        btnChecar.setEnabled(true);
                        showError("Código " + barcode + " no encontrado");
                    });
                    return;
                }
                JSONObject product = rows.getJSONObject(0);
                int prevTerex3 = product.optInt("terex3", 0);
                boolean matches = prevTerex3 == qty;
                int diff = qty - prevTerex3;
                String productName = product.optString("name", product.optString("estilo", barcode));

                Map<String, String> filt = new HashMap<>();
                filt.put("barcode", "eq." + barcode);
                JSONObject invBody = new JSONObject();
                invBody.put("terex3", qty);
                supa.update("inventario1", filt, invBody);

                JSONObject histBody = new JSONObject();
                histBody.put("barcode", Long.parseLong(barcode));
                histBody.put("product_name", productName);
                histBody.put("qty_before", prevTerex3);
                histBody.put("qty_counted", qty);
                histBody.put("matches", matches);
                histBody.put("difference", diff);
                try {
                    supa.insert("terex3_history", histBody);
                } catch (Exception e) {
                    Log.e(TAG, "terex3_history insert failed", e);
                }

                JSONObject changeBody = new JSONObject();
                changeBody.put("barcode", Long.parseLong(barcode));
                changeBody.put("product_name", productName);
                changeBody.put("branch", "terex3");
                changeBody.put("source", "count");
                changeBody.put("qty_before", prevTerex3);
                changeBody.put("qty_after", qty);
                changeBody.put("delta", diff);
                try {
                    supa.insert("inventory_changes", changeBody);
                } catch (Exception e) {
                    Log.e(TAG, "inventory_changes insert failed", e);
                }

                boolean photoUploaded = false;
                if (photoToUpload != null) {
                    photoUploaded = uploadPhoto(photoToUpload, barcode, product);
                }

                boolean finalPhotoUploaded = photoUploaded;
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    btnChecar.setEnabled(true);
                    showResult(product, prevTerex3, qty, matches, diff, finalPhotoUploaded);
                    addSessionEntry(productName, barcode, prevTerex3, qty, matches, diff);
                    etBarcode.setText("");
                    etQty.setText("");
                    pendingPhotoFile = null;
                    pendingPhotoUri = null;
                    imgPreview.setVisibility(View.GONE);
                    btnPhoto.setText(R.string.btn_tomar_foto);
                    etBarcode.requestFocus();
                });
            } catch (Exception e) {
                Log.e(TAG, "checar failed", e);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    btnChecar.setEnabled(true);
                    showError("⚠️ " + e.getMessage());
                });
            }
        });
    }

    /** Mirrors upload_barcode_photo2() in app.py — uploads to Storage, logs to barcode_photos. */
    private boolean uploadPhoto(File file, String barcode, JSONObject product) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String nowStr = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String uid = UUID.randomUUID().toString().substring(0, 8);
            String storagePath = "training/" + barcode + "/" + nowStr + "_" + uid + ".jpg";

            supa.uploadToStorage("barcode-photos", storagePath, bytes, "image/jpeg");
            String publicUrl = Config.SUPABASE_URL + "/storage/v1/object/public/barcode-photos/" + storagePath;

            JSONObject row = new JSONObject();
            row.put("barcode", barcode);
            row.put("product_name", product.optString("name", ""));
            row.put("estilo", product.optString("estilo", ""));
            if (!product.isNull("estilo_id")) row.put("estilo_id", product.opt("estilo_id"));
            row.put("color", product.optString("color", ""));
            row.put("file_path", storagePath);
            row.put("public_url", publicUrl);
            supa.insert("barcode_photos", row);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "uploadPhoto failed", e);
            return false;
        }
    }

    private void showResult(JSONObject product, int prev, int counted, boolean matches, int diff, boolean photoUploaded) {
        String name = product.optString("name", product.optString("estilo", "—"));
        List<String> metaParts = new ArrayList<>();
        String marca = product.optString("marca", "");
        String color = product.optString("color", "");
        if (!TextUtils.isEmpty(marca)) metaParts.add(marca);
        if (!TextUtils.isEmpty(color)) metaParts.add(color);
        if (!product.isNull("estilo_id")) metaParts.add("E" + product.optString("estilo_id"));
        metaParts.add(product.optString("barcode"));

        tvProductName.setText(name);
        tvProductMeta.setText(TextUtils.join(" · ", metaParts));
        tvStockPrev.setText(String.valueOf(prev));
        tvStockCounted.setText(String.valueOf(counted));

        String diffLabel = diff == 0 ? "0" : (diff > 0 ? "+" + diff : String.valueOf(diff));
        tvStockDiff.setText(diffLabel);
        tvStockDiff.setTextColor(getColor(diff < 0 ? R.color.error : R.color.accent));

        if (matches) {
            verdictBanner.setBackgroundColor(getColor(R.color.loyalty_row));
            tvVerdictIcon.setText("✅");
            tvVerdictTitle.setText("YES");
            tvVerdictTitle.setTextColor(getColor(R.color.accent));
            tvVerdictSub.setText("terex3 confirmado en " + counted);
        } else {
            verdictBanner.setBackgroundColor(0xFFFFEBEE);
            tvVerdictIcon.setText("❌");
            tvVerdictTitle.setText("NO — Dif: " + diffLabel);
            tvVerdictTitle.setTextColor(getColor(R.color.error));
            tvVerdictSub.setText(prev + " → " + counted);
        }

        if (photoUploaded) {
            tvPhotoStatus.setText(R.string.msg_foto_guardada);
            tvPhotoStatus.setVisibility(View.VISIBLE);
        } else {
            tvPhotoStatus.setVisibility(View.GONE);
        }
        resultCard.setVisibility(View.VISIBLE);
    }

    private void addSessionEntry(String name, String barcode, int prev, int counted, boolean matches, int diff) {
        String diffLabel = diff == 0 ? "0" : (diff > 0 ? "+" + diff : String.valueOf(diff));
        sessionLog.add(0, name + "  (" + barcode + ": " + prev + "→" + counted + ")  " + (matches ? "YES" : diffLabel));
        if (sessionLog.size() > 15) sessionLog.remove(sessionLog.size() - 1);

        sessionList.removeAllViews();
        for (String entry : sessionLog) {
            TextView tv = new TextView(this);
            tv.setText(entry);
            tv.setTextSize(13);
            tv.setPadding(dp(14), dp(10), dp(14), dp(10));
            tv.setBackgroundColor(getColor(R.color.surface));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            tv.setLayoutParams(lp);
            sessionList.addView(tv);
        }
        tvSessionTitle.setVisibility(View.VISIBLE);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showError(String msg) {
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        tvError.setVisibility(View.GONE);
    }
}
