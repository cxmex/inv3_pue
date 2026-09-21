package com.fundastock.terex3;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Entrada de Mercancía" — native translation of entrada_mercancia_3.html +
 * the /entradamercancia3 (POST/GET recientes) logic and /api/conteo-previo/cajas
 * in app.py. Talks to Supabase directly.
 */
public class EntradaMercanciaActivity extends AppCompatActivity {

    private static final String TAG = "EntradaMercancia";

    private final SupabaseClient supa = new SupabaseClient();
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    private TextInputEditText etQty, etBarcode;
    private Spinner spinnerCaja;
    private TextView tvCajaPreview, tvMsg;
    private MaterialButton btnScan, btnRegistrar, btnLimpiar, btnRefresh;
    private View progress;
    private LinearLayout recentList;

    private final List<Integer> cajaNumeros = new ArrayList<>();
    private final List<JSONArray> cajaItems = new ArrayList<>();

    private final ActivityResultLauncher<Intent> scannerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String barcode = result.getData().getStringExtra(ScannerActivity.EXTRA_BARCODE);
                    if (barcode != null) etBarcode.setText(barcode);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_entrada_mercancia);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        etQty = findViewById(R.id.etQty);
        etBarcode = findViewById(R.id.etBarcode);
        spinnerCaja = findViewById(R.id.spinnerCaja);
        tvCajaPreview = findViewById(R.id.tvCajaPreview);
        tvMsg = findViewById(R.id.tvMsg);
        btnScan = findViewById(R.id.btnScan);
        btnRegistrar = findViewById(R.id.btnRegistrar);
        btnLimpiar = findViewById(R.id.btnLimpiar);
        btnRefresh = findViewById(R.id.btnRefresh);
        progress = findViewById(R.id.progress);
        recentList = findViewById(R.id.recentList);

        btnScan.setOnClickListener(v -> scannerLauncher.launch(new Intent(this, ScannerActivity.class)));
        btnRegistrar.setOnClickListener(v -> registrarEntrada());
        btnLimpiar.setOnClickListener(v -> clearForm());
        btnRefresh.setOnClickListener(v -> loadRecentEntries());

        spinnerCaja.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                showCajaPreview(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        loadRecentEntries();
        loadConteoCajas();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bgExecutor.shutdown();
    }

    private void clearForm() {
        etQty.setText("1");
        etBarcode.setText("");
        etBarcode.requestFocus();
    }

    private void showCajaPreview(int position) {
        if (position <= 0 || position - 1 >= cajaItems.size()) {
            tvCajaPreview.setVisibility(View.GONE);
            return;
        }
        JSONArray items = cajaItems.get(position - 1);
        Map<String, Integer> byEstilo = new LinkedHashMap<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String key = it.optString("estilo", "");
            if (TextUtils.isEmpty(key)) key = "(sin estilo)";
            int qty = it.optInt("qty", 0);
            byEstilo.merge(key, qty, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : byEstilo.entrySet()) {
            parts.add(e.getKey() + ": " + e.getValue() + " pzas");
        }
        tvCajaPreview.setText("📦 Contenido de la caja:\n" + TextUtils.join("  ·  ", parts));
        tvCajaPreview.setVisibility(View.VISIBLE);
    }

    private void loadConteoCajas() {
        bgExecutor.execute(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("select", "caja_numero,fecha,estilo,modelo,color,qty,reconciled,created_at");
                params.put("order", "created_at.desc");
                params.put("limit", "1000");
                JSONArray rows = supa.select("conteo_previo", params);

                // Group by caja_numero, matching list_conteo_cajas2()'s defaultdict grouping
                LinkedHashMap<Integer, JSONArray> itemsByCaja = new LinkedHashMap<>();
                Map<Integer, Integer> totalByCaja = new HashMap<>();
                Map<Integer, String> fechaByCaja = new HashMap<>();
                Map<Integer, Boolean> reconciledByCaja = new HashMap<>();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject r = rows.getJSONObject(i);
                    int caja = r.optInt("caja_numero");
                    itemsByCaja.computeIfAbsent(caja, k -> new JSONArray()).put(r);
                    totalByCaja.merge(caja, r.optInt("qty", 0), Integer::sum);
                    fechaByCaja.put(caja, r.optString("fecha", ""));
                    boolean reconciled = reconciledByCaja.getOrDefault(caja, true) && r.optBoolean("reconciled", false);
                    reconciledByCaja.put(caja, reconciled);
                }

                List<Integer> cajasSorted = new ArrayList<>(itemsByCaja.keySet());
                cajasSorted.sort((a, b) -> Integer.compare(b, a));

                List<String> labels = new ArrayList<>();
                labels.add(getString(R.string.caja_sin_asignar));
                List<Integer> numeros = new ArrayList<>();
                List<JSONArray> itemsList = new ArrayList<>();
                for (int caja : cajasSorted) {
                    JSONArray items = itemsByCaja.get(caja);
                    String status = Boolean.TRUE.equals(reconciledByCaja.get(caja)) ? "✅ VERIFICADO" : "⏳ PENDIENTE";
                    labels.add("CAJA " + caja + " — " + fechaByCaja.get(caja) + " · " + items.length()
                            + " líneas · " + totalByCaja.get(caja) + " pzas · " + status);
                    numeros.add(caja);
                    itemsList.add(items);
                }

                runOnUiThread(() -> {
                    cajaNumeros.clear();
                    cajaNumeros.addAll(numeros);
                    cajaItems.clear();
                    cajaItems.addAll(itemsList);
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                            android.R.layout.simple_spinner_item, labels);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spinnerCaja.setAdapter(adapter);
                });
            } catch (Exception e) {
                Log.e(TAG, "loadConteoCajas failed", e);
            }
        });
    }

    private void registrarEntrada() {
        String qtyRaw = etQty.getText() == null ? "" : etQty.getText().toString().trim();
        String barcode = etBarcode.getText() == null ? "" : etBarcode.getText().toString().trim();

        int qty;
        try {
            qty = Integer.parseInt(qtyRaw);
        } catch (NumberFormatException e) {
            showMsg("La cantidad debe ser mayor a 0", true);
            return;
        }
        if (qty <= 0) {
            showMsg("La cantidad debe ser mayor a 0", true);
            return;
        }
        if (TextUtils.isEmpty(barcode)) {
            showMsg("El código de barras es requerido", true);
            return;
        }
        long barcodeInt;
        try {
            barcodeInt = Long.parseLong(barcode);
        } catch (NumberFormatException e) {
            showMsg("El código de barras debe ser numérico", true);
            return;
        }

        Integer selectedCaja = null;
        int spinnerPos = spinnerCaja.getSelectedItemPosition();
        if (spinnerPos > 0 && spinnerPos - 1 < cajaNumeros.size()) {
            selectedCaja = cajaNumeros.get(spinnerPos - 1);
        }
        Integer finalSelectedCaja = selectedCaja;

        btnRegistrar.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        showMsg("Registrando…", false);

        bgExecutor.execute(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("select", "name,estilo_id,marca,terex3");
                params.put("barcode", "eq." + barcode);
                params.put("limit", "1");
                JSONArray productRows = supa.select("inventario1", params);
                JSONObject productInfo = productRows.length() > 0 ? productRows.getJSONObject(0) : null;
                int currentTerex3 = productInfo != null ? productInfo.optInt("terex3", 0) : 0;

                JSONObject entrada = new JSONObject();
                entrada.put("qty", qty);
                entrada.put("barcode", barcodeInt);
                if (productInfo != null) {
                    if (!TextUtils.isEmpty(productInfo.optString("name", ""))) {
                        entrada.put("estilo", productInfo.optString("name"));
                    }
                    if (!productInfo.isNull("estilo_id")) {
                        entrada.put("estilo_id", productInfo.opt("estilo_id"));
                    }
                }
                if (finalSelectedCaja != null) {
                    entrada.put("conteo_previo_caja", finalSelectedCaja);
                }

                try {
                    supa.insert("entrada_mercancia_3", entrada);
                } catch (Exception insertError) {
                    Log.w(TAG, "full insert failed, retrying minimal", insertError);
                    JSONObject minimal = new JSONObject();
                    minimal.put("qty", qty);
                    minimal.put("barcode", barcodeInt);
                    supa.insert("entrada_mercancia_3", minimal);
                }

                if (productInfo != null) {
                    try {
                        int newTerex3 = currentTerex3 + qty;
                        Map<String, String> filt = new HashMap<>();
                        filt.put("barcode", "eq." + barcodeInt);
                        JSONObject body = new JSONObject();
                        body.put("terex3", newTerex3);
                        supa.update("inventario1", filt, body);
                    } catch (Exception e) {
                        Log.e(TAG, "terex3 update failed", e);
                    }
                }

                String productName = productInfo != null
                        ? productInfo.optString("name", "Producto no identificado")
                        : "Producto no identificado";

                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    btnRegistrar.setEnabled(true);
                    showMsg("¡Éxito! Entrada registrada: " + qty + "x " + productName, false);
                    clearForm();
                    loadRecentEntries();
                });
            } catch (Exception e) {
                Log.e(TAG, "registrarEntrada failed", e);
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    btnRegistrar.setEnabled(true);
                    showMsg("Error: " + e.getMessage(), true);
                });
            }
        });
    }

    private void loadRecentEntries() {
        recentList.removeAllViews();
        TextView loading = new TextView(this);
        loading.setText("Cargando entradas recientes…");
        loading.setPadding(dp(14), dp(14), dp(14), dp(14));
        loading.setTextColor(getColor(R.color.text_secondary));
        recentList.addView(loading);

        bgExecutor.execute(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("select", "*");
                params.put("order", "created_at.desc");
                params.put("limit", "20");
                JSONArray entries = supa.select("entrada_mercancia_3", params);
                runOnUiThread(() -> renderRecentEntries(entries));
            } catch (Exception e) {
                Log.e(TAG, "loadRecentEntries failed", e);
                runOnUiThread(() -> {
                    recentList.removeAllViews();
                    TextView err = new TextView(this);
                    err.setText("Error al cargar entradas recientes");
                    err.setTextColor(getColor(R.color.error));
                    err.setPadding(dp(14), dp(14), dp(14), dp(14));
                    recentList.addView(err);
                });
            }
        });
    }

    private void renderRecentEntries(JSONArray entries) {
        recentList.removeAllViews();
        if (entries.length() == 0) {
            TextView empty = new TextView(this);
            empty.setText("No hay entradas registradas");
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setPadding(dp(14), dp(14), dp(14), dp(14));
            recentList.addView(empty);
            return;
        }
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(14), dp(10), dp(14), dp(10));
            row.setBackgroundColor(getColor(R.color.loyalty_row));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            row.setLayoutParams(lp);

            String name = entry.optString("estilo", entry.optString("name", "Producto no identificado"));
            TextView tvTop = new TextView(this);
            tvTop.setText("Qty: " + entry.optInt("qty", 0) + " — " + name);
            tvTop.setTypeface(null, android.graphics.Typeface.BOLD);
            tvTop.setTextColor(getColor(R.color.text_primary));
            row.addView(tvTop);

            TextView tvBottom = new TextView(this);
            tvBottom.setText("Código: " + entry.optString("barcode", "") + "  ·  " + entry.optString("created_at", ""));
            tvBottom.setTextSize(11);
            tvBottom.setTextColor(getColor(R.color.text_secondary));
            row.addView(tvBottom);

            recentList.addView(row);
        }
    }

    private void showMsg(String text, boolean error) {
        tvMsg.setText(text);
        tvMsg.setTextColor(getColor(error ? R.color.error : R.color.accent));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
