package com.fundastock.terex3;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Conteo de Efectivo" — native translation of conteo_efectivo3.html +
 * /api/conteo3 (GET/POST) and /api/conteo3/transferencias in app.py.
 * Talks to Supabase directly against conteo_efectivo3 and ventas_terex3.
 */
public class ConteoEfectivoActivity extends AppCompatActivity {

    private static final String TAG = "ConteoEfectivo";
    private static final ZoneId MX_ZONE = ZoneId.of("America/Mexico_City");

    private final SupabaseClient supa = new SupabaseClient();
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final Handler clockHandler = new Handler(Looper.getMainLooper());

    private TextView tvCurrentTime, tvBalance, tvStatus, tvConteoInfo;
    private Spinner spinnerTipo;
    private TextInputEditText etNombre, etAmount;
    private MaterialButton btnGuardar;
    private LinearLayout movementsList;

    private final String[] tipoValues = {"credito", "debito", "conteo"};

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateCurrentTime();
            clockHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_conteo_efectivo);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tvCurrentTime = findViewById(R.id.tvCurrentTime);
        tvBalance = findViewById(R.id.tvBalance);
        tvStatus = findViewById(R.id.tvStatus);
        tvConteoInfo = findViewById(R.id.tvConteoInfo);
        spinnerTipo = findViewById(R.id.spinnerTipo);
        etNombre = findViewById(R.id.etNombre);
        etAmount = findViewById(R.id.etAmount);
        btnGuardar = findViewById(R.id.btnGuardar);
        movementsList = findViewById(R.id.movementsList);

        ArrayAdapter<String> tipoAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"💵 Ingreso (Crédito)", "💸 Gasto (Débito)", "📊 Conteo de Caja"});
        tipoAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerTipo.setAdapter(tipoAdapter);
        spinnerTipo.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                boolean isConteo = tipoValues[position].equals("conteo");
                tvConteoInfo.setVisibility(isConteo ? android.view.View.VISIBLE : android.view.View.GONE);
                etNombre.setHint(isConteo ? "Ej: Conteo de cierre del día" : getString(R.string.hint_descripcion));
                etAmount.setHint(isConteo ? "Monto contado físicamente" : getString(R.string.hint_monto));
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        btnGuardar.setOnClickListener(v -> saveConteo());

        clockHandler.post(clockTick);
        loadConteos();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bgExecutor.shutdown();
        clockHandler.removeCallbacks(clockTick);
    }

    private void updateCurrentTime() {
        SimpleDateFormat fmt = new SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy, HH:mm:ss", new Locale("es", "MX"));
        fmt.setTimeZone(TimeZone.getTimeZone("America/Mexico_City"));
        tvCurrentTime.setText("🕐 " + fmt.format(new Date()));
    }

    private String fmtMoney(double n) {
        return String.format(Locale.US, "$%.2f", n);
    }

    private void loadConteos() {
        bgExecutor.execute(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("order", "created_at.desc");
                params.put("limit", "100");
                JSONArray data = supa.select("conteo_efectivo3", params);

                LocalDate today = LocalDate.now(MX_ZONE);
                LocalDate yesterday = today.minusDays(1);

                Map<String, String> transParams = new HashMap<>();
                transParams.put("select", "order_id,qty,price,fecha,hora,cliente");
                transParams.put("payment_method", "eq.transferencia");
                transParams.put("fecha", "gte." + yesterday);
                transParams.put("order", "order_id.desc");
                transParams.put("limit", "2000");
                JSONArray transRows;
                try {
                    transRows = supa.select(Config.VENTAS_TABLE, transParams);
                } catch (Exception e) {
                    transRows = new JSONArray();
                }

                LinkedHashMap<Integer, JSONObject> orders = new LinkedHashMap<>();
                for (int i = 0; i < transRows.length(); i++) {
                    JSONObject r = transRows.getJSONObject(i);
                    if (r.isNull("order_id")) continue;
                    int oid = r.optInt("order_id");
                    JSONObject o = orders.get(oid);
                    if (o == null) {
                        o = new JSONObject();
                        o.put("order_id", oid);
                        o.put("amount", 0.0);
                        o.put("fecha", r.optString("fecha", ""));
                        o.put("hora", r.optString("hora", ""));
                        o.put("cliente", r.opt("cliente"));
                        orders.put(oid, o);
                    }
                    double amount = o.getDouble("amount") + r.optDouble("qty", 0) * r.optDouble("price", 0);
                    o.put("amount", amount);
                }

                JSONArray dataFinal = data;
                List<JSONObject> rows = new ArrayList<>();
                for (int i = 0; i < data.length(); i++) {
                    JSONObject entry = data.getJSONObject(i);
                    String createdAt = entry.optString("created_at", "");
                    String dateStr = createdAt.length() >= 10 ? createdAt.substring(0, 10) : "";
                    if (dateStr.isEmpty()) continue;
                    LocalDate entryDay = LocalDate.parse(dateStr);
                    if (entryDay.isBefore(yesterday)) continue;
                    JSONObject row = new JSONObject();
                    row.put("_type", "conteo");
                    row.put("_sortKey", createdAt.length() >= 19 ? createdAt.substring(0, 19) : createdAt);
                    row.put("_entry", entry);
                    rows.add(row);
                }
                for (JSONObject o : orders.values()) {
                    JSONObject row = new JSONObject();
                    row.put("_type", "transferencia");
                    row.put("_sortKey", o.optString("fecha", "") + "T" + o.optString("hora", ""));
                    row.put("_entry", o);
                    rows.add(row);
                }
                rows.sort(Comparator.comparing(r -> r.optString("_sortKey", "")));

                double currentBalance = data.length() > 0 ? data.getJSONObject(0).optDouble("balance", 0) : 0;

                List<JSONObject> finalRows = rows;
                runOnUiThread(() -> renderMovements(finalRows, currentBalance, dataFinal.length() == 0));
            } catch (Exception e) {
                Log.e(TAG, "loadConteos failed", e);
                runOnUiThread(() -> {
                    tvStatus.setText("Error al cargar datos");
                    tvStatus.setTextColor(getColor(R.color.error));
                });
            }
        });
    }

    private void renderMovements(List<JSONObject> rows, double currentBalance, boolean noData) {
        tvBalance.setText(fmtMoney(currentBalance));
        movementsList.removeAllViews();

        if (noData || rows.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(noData ? "No hay movimientos aún" : "No hay movimientos de ayer u hoy");
            empty.setTextColor(getColor(R.color.text_secondary));
            empty.setPadding(dp(14), dp(20), dp(14), dp(20));
            empty.setGravity(android.view.Gravity.CENTER);
            movementsList.addView(empty);
            return;
        }

        for (JSONObject row : rows) {
            JSONObject entry = row.optJSONObject("_entry");
            if (entry == null) continue;
            boolean isTransfer = "transferencia".equals(row.optString("_type"));

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(10), dp(14), dp(10));
            card.setBackgroundColor(isTransfer ? 0xFFF5F3FB : getColor(R.color.surface));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(6);
            card.setLayoutParams(lp);

            TextView tvHeader = new TextView(this);
            String badge = isTransfer ? "💳 TRANSFERENCIA" : tipoBadge(entry.optString("tipo", ""));
            String when = formatDateTime(row.optString("_sortKey", ""));
            tvHeader.setText(badge + "  ·  " + when);
            tvHeader.setTextSize(11);
            tvHeader.setTypeface(null, android.graphics.Typeface.BOLD);
            tvHeader.setTextColor(getColor(R.color.text_secondary));
            card.addView(tvHeader);

            TextView tvName = new TextView(this);
            tvName.setText(isTransfer
                    ? (entry.isNull("cliente") || TextUtils.isEmpty(entry.optString("cliente", "")) ? "Venta transferencia" : entry.optString("cliente"))
                    : entry.optString("nombre", ""));
            tvName.setTextSize(14);
            tvName.setTextColor(getColor(R.color.text_primary));
            card.addView(tvName);

            TextView tvAmounts = new TextView(this);
            if (isTransfer) {
                tvAmounts.setText(fmtMoney(entry.optDouble("amount", 0)) + "  ·  Order #" + entry.optInt("order_id"));
                tvAmounts.setTextColor(0xFF5E35B1);
            } else {
                String tipo = entry.optString("tipo", "");
                StringBuilder sb = new StringBuilder();
                if ("credito".equals(tipo)) sb.append("+").append(fmtMoney(entry.optDouble("amount", 0)));
                else if ("debito".equals(tipo)) sb.append("-").append(fmtMoney(entry.optDouble("amount", 0)));
                sb.append("  ·  Saldo: ").append(fmtMoney(entry.optDouble("balance", 0)));
                if (!entry.isNull("diferencia")) {
                    double diff = entry.optDouble("diferencia", 0);
                    sb.append("  ·  Dif: ").append(diff >= 0 ? "+" : "").append(fmtMoney(diff));
                }
                if (!entry.isNull("order_id")) sb.append("  ·  Order #").append(entry.optInt("order_id"));
                tvAmounts.setText(sb.toString());
                tvAmounts.setTextColor(getColor(R.color.text_secondary));
            }
            tvAmounts.setTextSize(12);
            card.addView(tvAmounts);

            movementsList.addView(card);
        }
    }

    private String tipoBadge(String tipo) {
        switch (tipo) {
            case "inicial": return "INICIAL";
            case "credito": return "💵 INGRESO";
            case "debito": return "💸 GASTO";
            case "conteo": return "📊 CONTEO";
            default: return tipo;
        }
    }

    private String formatDateTime(String sortKey) {
        // sortKey is "yyyy-MM-ddTHH:mm:ss" (already MX-local, no timezone conversion needed).
        if (sortKey.length() < 19) return sortKey;
        String datePart = sortKey.substring(0, 10);
        String timePart = sortKey.substring(11, 19);
        String[] ymd = datePart.split("-");
        if (ymd.length != 3) return sortKey;
        return ymd[2] + "/" + ymd[1] + "/" + ymd[0] + " " + timePart;
    }

    private void saveConteo() {
        int tipoIdx = spinnerTipo.getSelectedItemPosition();
        String tipo = tipoValues[tipoIdx];
        String nombre = etNombre.getText() == null ? "" : etNombre.getText().toString().trim();
        String amountRaw = etAmount.getText() == null ? "" : etAmount.getText().toString().trim();

        if (TextUtils.isEmpty(nombre)) {
            setStatus("Por favor ingresa una descripción", true);
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(amountRaw);
        } catch (NumberFormatException e) {
            setStatus("Por favor ingresa un monto válido", true);
            return;
        }
        if (amount <= 0) {
            setStatus("Por favor ingresa un monto válido", true);
            return;
        }

        btnGuardar.setEnabled(false);
        setStatus("Guardando...", false);

        double finalAmount = amount;
        bgExecutor.execute(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("order", "created_at.desc");
                params.put("limit", "1");
                JSONArray last = supa.select("conteo_efectivo3", params);
                double currentBalance = last.length() > 0 ? last.getJSONObject(0).optDouble("balance", 0) : 0;

                double newBalance;
                Double diferencia = null;
                switch (tipo) {
                    case "credito":
                        newBalance = currentBalance + finalAmount;
                        break;
                    case "debito":
                        newBalance = currentBalance - finalAmount;
                        break;
                    case "conteo":
                        diferencia = finalAmount - currentBalance;
                        newBalance = finalAmount;
                        break;
                    default:
                        throw new Exception("Tipo inválido");
                }

                JSONObject body = new JSONObject();
                body.put("nombre", nombre);
                body.put("tipo", tipo);
                body.put("amount", finalAmount);
                body.put("balance", newBalance);
                body.put("diferencia", diferencia == null ? JSONObject.NULL : diferencia);
                supa.insert("conteo_efectivo3", body);

                runOnUiThread(() -> {
                    btnGuardar.setEnabled(true);
                    setStatus("✅ Guardado exitosamente", false);
                    etNombre.setText("");
                    etAmount.setText("");
                    etNombre.requestFocus();
                    loadConteos();
                });
            } catch (Exception e) {
                Log.e(TAG, "saveConteo failed", e);
                runOnUiThread(() -> {
                    btnGuardar.setEnabled(true);
                    setStatus("❌ Error: " + e.getMessage(), true);
                });
            }
        });
    }

    private void setStatus(String text, boolean error) {
        tvStatus.setText(text);
        tvStatus.setTextColor(getColor(error ? R.color.error : R.color.accent));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
