package com.fundastock.terex3;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Inventario x Código" — native translation of inventoryxbarcode3.html +
 * the /api/inventoryxbarcode3 GET/PATCH logic in app.py. Talks to Supabase
 * directly: reads inventario1 + terex3_history, then on submit writes the
 * new terex3 count, a terex3_history row and an inventory_changes row.
 */
public class InventoryCheckActivity extends AppCompatActivity {

    private static final String TAG = "InventoryCheck";

    private final SupabaseClient supa = new SupabaseClient();
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final List<String> sessionLog = new ArrayList<>();

    private TextInputEditText etBarcode, etQty;
    private MaterialButton btnChecar, btnScan;
    private TextView tvError, tvProductName, tvProductMeta;
    private TextView tvStockPrev, tvStockCounted, tvStockDiff;
    private View resultCard, verdictBanner, progress;
    private TextView tvVerdictIcon, tvVerdictTitle, tvVerdictSub;
    private TextView tvHistoryTitle, tvSessionTitle;
    private LinearLayout historyList, sessionList;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inventory_check);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        etBarcode = findViewById(R.id.etBarcode);
        etQty = findViewById(R.id.etQty);
        btnChecar = findViewById(R.id.btnChecar);
        btnScan = findViewById(R.id.btnScan);
        tvError = findViewById(R.id.tvError);
        resultCard = findViewById(R.id.resultCard);
        tvProductName = findViewById(R.id.tvProductName);
        tvProductMeta = findViewById(R.id.tvProductMeta);
        tvStockPrev = findViewById(R.id.tvStockPrev);
        tvStockCounted = findViewById(R.id.tvStockCounted);
        tvStockDiff = findViewById(R.id.tvStockDiff);
        verdictBanner = findViewById(R.id.verdictBanner);
        tvVerdictIcon = findViewById(R.id.tvVerdictIcon);
        tvVerdictTitle = findViewById(R.id.tvVerdictTitle);
        tvVerdictSub = findViewById(R.id.tvVerdictSub);
        tvHistoryTitle = findViewById(R.id.tvHistoryTitle);
        tvSessionTitle = findViewById(R.id.tvSessionTitle);
        historyList = findViewById(R.id.historyList);
        sessionList = findViewById(R.id.sessionList);
        progress = findViewById(R.id.progress);

        btnScan.setOnClickListener(v -> scannerLauncher.launch(new Intent(this, ScannerActivity.class)));
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

    private void checar() {
        String barcode = etBarcode.getText() == null ? "" : etBarcode.getText().toString().trim();
        String qtyRaw = etQty.getText() == null ? "" : etQty.getText().toString().trim();

        if (TextUtils.isEmpty(barcode)) {
            showError("Ingresa un código de barras");
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
                        showError("❌ Código " + barcode + " no encontrado en inventario1");
                    });
                    return;
                }
                JSONObject product = rows.getJSONObject(0);
                int prevTerex3 = product.optInt("terex3", 0);
                boolean matches = prevTerex3 == qty;
                int diff = qty - prevTerex3;

                Map<String, String> histParams = new HashMap<>();
                histParams.put("barcode", "eq." + barcode);
                histParams.put("order", "created_at.desc");
                histParams.put("limit", "20");
                JSONArray history = supa.select("terex3_history", histParams);

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

                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    btnChecar.setEnabled(true);
                    showResult(product, prevTerex3, qty, matches, diff);
                    renderHistory(barcode, history, prevTerex3, qty, matches, diff);
                    addSessionEntry(productName, barcode, prevTerex3, qty, matches, diff);
                    etBarcode.setText("");
                    etQty.setText("");
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

    private void showResult(JSONObject product, int prev, int counted, boolean matches, int diff) {
        String name = product.optString("name", product.optString("estilo", "—"));
        List<String> metaParts = new ArrayList<>();
        String marca = product.optString("marca", "");
        String color = product.optString("color", "");
        if (!TextUtils.isEmpty(marca)) metaParts.add(marca);
        if (!TextUtils.isEmpty(color)) metaParts.add(color);
        if (!product.isNull("estilo_id")) metaParts.add("Estilo " + product.optString("estilo_id"));
        metaParts.add("barcode: " + product.optString("barcode"));

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
            tvVerdictTitle.setText("YES — Cantidad correcta");
            tvVerdictTitle.setTextColor(getColor(R.color.accent));
            tvVerdictSub.setText("terex3 confirmado en " + counted);
        } else {
            verdictBanner.setBackgroundColor(0xFFFFEBEE);
            tvVerdictIcon.setText("❌");
            tvVerdictTitle.setText("NO — Diferencia: " + diffLabel);
            tvVerdictTitle.setTextColor(getColor(R.color.error));
            tvVerdictSub.setText("Sistema tenía " + prev + " · Contaste " + counted + " → actualizado a " + counted);
        }
        resultCard.setVisibility(View.VISIBLE);
    }

    private void renderHistory(String barcode, JSONArray history, int latestPrev, int latestCounted,
                                boolean latestMatches, int latestDiff) {
        historyList.removeAllViews();

        addHistoryRow("Ahora 🆕", latestPrev, latestCounted, latestDiff, latestMatches);
        for (int i = 0; i < history.length(); i++) {
            JSONObject h = history.optJSONObject(i);
            if (h == null) continue;
            int qb = h.optInt("qty_before", 0);
            int qc = h.optInt("qty_counted", 0);
            int diff = h.has("difference") ? h.optInt("difference") : (qc - qb);
            boolean m = h.optBoolean("matches", qb == qc);
            String created = h.optString("created_at", "");
            addHistoryRow(formatTimestamp(created), qb, qc, diff, m);
        }
        tvHistoryTitle.setVisibility(View.VISIBLE);
    }

    private String formatTimestamp(String iso) {
        if (TextUtils.isEmpty(iso)) return "—";
        // created_at comes as "yyyy-MM-ddTHH:mm:ss..." — keep it readable without
        // pulling in a full date parser for a cosmetic label.
        String cleaned = iso.replace("T", " ");
        return cleaned.length() > 16 ? cleaned.substring(0, 16) : cleaned;
    }

    private void addHistoryRow(String whenLabel, int before, int counted, int diff, boolean matches) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView tvWhen = new TextView(this);
        tvWhen.setText(whenLabel);
        tvWhen.setTextSize(11);
        tvWhen.setTextColor(getColor(R.color.text_secondary));
        tvWhen.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f));

        String diffLabel = diff == 0 ? "0" : (diff > 0 ? "+" + diff : String.valueOf(diff));
        TextView tvVals = new TextView(this);
        tvVals.setText(String.format(Locale.US, "%d → %d (%s)", before, counted, diffLabel));
        tvVals.setTextSize(12);
        tvVals.setTextColor(getColor(diff < 0 ? R.color.error : R.color.text_primary));
        tvVals.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvBadge = new TextView(this);
        tvBadge.setText(matches ? "YES" : "NO");
        tvBadge.setTextSize(11);
        tvBadge.setTextColor(getColor(matches ? R.color.accent : R.color.error));
        tvBadge.setTypeface(null, android.graphics.Typeface.BOLD);

        row.addView(tvWhen);
        row.addView(tvVals);
        row.addView(tvBadge);

        View divider = new View(this);
        divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        divider.setBackgroundColor(0xFFEEEEEE);

        historyList.addView(row);
        historyList.addView(divider);
    }

    private void addSessionEntry(String name, String barcode, int prev, int counted, boolean matches, int diff) {
        String diffLabel = diff == 0 ? "0" : (diff > 0 ? "+" + diff : String.valueOf(diff));
        sessionLog.add(0, name + "  (" + barcode + ": " + prev + "→" + counted + ")  " + (matches ? "YES" : diffLabel));
        if (sessionLog.size() > 10) sessionLog.remove(sessionLog.size() - 1);

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
