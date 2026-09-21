package com.fundastock.terex3;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Inventario Rápido por Modelo" — native translation of
 * inventario_rapido_modelo.html + /api/inventario-rapido-modelo/ranking
 * and /api/conteo-por-modelo in app.py. Talks to Supabase directly.
 */
public class InventarioModeloActivity extends AppCompatActivity {

    private static final String TAG = "InventarioModelo";

    private static class RankRow {
        String modelo;
        int qty;
        int rank;
    }

    private final SupabaseClient supa = new SupabaseClient();
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final List<RankRow> allRows = new ArrayList<>();
    private final String sessionId = UUID.randomUUID().toString();

    private TextInputEditText etSearch;
    private LinearLayout rankingList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inventario_modelo);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        etSearch = findViewById(R.id.etSearch);
        rankingList = findViewById(R.id.rankingList);

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { filterAndRender(s.toString()); }
        });

        loadRanking();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bgExecutor.shutdown();
    }

    private void loadRanking() {
        rankingList.removeAllViews();
        TextView loading = new TextView(this);
        loading.setText("Cargando…");
        loading.setTextColor(getColor(R.color.text_secondary));
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(0, dp(30), 0, dp(30));
        rankingList.addView(loading);

        bgExecutor.execute(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("select", "modelo,terex3");
                params.put("terex3", "gt.0");
                params.put("limit", "10000");
                JSONArray rows = supa.select("inventario1", params);

                Map<String, Integer> totals = new HashMap<>();
                List<String> order = new ArrayList<>();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject r = rows.getJSONObject(i);
                    String m = r.optString("modelo", "").trim().toUpperCase(Locale.US);
                    if (m.isEmpty()) continue;
                    int qty = Math.max(0, r.optInt("terex3", 0));
                    if (!totals.containsKey(m)) order.add(m);
                    totals.merge(m, qty, Integer::sum);
                }
                List<String> ranked = new ArrayList<>(order);
                ranked.sort((a, b) -> Integer.compare(totals.get(b), totals.get(a)));

                List<RankRow> result = new ArrayList<>();
                for (int i = 0; i < ranked.size(); i++) {
                    RankRow rr = new RankRow();
                    rr.modelo = ranked.get(i);
                    rr.qty = totals.get(ranked.get(i));
                    rr.rank = i + 1;
                    result.add(rr);
                }

                runOnUiThread(() -> {
                    allRows.clear();
                    allRows.addAll(result);
                    renderList(allRows);
                });
            } catch (Exception e) {
                Log.e(TAG, "loadRanking failed", e);
                runOnUiThread(() -> {
                    rankingList.removeAllViews();
                    TextView err = new TextView(this);
                    err.setText("Error: " + e.getMessage());
                    err.setTextColor(getColor(R.color.error));
                    err.setPadding(dp(14), dp(20), dp(14), dp(20));
                    rankingList.addView(err);
                });
            }
        });
    }

    private void filterAndRender(String query) {
        String q = query.trim().toUpperCase(Locale.US);
        if (q.isEmpty()) {
            renderList(allRows);
            return;
        }
        List<RankRow> filtered = new ArrayList<>();
        for (RankRow r : allRows) {
            if (r.modelo.contains(q)) filtered.add(r);
        }
        renderList(filtered);
    }

    private void renderList(List<RankRow> rows) {
        rankingList.removeAllViews();
        if (rows.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Sin resultados");
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(20), 0, dp(20));
            rankingList.addView(empty);
            return;
        }
        for (RankRow r : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setClickable(true);
            row.setFocusable(true);
            row.setBackgroundResource(android.R.drawable.list_selector_background);
            row.setOnClickListener(v -> openDetail(r.modelo));

            TextView tvRank = new TextView(this);
            tvRank.setText(String.valueOf(r.rank));
            tvRank.setTextColor(getColor(R.color.text_secondary));
            tvRank.setTextSize(11);
            tvRank.setLayoutParams(new LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView tvModelo = new TextView(this);
            tvModelo.setText(r.modelo);
            tvModelo.setTypeface(null, android.graphics.Typeface.BOLD);
            tvModelo.setTextSize(14);
            tvModelo.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvQty = new TextView(this);
            tvQty.setText(String.valueOf(r.qty));
            tvQty.setTextColor(getColor(R.color.card_red));
            tvQty.setTypeface(null, android.graphics.Typeface.BOLD);

            row.addView(tvRank);
            row.addView(tvModelo);
            row.addView(tvQty);
            rankingList.addView(row);

            View divider = new View(this);
            divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
            divider.setBackgroundColor(0xFFEEEEEE);
            rankingList.addView(divider);
        }
    }

    private void openDetail(String modelo) {
        EditText input = new EditText(this);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        input.setHint(R.string.hint_cantidad_contada);
        input.setGravity(Gravity.CENTER);
        input.setTextSize(24);
        int pad = dp(16);
        input.setPadding(pad, pad, pad, pad);

        new AlertDialog.Builder(this)
                .setTitle(modelo)
                .setView(input)
                .setPositiveButton(R.string.btn_guardar_simple, (d, w) -> saveConteo(modelo, input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void saveConteo(String modelo, String qtyRaw) {
        int qty;
        try {
            qty = Integer.parseInt(qtyRaw.trim());
            if (qty < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Ingresa una cantidad válida", Toast.LENGTH_SHORT).show();
            return;
        }

        bgExecutor.execute(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("select", "terex3");
                params.put("modelo", "eq." + modelo);
                params.put("limit", "10000");
                JSONArray stockRows = supa.select("inventario1", params);
                int qtySistema = 0;
                for (int i = 0; i < stockRows.length(); i++) {
                    qtySistema += stockRows.getJSONObject(i).optInt("terex3", 0);
                }

                JSONObject body = new JSONObject();
                body.put("sucursal", "terex3");
                body.put("session_id", sessionId);
                body.put("modelo", modelo);
                body.put("qty_contada", qty);
                body.put("qty_sistema", qtySistema);
                body.put("error", qty - qtySistema);
                supa.insert("conteo_por_modelo", body);

                runOnUiThread(() -> Toast.makeText(this, "Guardado ✅", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                Log.e(TAG, "saveConteo failed", e);
                runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
