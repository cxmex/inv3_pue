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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Conteo Rápido por Estilo" — native translation of conteo_rapido_estilo.html
 * + /api/conteo-rapido-estilo/{nombres,estilos} and /api/conteo-por-estilo in
 * app.py. Talks to Supabase directly. Thumbnails (a purely cosmetic feature
 * requiring a batch of Storage list calls) are left out — the estilo name is
 * enough to count against.
 */
public class ConteoEstiloActivity extends AppCompatActivity {

    private static final String TAG = "ConteoEstilo";

    private static class RankRow {
        String modelo;
        int rank;
    }

    private static class EstiloCount {
        int estiloId;
        int qty;
    }

    private final SupabaseClient supa = new SupabaseClient();
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final List<RankRow> allRows = new ArrayList<>();
    private final Map<Integer, String> estiloNombres = new HashMap<>();
    private final String sessionId = UUID.randomUUID().toString();
    private String currentModelo;

    private TextInputEditText etSearch;
    private LinearLayout rankingList, estiloRowsList, estiloPanel;
    private TextView tvPanelTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_conteo_estilo);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        etSearch = findViewById(R.id.etSearch);
        rankingList = findViewById(R.id.rankingList);
        estiloPanel = findViewById(R.id.estiloPanel);
        estiloRowsList = findViewById(R.id.estiloRowsList);
        tvPanelTitle = findViewById(R.id.tvPanelTitle);

        findViewById(R.id.btnClosePanel).setOnClickListener(v -> closePanel());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { filterAndRender(s.toString()); }
        });

        loadRanking();
        loadNombres();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bgExecutor.shutdown();
    }

    private void loadNombres() {
        bgExecutor.execute(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("select", "id,nombre");
                params.put("limit", "2000");
                JSONArray rows = supa.select("inventario_estilos", params);
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject r = rows.getJSONObject(i);
                    String nombre = r.optString("nombre", "");
                    if (!nombre.isEmpty()) estiloNombres.put(r.optInt("id"), nombre);
                }
            } catch (Exception e) {
                Log.e(TAG, "loadNombres failed", e);
            }
        });
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
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject r = rows.getJSONObject(i);
                    String m = r.optString("modelo", "").trim().toUpperCase(Locale.US);
                    if (m.isEmpty()) continue;
                    totals.merge(m, Math.max(0, r.optInt("terex3", 0)), Integer::sum);
                }
                List<String> ranked = new ArrayList<>(totals.keySet());
                ranked.sort((a, b) -> Integer.compare(totals.get(b), totals.get(a)));

                List<RankRow> result = new ArrayList<>();
                for (int i = 0; i < ranked.size(); i++) {
                    RankRow rr = new RankRow();
                    rr.modelo = ranked.get(i);
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
            row.setOnClickListener(v -> selectModelo(r.modelo));

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

            TextView tvArrow = new TextView(this);
            tvArrow.setText("›");
            tvArrow.setTextColor(0xFFCCCCCC);

            row.addView(tvRank);
            row.addView(tvModelo);
            row.addView(tvArrow);
            rankingList.addView(row);

            View divider = new View(this);
            divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
            divider.setBackgroundColor(0xFFEEEEEE);
            rankingList.addView(divider);
        }
    }

    private void selectModelo(String modelo) {
        currentModelo = modelo;
        etSearch.setText("");
        tvPanelTitle.setText(modelo);
        estiloPanel.setVisibility(View.VISIBLE);
        estiloRowsList.removeAllViews();
        TextView loading = new TextView(this);
        loading.setText("Cargando estilos…");
        loading.setTextColor(getColor(R.color.text_secondary));
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(0, dp(20), 0, dp(20));
        estiloRowsList.addView(loading);

        bgExecutor.execute(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("modelo", "eq." + modelo);
                params.put("terex3", "gt.0");
                params.put("select", "estilo_id,terex3");
                params.put("limit", "5000");
                JSONArray rows = supa.select("inventario1", params);

                Map<Integer, Integer> byEstilo = new HashMap<>();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject r = rows.getJSONObject(i);
                    int eid = r.optInt("estilo_id");
                    byEstilo.merge(eid, r.optInt("terex3", 0), Integer::sum);
                }
                List<EstiloCount> counts = new ArrayList<>();
                for (Map.Entry<Integer, Integer> e : byEstilo.entrySet()) {
                    EstiloCount ec = new EstiloCount();
                    ec.estiloId = e.getKey();
                    ec.qty = e.getValue();
                    counts.add(ec);
                }
                counts.sort((a, b) -> Integer.compare(b.qty, a.qty));

                runOnUiThread(() -> renderEstiloRows(counts));
            } catch (Exception e) {
                Log.e(TAG, "selectModelo failed", e);
                runOnUiThread(() -> {
                    estiloRowsList.removeAllViews();
                    TextView err = new TextView(this);
                    err.setText("Error: " + e.getMessage());
                    err.setTextColor(getColor(R.color.error));
                    estiloRowsList.addView(err);
                });
            }
        });
    }

    private void renderEstiloRows(List<EstiloCount> counts) {
        estiloRowsList.removeAllViews();
        if (counts.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Sin estilos con stock en T3");
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(20), 0, dp(20));
            estiloRowsList.addView(empty);
            return;
        }
        List<EditText> allInputs = new ArrayList<>();
        for (int i = 0; i < counts.size(); i++) {
            EstiloCount ec = counts.get(i);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(9), dp(14), dp(9));

            TextView tvRank = new TextView(this);
            tvRank.setText(String.valueOf(i + 1));
            tvRank.setTextColor(getColor(R.color.text_secondary));
            tvRank.setTextSize(11);
            tvRank.setLayoutParams(new LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView tvNombre = new TextView(this);
            tvNombre.setText(estiloNombres.getOrDefault(ec.estiloId, "—"));
            tvNombre.setTextSize(13);
            tvNombre.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            EditText etQty = new EditText(this);
            etQty.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            etQty.setGravity(Gravity.CENTER);
            etQty.setHint("—");
            etQty.setLayoutParams(new LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.WRAP_CONTENT));
            allInputs.add(etQty);

            MaterialButton btnOk = new MaterialButton(this);
            btnOk.setText(R.string.btn_ok_short);
            btnOk.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.card_red)));
            btnOk.setTextColor(0xFFFFFFFF);
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            btnLp.setMarginStart(dp(6));
            btnOk.setLayoutParams(btnLp);

            btnOk.setOnClickListener(v -> saveOne(ec.estiloId, etQty, btnOk, row, allInputs));
            etQty.setOnEditorActionListener((v, actionId, event) -> {
                saveOne(ec.estiloId, etQty, btnOk, row, allInputs);
                return true;
            });

            row.addView(tvRank);
            row.addView(tvNombre);
            row.addView(etQty);
            row.addView(btnOk);
            estiloRowsList.addView(row);

            View divider = new View(this);
            divider.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
            divider.setBackgroundColor(0xFFF0F0F0);
            estiloRowsList.addView(divider);
        }
        if (!allInputs.isEmpty()) allInputs.get(0).requestFocus();
    }

    private void saveOne(int estiloId, EditText input, MaterialButton btn, LinearLayout row, List<EditText> allInputs) {
        String raw = input.getText() == null ? "" : input.getText().toString().trim();
        int qty;
        try {
            qty = Integer.parseInt(raw);
            if (qty < 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            input.requestFocus();
            return;
        }

        btn.setText("…");
        btn.setEnabled(false);
        String modelo = currentModelo;

        bgExecutor.execute(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("select", "terex3");
                params.put("modelo", "eq." + modelo);
                params.put("estilo_id", "eq." + estiloId);
                params.put("limit", "5000");
                JSONArray stockRows = supa.select("inventario1", params);
                int qtySistema = 0;
                for (int i = 0; i < stockRows.length(); i++) {
                    qtySistema += stockRows.getJSONObject(i).optInt("terex3", 0);
                }

                JSONObject body = new JSONObject();
                body.put("sucursal", "terex3");
                body.put("session_id", sessionId);
                body.put("modelo", modelo);
                body.put("estilo_id", estiloId);
                body.put("qty_contada", qty);
                body.put("qty_sistema", qtySistema);
                body.put("error", qty - qtySistema);
                supa.insert("conteo_por_estilo", body);

                runOnUiThread(() -> {
                    btn.setText("✓");
                    btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
                    row.setAlpha(0.5f);
                    for (EditText next : allInputs) {
                        if (next != input && (next.getText() == null || next.getText().length() == 0)) {
                            next.requestFocus();
                            break;
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "saveOne failed", e);
                runOnUiThread(() -> {
                    btn.setText(R.string.btn_ok_short);
                    btn.setEnabled(true);
                });
            }
        });
    }

    private void closePanel() {
        estiloPanel.setVisibility(View.GONE);
        currentModelo = null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
