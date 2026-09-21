package com.fundastock.terex3;

import android.app.DatePickerDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Conteo Previo de Mercancía" — native translation of conteo_previo.html +
 * /api/conteo-previo (POST/cajas/reconcile/mark-reconciled/foto) in app.py.
 * Talks to Supabase directly against conteo_previo, entrada_mercancia(_3)
 * and the barcode-photos storage bucket.
 */
public class ConteoPrevioActivity extends AppCompatActivity {

    private static final String TAG = "ConteoPrevio";
    private static final ZoneId MX_ZONE = ZoneId.of("America/Mexico_City");
    private static final String[] COLORS = {
            "NEGRO", "ROSA", "PISTACHE", "AZUL", "BLANCO", "MORADO", "TRANSPARENTE",
            "HUMO", "VERDE", "ROJO", "NARANJA", "AMARILLO", "GRIS", "BEIGE", "LILA", "TURQUESA"
    };

    private static class EstiloBlock {
        View blockView;
        EditText etNombre;
        LinearLayout rowsContainer;
        TextView tvSubtotal;
        ImageView imgThumb;
        MaterialButton btnFoto;
        File photoFile;
    }

    private final SupabaseClient supa = new SupabaseClient();
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final List<EstiloBlock> estiloBlocks = new ArrayList<>();
    private ArrayAdapter<String> modeloAdapter;
    private String fechaHoy;

    private TextView tabNueva, tabCajas;
    private View panelNueva;
    private LinearLayout panelCajas;
    private TextInputEditText etCajaNum;
    private TextView tvFecha, tvTotalDisplay;
    private LinearLayout estilosContainer;
    private MaterialButton btnAddEstilo, btnSubmit;

    private EstiloBlock pendingPhotoBlock;
    private File pendingPhotoFile;

    private final ActivityResultLauncher<Uri> cameraLauncher = registerForActivityResult(
            new ActivityResultContracts.TakePicture(), success -> {
                if (success && pendingPhotoBlock != null && pendingPhotoFile != null) {
                    pendingPhotoBlock.photoFile = pendingPhotoFile;
                    Bitmap bmp = decodeSampledBitmap(pendingPhotoFile, 200, 200);
                    if (bmp != null) {
                        pendingPhotoBlock.imgThumb.setImageBitmap(bmp);
                        pendingPhotoBlock.imgThumb.setVisibility(View.VISIBLE);
                    }
                    pendingPhotoBlock.btnFoto.setText("✅ Lista");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_conteo_previo);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        tabNueva = findViewById(R.id.tabNueva);
        tabCajas = findViewById(R.id.tabCajas);
        panelNueva = findViewById(R.id.panelNueva);
        panelCajas = findViewById(R.id.panelCajas);
        etCajaNum = findViewById(R.id.etCajaNum);
        tvFecha = findViewById(R.id.tvFecha);
        tvTotalDisplay = findViewById(R.id.tvTotalDisplay);
        estilosContainer = findViewById(R.id.estilosContainer);
        btnAddEstilo = findViewById(R.id.btnAddEstilo);
        btnSubmit = findViewById(R.id.btnSubmit);

        fechaHoy = LocalDate.now(MX_ZONE).toString();
        tvFecha.setText(fechaHoy);

        modeloAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, new ArrayList<>());
        loadModelos();

        tabNueva.setOnClickListener(v -> switchTab(true));
        tabCajas.setOnClickListener(v -> switchTab(false));
        btnAddEstilo.setOnClickListener(v -> addEstilo());
        btnSubmit.setOnClickListener(v -> submitConteo());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bgExecutor.shutdown();
    }

    private void switchTab(boolean nueva) {
        panelNueva.setVisibility(nueva ? View.VISIBLE : View.GONE);
        panelCajas.setVisibility(nueva ? View.GONE : View.VISIBLE);
        tabNueva.setTextColor(getColor(nueva ? R.color.card_orange : R.color.text_secondary));
        tabCajas.setTextColor(getColor(nueva ? R.color.text_secondary : R.color.card_orange));
        if (!nueva) loadCajas();
    }

    private void loadModelos() {
        bgExecutor.execute(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("select", "modelo");
                params.put("order", "modelo.asc");
                params.put("limit", "500");
                JSONArray rows = supa.select("inventario_modelos", params);
                List<String> modelos = new ArrayList<>();
                for (int i = 0; i < rows.length(); i++) {
                    String m = rows.getJSONObject(i).optString("modelo", "");
                    if (!TextUtils.isEmpty(m) && !modelos.contains(m)) modelos.add(m);
                }
                runOnUiThread(() -> {
                    modeloAdapter.clear();
                    modeloAdapter.addAll(modelos);
                    modeloAdapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                Log.e(TAG, "loadModelos failed", e);
            }
        });
    }

    // ─── NUEVA CUENTA: estilo blocks ────────────────────────────────────
    private void addEstilo() {
        View block = LayoutInflater.from(this).inflate(R.layout.item_conteo_previo_estilo, estilosContainer, false);
        EstiloBlock eb = new EstiloBlock();
        eb.blockView = block;
        eb.etNombre = block.findViewById(R.id.etEstiloNombre);
        eb.rowsContainer = block.findViewById(R.id.rowsContainer);
        eb.tvSubtotal = block.findViewById(R.id.tvSubtotal);
        eb.imgThumb = block.findViewById(R.id.imgThumb);
        eb.btnFoto = block.findViewById(R.id.btnFoto);

        eb.btnFoto.setOnClickListener(v -> launchCameraFor(eb));
        block.findViewById(R.id.btnDelEstilo).setOnClickListener(v -> {
            estilosContainer.removeView(block);
            estiloBlocks.remove(eb);
            recalc();
        });
        MaterialButton btnAddRow = block.findViewById(R.id.btnAddRow);
        btnAddRow.setOnClickListener(v -> addRow(eb));

        estilosContainer.addView(block);
        estiloBlocks.add(eb);
        addRow(eb);
        addRow(eb);
        recalc();
    }

    private void addRow(EstiloBlock eb) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_conteo_previo_row, eb.rowsContainer, false);
        AutoCompleteTextView etModelo = row.findViewById(R.id.etModelo);
        AutoCompleteTextView etColor = row.findViewById(R.id.etColor);
        EditText etQty = row.findViewById(R.id.etQty);

        etModelo.setAdapter(modeloAdapter);
        ArrayAdapter<String> colorAdapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, COLORS);
        etColor.setAdapter(colorAdapter);

        etQty.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { recalc(); }
        });

        row.findViewById(R.id.btnDelRow).setOnClickListener(v -> {
            eb.rowsContainer.removeView(row);
            recalc();
        });

        eb.rowsContainer.addView(row);
    }

    private void recalc() {
        int grand = 0;
        for (EstiloBlock eb : estiloBlocks) {
            int sub = 0;
            for (int i = 0; i < eb.rowsContainer.getChildCount(); i++) {
                View row = eb.rowsContainer.getChildAt(i);
                EditText etQty = row.findViewById(R.id.etQty);
                sub += parseIntSafe(etQty.getText());
            }
            eb.tvSubtotal.setText("Subtotal: " + sub + " pzs");
            grand += sub;
        }
        tvTotalDisplay.setText(String.valueOf(grand));
    }

    private int parseIntSafe(CharSequence s) {
        if (s == null || s.length() == 0) return 0;
        try {
            return Integer.parseInt(s.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void launchCameraFor(EstiloBlock eb) {
        try {
            File dir = new File(getCacheDir(), "photos");
            if (!dir.exists()) dir.mkdirs();
            pendingPhotoFile = new File(dir, "estilo_" + System.currentTimeMillis() + ".jpg");
            pendingPhotoBlock = eb;
            Uri uri = FileProvider.getUriForFile(this, "com.fundastock.terex3.fileprovider", pendingPhotoFile);
            cameraLauncher.launch(uri);
        } catch (Exception e) {
            Log.e(TAG, "launchCameraFor failed", e);
        }
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

    // ─── SUBMIT ──────────────────────────────────────────────────────────
    private void submitConteo() {
        int cajaNumero = parseIntSafe(etCajaNum.getText());
        if (cajaNumero <= 0) {
            Toast.makeText(this, "Ingresa el número de caja", Toast.LENGTH_SHORT).show();
            etCajaNum.requestFocus();
            return;
        }

        List<JSONObject> items = new ArrayList<>();
        List<EstiloBlock> blocksSnapshot = new ArrayList<>(estiloBlocks);
        try {
            for (EstiloBlock eb : blocksSnapshot) {
                String estilo = eb.etNombre.getText() == null ? "" : eb.etNombre.getText().toString().trim().toUpperCase(Locale.US);
                for (int i = 0; i < eb.rowsContainer.getChildCount(); i++) {
                    View row = eb.rowsContainer.getChildAt(i);
                    AutoCompleteTextView etModelo = row.findViewById(R.id.etModelo);
                    AutoCompleteTextView etColor = row.findViewById(R.id.etColor);
                    EditText etQty = row.findViewById(R.id.etQty);
                    String modelo = etModelo.getText() == null ? "" : etModelo.getText().toString().trim().toUpperCase(Locale.US);
                    String color = etColor.getText() == null ? "" : etColor.getText().toString().trim().toUpperCase(Locale.US);
                    int qty = parseIntSafe(etQty.getText());
                    if (!TextUtils.isEmpty(modelo) && !TextUtils.isEmpty(color) && qty > 0) {
                        JSONObject item = new JSONObject();
                        item.put("estilo", estilo);
                        item.put("modelo", modelo);
                        item.put("color", color);
                        item.put("qty", qty);
                        items.add(item);
                    }
                }
            }
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            return;
        }

        if (items.isEmpty()) {
            Toast.makeText(this, "Agrega al menos un item con modelo, color y cantidad", Toast.LENGTH_SHORT).show();
            return;
        }

        btnSubmit.setEnabled(false);
        btnSubmit.setText("Guardando…");

        bgExecutor.execute(() -> {
            try {
                JSONArray rows = new JSONArray();
                int total = 0;
                // estilo -> modelo -> list of {color, qty}, insertion order preserved (mirrors OrderedDict)
                LinkedHashMap<String, LinkedHashMap<String, List<JSONObject>>> byEstilo = new LinkedHashMap<>();
                for (JSONObject item : items) {
                    JSONObject row = new JSONObject();
                    row.put("caja_numero", cajaNumero);
                    row.put("fecha", fechaHoy);
                    row.put("estilo", item.getString("estilo"));
                    row.put("modelo", item.getString("modelo"));
                    row.put("color", item.getString("color"));
                    row.put("qty", item.getInt("qty"));
                    row.put("notas", "");
                    rows.put(row);
                    total += item.getInt("qty");

                    String est = TextUtils.isEmpty(item.getString("estilo")) ? "(sin estilo)" : item.getString("estilo");
                    byEstilo.computeIfAbsent(est, k -> new LinkedHashMap<>())
                            .computeIfAbsent(item.getString("modelo"), k -> new ArrayList<>())
                            .add(item);
                }

                supa.insertBatch("conteo_previo", rows);

                StringBuilder receipt = new StringBuilder();
                receipt.append("📦 *CAJA ").append(cajaNumero).append("* — ").append(fechaHoy).append("\n\n");
                for (Map.Entry<String, LinkedHashMap<String, List<JSONObject>>> estEntry : byEstilo.entrySet()) {
                    receipt.append("▸ *").append(estEntry.getKey()).append("*\n");
                    for (Map.Entry<String, List<JSONObject>> modEntry : estEntry.getValue().entrySet()) {
                        receipt.append("  ").append(modEntry.getKey()).append("\n");
                        for (JSONObject it : modEntry.getValue()) {
                            receipt.append("    ").append(it.getString("color")).append(": ").append(it.getInt("qty")).append("\n");
                        }
                    }
                    receipt.append("\n");
                }
                receipt.append("TOTAL DE PZS .... ").append(total).append(" ✅");

                // Upload estilo photos (storage only, mirrors upload_conteo_foto2 — no table row)
                for (EstiloBlock eb : blocksSnapshot) {
                    String estiloName = eb.etNombre.getText() == null ? "" : eb.etNombre.getText().toString().trim();
                    if (eb.photoFile == null || !eb.photoFile.exists() || TextUtils.isEmpty(estiloName)) continue;
                    try {
                        byte[] bytes = Files.readAllBytes(eb.photoFile.toPath());
                        String nowStr = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
                        String uid = UUID.randomUUID().toString().substring(0, 8);
                        String safeEstilo = estiloName.toUpperCase(Locale.US).replace(" ", "_").replace("/", "-");
                        if (safeEstilo.length() > 40) safeEstilo = safeEstilo.substring(0, 40);
                        String storagePath = "conteo_previo/caja" + cajaNumero + "/" + safeEstilo + "_" + nowStr + "_" + uid + ".jpg";
                        supa.uploadToStorage("barcode-photos", storagePath, bytes, "image/jpeg");
                    } catch (Exception e) {
                        Log.e(TAG, "estilo photo upload failed", e);
                    }
                }

                int finalTotal = total;
                runOnUiThread(() -> {
                    btnSubmit.setEnabled(true);
                    btnSubmit.setText(R.string.btn_guardar_recibo);
                    showReceipt(receipt.toString(), finalTotal);
                    resetForm();
                });
            } catch (Exception e) {
                Log.e(TAG, "submitConteo failed", e);
                runOnUiThread(() -> {
                    btnSubmit.setEnabled(true);
                    btnSubmit.setText(R.string.btn_guardar_recibo);
                    new AlertDialog.Builder(this).setTitle("Error").setMessage(e.getMessage())
                            .setPositiveButton("OK", null).show();
                });
            }
        });
    }

    private void resetForm() {
        etCajaNum.setText("");
        estilosContainer.removeAllViews();
        estiloBlocks.clear();
        recalc();
    }

    private void showReceipt(String receipt, int total) {
        new AlertDialog.Builder(this)
                .setTitle("📋 Recibo Generado (" + total + " pzs)")
                .setMessage(receipt)
                .setPositiveButton("📋 Copiar para WhatsApp", (d, w) -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("recibo", receipt));
                    Toast.makeText(this, "Copiado ✅", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    // ─── CAJAS PENDIENTES ────────────────────────────────────────────────
    private void loadCajas() {
        panelCajas.setVisibility(View.VISIBLE);
        (panelCajas).removeAllViews();
        TextView loading = new TextView(this);
        loading.setText("Cargando…");
        loading.setTextColor(getColor(R.color.text_secondary));
        loading.setPadding(0, dp(20), 0, dp(20));
        (panelCajas).addView(loading);

        bgExecutor.execute(() -> {
            try {
                Map<String, String> params = new HashMap<>();
                params.put("select", "caja_numero,fecha,estilo,modelo,color,qty,reconciled,created_at");
                params.put("order", "created_at.desc");
                params.put("limit", "1000");
                JSONArray rows = supa.select("conteo_previo", params);

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

                runOnUiThread(() -> {
                    (panelCajas).removeAllViews();
                    if (cajasSorted.isEmpty()) {
                        TextView empty = new TextView(this);
                        empty.setText("No hay cajas registradas");
                        empty.setTextColor(getColor(R.color.text_secondary));
                        empty.setPadding(0, dp(20), 0, dp(20));
                        (panelCajas).addView(empty);
                        return;
                    }
                    for (int caja : cajasSorted) {
                        addCajaCard(caja, itemsByCaja.get(caja), totalByCaja.get(caja),
                                fechaByCaja.get(caja), Boolean.TRUE.equals(reconciledByCaja.get(caja)));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "loadCajas failed", e);
                runOnUiThread(() -> {
                    (panelCajas).removeAllViews();
                    TextView err = new TextView(this);
                    err.setText("Error: " + e.getMessage());
                    err.setTextColor(getColor(R.color.error));
                    (panelCajas).addView(err);
                });
            }
        });
    }

    private void addCajaCard(int cajaNumero, JSONArray items, int total, String fecha, boolean reconciled) {
        View card = LayoutInflater.from(this).inflate(R.layout.item_caja_card, panelCajas, false);
        TextView tvNum = card.findViewById(R.id.tvCajaNum);
        TextView tvMeta = card.findViewById(R.id.tvCajaMeta);
        TextView tvTotal = card.findViewById(R.id.tvCajaTotal);
        TextView tvBadge = card.findViewById(R.id.tvCajaBadge);
        View header = card.findViewById(R.id.cajaHeader);
        LinearLayout detail = card.findViewById(R.id.cajaDetail);

        tvNum.setText("CAJA " + cajaNumero);
        tvMeta.setText(fecha + " · " + items.length() + " líneas");
        tvTotal.setText(String.valueOf(total));
        tvBadge.setText(reconciled ? "VERIFICADO" : "PENDIENTE");
        tvBadge.setTextColor(reconciled ? getColor(R.color.accent) : getColor(R.color.stock_yellow));

        header.setOnClickListener(v -> {
            boolean visible = detail.getVisibility() == View.VISIBLE;
            if (!visible && detail.getChildCount() == 0) {
                buildCajaDetail(detail, cajaNumero, items, fecha, reconciled);
            }
            detail.setVisibility(visible ? View.GONE : View.VISIBLE);
        });

        (panelCajas).addView(card);
    }

    private void buildCajaDetail(LinearLayout detail, int cajaNumero, JSONArray items, String fecha, boolean reconciled) {
        LinkedHashMap<String, List<JSONObject>> byEstilo = new LinkedHashMap<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) continue;
            String est = it.optString("estilo", "");
            if (TextUtils.isEmpty(est)) est = "(sin estilo)";
            byEstilo.computeIfAbsent(est, k -> new ArrayList<>()).add(it);
        }
        for (Map.Entry<String, List<JSONObject>> e : byEstilo.entrySet()) {
            TextView tvEst = new TextView(this);
            tvEst.setText(e.getKey());
            tvEst.setTypeface(null, android.graphics.Typeface.BOLD);
            tvEst.setTextColor(getColor(R.color.primary));
            tvEst.setTextSize(12);
            tvEst.setPadding(0, dp(6), 0, dp(4));
            detail.addView(tvEst);
            for (JSONObject it : e.getValue()) {
                TextView tvRow = new TextView(this);
                tvRow.setText((it.optString("modelo", "") + " · " + it.optString("color", "")) + "   ×" + it.optInt("qty", 0));
                tvRow.setTextSize(12);
                tvRow.setPadding(dp(8), dp(3), dp(8), dp(3));
                detail.addView(tvRow);
            }
        }

        if (reconciled) return;

        LinearLayout recCard = new LinearLayout(this);
        recCard.setOrientation(LinearLayout.VERTICAL);
        recCard.setPadding(dp(12), dp(12), dp(12), dp(12));
        recCard.setBackgroundColor(getColor(R.color.background));
        LinearLayout.LayoutParams recLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        recLp.topMargin = dp(10);
        recCard.setLayoutParams(recLp);

        EditText etFrom = new EditText(this);
        etFrom.setHint(getString(R.string.hint_fecha_desde));
        etFrom.setText(fecha);
        etFrom.setFocusable(false);
        etFrom.setOnClickListener(v -> showDatePicker(etFrom));
        recCard.addView(etFrom);

        MaterialButton btnCompare = new MaterialButton(this);
        btnCompare.setText(R.string.btn_comparar_entradas);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.topMargin = dp(8);
        btnCompare.setLayoutParams(btnLp);
        recCard.addView(btnCompare);

        LinearLayout resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        recCard.addView(resultBox);

        btnCompare.setOnClickListener(v -> reconcile(cajaNumero, etFrom.getText().toString().trim(), resultBox));

        if (!reconciled) {
            MaterialButton btnDone = new MaterialButton(this);
            btnDone.setText(R.string.btn_marcar_verificado);
            btnDone.setBackgroundTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.accent)));
            LinearLayout.LayoutParams doneLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            doneLp.topMargin = dp(8);
            btnDone.setLayoutParams(doneLp);
            btnDone.setOnClickListener(v -> confirmMarkDone(cajaNumero));
            recCard.addView(btnDone);
        }

        detail.addView(recCard);
    }

    private void showDatePicker(EditText target) {
        Calendar cal = Calendar.getInstance();
        String current = target.getText().toString();
        if (current.matches("\\d{4}-\\d{2}-\\d{2}")) {
            String[] parts = current.split("-");
            cal.set(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) - 1, Integer.parseInt(parts[2]));
        }
        new DatePickerDialog(this, (view, year, month, day) ->
                target.setText(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day)),
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show();
    }

    private void reconcile(int cajaNumero, String fechaFrom, LinearLayout resultBox) {
        if (TextUtils.isEmpty(fechaFrom)) {
            Toast.makeText(this, "Selecciona la fecha", Toast.LENGTH_SHORT).show();
            return;
        }
        resultBox.removeAllViews();
        TextView loading = new TextView(this);
        loading.setText("Comparando…");
        loading.setTextColor(getColor(R.color.text_secondary));
        resultBox.addView(loading);

        bgExecutor.execute(() -> {
            try {
                Map<String, String> countedParams = new HashMap<>();
                countedParams.put("caja_numero", "eq." + cajaNumero);
                countedParams.put("select", "modelo,color,qty");
                countedParams.put("order", "modelo.asc");
                JSONArray counted = supa.select("conteo_previo", countedParams);
                int totalCounted = sumQty(counted);

                Map<String, String> e1Params = new HashMap<>();
                e1Params.put("created_at", "gte." + fechaFrom + "T00:00:00");
                e1Params.put("select", "estilo,qty");
                e1Params.put("limit", "2000");
                int totalEntered1;
                try {
                    totalEntered1 = sumQty(supa.select("entrada_mercancia", e1Params));
                } catch (Exception e) {
                    totalEntered1 = 0;
                }

                Map<String, String> e3Params = new HashMap<>();
                e3Params.put("created_at", "gte." + fechaFrom + "T00:00:00");
                e3Params.put("select", "estilo,qty");
                e3Params.put("limit", "2000");
                int totalEntered3;
                try {
                    totalEntered3 = sumQty(supa.select("entrada_mercancia_3", e3Params));
                } catch (Exception e) {
                    totalEntered3 = 0;
                }

                int totalEntered = totalEntered1 + totalEntered3;
                int diff = totalEntered - totalCounted;

                int finalTotalCounted = totalCounted;
                int finalTotalEntered1 = totalEntered1;
                int finalTotalEntered3 = totalEntered3;
                runOnUiThread(() -> {
                    resultBox.removeAllViews();
                    TextView tv = new TextView(this);
                    String verdict = diff == 0 ? "✅ Todo cuadra perfectamente"
                            : Math.abs(diff) <= 10 ? "⚠️ Diferencia pequeña de " + Math.abs(diff) + " pzs"
                            : "❌ Diferencia de " + Math.abs(diff) + " pzs — revisar";
                    tv.setText("Contado: " + finalTotalCounted
                            + "\nEntrado T1: " + finalTotalEntered1
                            + "\nEntrado T3: " + finalTotalEntered3
                            + "\nTotal Entrado: " + totalEntered
                            + "\nDiferencia: " + (diff > 0 ? "+" : "") + diff
                            + "\n\n" + verdict);
                    tv.setTextSize(12);
                    resultBox.addView(tv);
                });
            } catch (Exception e) {
                Log.e(TAG, "reconcile failed", e);
                runOnUiThread(() -> {
                    resultBox.removeAllViews();
                    TextView err = new TextView(this);
                    err.setText("Error: " + e.getMessage());
                    err.setTextColor(getColor(R.color.error));
                    resultBox.addView(err);
                });
            }
        });
    }

    private int sumQty(JSONArray rows) {
        int sum = 0;
        for (int i = 0; i < rows.length(); i++) {
            sum += rows.optJSONObject(i) != null ? rows.optJSONObject(i).optInt("qty", 0) : 0;
        }
        return sum;
    }

    private void confirmMarkDone(int cajaNumero) {
        new AlertDialog.Builder(this)
                .setTitle("¿Marcar CAJA " + cajaNumero + " como verificada?")
                .setPositiveButton("Sí", (d, w) -> markDone(cajaNumero))
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void markDone(int cajaNumero) {
        bgExecutor.execute(() -> {
            try {
                Map<String, String> filt = new HashMap<>();
                filt.put("caja_numero", "eq." + cajaNumero);
                JSONObject body = new JSONObject();
                body.put("reconciled", true);
                body.put("reconciled_at", java.time.LocalDateTime.now().toString());
                supa.update("conteo_previo", filt, body);
                runOnUiThread(this::loadCajas);
            } catch (Exception e) {
                Log.e(TAG, "markDone failed", e);
                runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
