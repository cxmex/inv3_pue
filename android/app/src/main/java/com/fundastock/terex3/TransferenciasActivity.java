package com.fundastock.terex3;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Transferir Mercancía" — native translation of transferir_mercancia.html
 * (the actual page served at /transferencias) + /api/transferir and
 * /api/transferencias in app.py. Source branch is always terex3 here.
 * Talks to Supabase directly.
 */
public class TransferenciasActivity extends AppCompatActivity {

    private static final String TAG = "Transferencias";
    private static final String SOURCE_BRANCH = "terex3";
    private static final String[] OTHER_BRANCHES = {"terex1", "terex2"};
    private static final String[] BRANCH_LABELS = {"Sucursal 1", "Sucursal 2"};

    private static class CartItem {
        String codigo, name;
        int sourceStock, qty;
        boolean forced;
    }

    private final SupabaseClient supa = new SupabaseClient();
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final List<CartItem> cart = new ArrayList<>();

    private Spinner spinnerTarget;
    private TextInputEditText etBarcode;
    private MaterialButton btnScan, btnTransferir;
    private TextView tvScanStatus, tvTotalSkus, tvTotalPiezas;
    private CheckBox cbForce;
    private LinearLayout productsList, historyList;
    private View summarySection, progress;

    private final ActivityResultLauncher<Intent> scannerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String barcode = result.getData().getStringExtra(ScannerActivity.EXTRA_BARCODE);
                    if (barcode != null) lookupProduct(barcode);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transferencias);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        spinnerTarget = findViewById(R.id.spinnerTarget);
        etBarcode = findViewById(R.id.etBarcode);
        btnScan = findViewById(R.id.btnScan);
        btnTransferir = findViewById(R.id.btnTransferir);
        tvScanStatus = findViewById(R.id.tvScanStatus2);
        tvTotalSkus = findViewById(R.id.tvTotalSkus);
        tvTotalPiezas = findViewById(R.id.tvTotalPiezas);
        cbForce = findViewById(R.id.cbForce);
        productsList = findViewById(R.id.productsList);
        historyList = findViewById(R.id.historyList);
        summarySection = findViewById(R.id.summarySection);
        progress = findViewById(R.id.progress);

        ArrayAdapter<String> targetAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, BRANCH_LABELS);
        targetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTarget.setAdapter(targetAdapter);

        btnScan.setOnClickListener(v -> scannerLauncher.launch(new Intent(this, ScannerActivity.class)));
        etBarcode.setOnEditorActionListener((v, actionId, event) -> {
            String code = etBarcode.getText() == null ? "" : etBarcode.getText().toString().trim();
            if (!TextUtils.isEmpty(code)) lookupProduct(code);
            etBarcode.setText("");
            return true;
        });
        btnTransferir.setOnClickListener(v -> doTransfer());

        renderProducts();
        loadHistory();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bgExecutor.shutdown();
    }

    private String targetBranch() {
        return OTHER_BRANCHES[spinnerTarget.getSelectedItemPosition()];
    }

    private void lookupProduct(String code) {
        for (CartItem p : cart) {
            if (p.codigo.equals(code)) {
                p.qty += 1;
                renderProducts();
                setScanStatus(code + " (+1)", false);
                return;
            }
        }

        setScanStatus("Buscando " + code + "...", false);
        String target = targetBranch();
        bgExecutor.execute(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("select", "barcode,name,estilo," + SOURCE_BRANCH + "," + target);
                params.put("barcode", "eq." + code);
                params.put("limit", "1");
                JSONArray rows = supa.select("inventario1", params);
                if (rows.length() == 0) {
                    runOnUiThread(() -> setScanStatus("No encontrado: " + code, true));
                    return;
                }
                JSONObject data = rows.getJSONObject(0);
                int sourceStock = data.optInt(SOURCE_BRANCH, 0);

                runOnUiThread(() -> {
                    boolean forceMode = cbForce.isChecked();
                    if (sourceStock <= 0 && !forceMode) {
                        setScanStatus("Sin stock en Terex3: " + code + " (activa Forzar)", true);
                        return;
                    }
                    CartItem item = new CartItem();
                    item.codigo = code;
                    item.name = data.optString("name", code);
                    item.sourceStock = sourceStock;
                    item.qty = 1;
                    item.forced = sourceStock <= 0;
                    cart.add(item);
                    renderProducts();
                    setScanStatus((item.forced ? "Agregado (FORZADO): " : "Agregado: ") + item.name, false);
                });
            } catch (Exception e) {
                Log.e(TAG, "lookupProduct failed", e);
                runOnUiThread(() -> setScanStatus("Error: " + e.getMessage(), true));
            }
        });
    }

    private void setScanStatus(String text, boolean error) {
        tvScanStatus.setText(text);
        tvScanStatus.setTextColor(getColor(error ? R.color.error : R.color.text_secondary));
    }

    private void renderProducts() {
        productsList.removeAllViews();
        if (cart.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Escanea un producto para empezar");
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(14), dp(24), dp(14), dp(24));
            productsList.addView(empty);
            summarySection.setVisibility(View.GONE);
            btnTransferir.setEnabled(false);
            return;
        }

        int totalPiezas = 0;
        for (int i = 0; i < cart.size(); i++) {
            CartItem p = cart.get(i);
            totalPiezas += p.qty;
            final int idx = i;

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackgroundColor(p.forced ? 0xFFFFF3E0 : getColor(R.color.surface));

            TextView tvQty = new TextView(this);
            tvQty.setText(String.valueOf(p.qty));
            tvQty.setTypeface(null, android.graphics.Typeface.BOLD);
            tvQty.setTextColor(getColor(R.color.card_purple));
            tvQty.setTextSize(16);
            tvQty.setLayoutParams(new LinearLayout.LayoutParams(dp(36), LinearLayout.LayoutParams.WRAP_CONTENT));
            tvQty.setGravity(Gravity.CENTER);

            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            info.setPadding(dp(6), 0, dp(6), 0);

            TextView tvName = new TextView(this);
            tvName.setText(p.name + (p.forced ? "  FORZADO" : ""));
            tvName.setTypeface(null, android.graphics.Typeface.BOLD);
            tvName.setTextSize(13);
            info.addView(tvName);

            TextView tvCode = new TextView(this);
            tvCode.setText(p.codigo);
            tvCode.setTextSize(11);
            tvCode.setTextColor(getColor(R.color.text_secondary));
            info.addView(tvCode);

            TextView tvStock = new TextView(this);
            tvStock.setText("Stock Terex3: " + p.sourceStock);
            tvStock.setTextSize(11);
            tvStock.setTextColor(p.forced ? 0xFFE65100 : getColor(R.color.card_purple));
            info.addView(tvStock);

            TextView tvRemove = new TextView(this);
            tvRemove.setText("✕");
            tvRemove.setTextColor(getColor(R.color.error));
            tvRemove.setTextSize(18);
            tvRemove.setPadding(dp(8), 0, dp(8), 0);
            tvRemove.setOnClickListener(v -> {
                cart.remove(idx);
                renderProducts();
            });

            row.addView(tvQty);
            row.addView(info);
            row.addView(tvRemove);
            productsList.addView(row);

            if (i < cart.size() - 1) {
                View divider = new View(this);
                divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
                divider.setBackgroundColor(0xFFEEEEEE);
                productsList.addView(divider);
            }
        }

        tvTotalSkus.setText(String.valueOf(cart.size()));
        tvTotalPiezas.setText(String.valueOf(totalPiezas));
        summarySection.setVisibility(View.VISIBLE);
        btnTransferir.setEnabled(true);
    }

    private void doTransfer() {
        btnTransferir.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        String target = targetBranch();
        String targetLabel = BRANCH_LABELS[spinnerTarget.getSelectedItemPosition()];
        List<CartItem> snapshot = new ArrayList<>(cart);

        bgExecutor.execute(() -> {
            int transferred = 0;
            int totalQty = 0;
            List<String> details = new ArrayList<>();
            try {
                for (CartItem p : snapshot) {
                    Map<String, String> params = new HashMap<>();
                    params.put("select", "barcode,name,estilo," + SOURCE_BRANCH + "," + target);
                    params.put("barcode", "eq." + p.codigo);
                    JSONArray rows = supa.select("inventario1", params);
                    if (rows.length() == 0) continue;
                    JSONObject row = rows.getJSONObject(0);
                    int sourceStock = row.optInt(SOURCE_BRANCH, 0);
                    int targetStock = row.optInt(target, 0);
                    int actualQty = Math.min(p.qty, sourceStock);
                    if (actualQty <= 0) continue;

                    Map<String, String> filt = new HashMap<>();
                    filt.put("barcode", "eq." + p.codigo);
                    JSONObject body = new JSONObject();
                    body.put(SOURCE_BRANCH, sourceStock - actualQty);
                    body.put(target, targetStock + actualQty);
                    supa.update("inventario1", filt, body);

                    transferred++;
                    totalQty += actualQty;
                    details.add(row.optString("name", p.codigo) + " x" + actualQty);
                }

                try {
                    JSONObject log = new JSONObject();
                    log.put("source", SOURCE_BRANCH);
                    log.put("target", target);
                    log.put("item_count", transferred);
                    log.put("total_qty", totalQty);
                    List<String> logged = details.size() > 20 ? details.subList(0, 20) : details;
                    log.put("details", TextUtils.join(", ", logged));
                    log.put("created_by", "app3");
                    supa.insert("transferencias", log);
                } catch (Exception e) {
                    Log.e(TAG, "transferencias log insert failed", e);
                }

                int finalTransferred = transferred;
                int finalTotalQty = totalQty;
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    btnTransferir.setEnabled(true);
                    showSuccess(targetLabel, finalTransferred, finalTotalQty);
                });
            } catch (Exception e) {
                Log.e(TAG, "doTransfer failed", e);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    btnTransferir.setEnabled(true);
                    new AlertDialog.Builder(this)
                            .setTitle("Error")
                            .setMessage(e.getMessage())
                            .setPositiveButton("OK", null)
                            .show();
                });
            }
        });
    }

    private void showSuccess(String targetLabel, int transferred, int totalQty) {
        new AlertDialog.Builder(this)
                .setTitle("✅ Transferencia Completada")
                .setMessage("Sucursal 3 → " + targetLabel + "\n" + transferred + " productos, " + totalQty + " piezas")
                .setPositiveButton("Nueva Transferencia", (d, w) -> {
                    cart.clear();
                    renderProducts();
                    loadHistory();
                })
                .setCancelable(false)
                .show();
    }

    private void loadHistory() {
        historyList.removeAllViews();
        bgExecutor.execute(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("select", "*");
                params.put("order", "created_at.desc");
                params.put("limit", "10");
                JSONArray data = supa.select("transferencias", params);
                runOnUiThread(() -> renderHistory(data));
            } catch (Exception e) {
                Log.e(TAG, "loadHistory failed", e);
            }
        });
    }

    private void renderHistory(JSONArray data) {
        historyList.removeAllViews();
        if (data.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("Sin transferencias recientes");
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(14), dp(14), dp(14), dp(14));
            historyList.addView(empty);
            return;
        }
        Map<String, String> shortLabels = new HashMap<>();
        shortLabels.put("terex1", "S1");
        shortLabels.put("terex2", "S2");
        shortLabels.put("terex3", "S3");

        for (int i = 0; i < data.length(); i++) {
            JSONObject t = data.optJSONObject(i);
            if (t == null) continue;
            String src = t.optString("source", "");
            String tgt = t.optString("target", "");
            String dir = shortLabels.getOrDefault(src, src) + " → " + shortLabels.getOrDefault(tgt, tgt);

            TextView tv = new TextView(this);
            tv.setText(dir + "  " + t.optInt("total_qty", 0) + " pzas (" + t.optInt("item_count", 0)
                    + " SKUs)  " + t.optString("created_at", ""));
            tv.setTextSize(12);
            tv.setPadding(dp(14), dp(10), dp(14), dp(10));
            historyList.addView(tv);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
