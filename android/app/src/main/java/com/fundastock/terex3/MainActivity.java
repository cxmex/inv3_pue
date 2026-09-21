package com.fundastock.terex3;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Core "Nota" sales screen — native translation of nota1.html + the
 * /api/search_barcode and /api/save logic in app.py. Talks to Supabase
 * directly with the anon key (see Config.java for the RLS caveat).
 */
public class MainActivity extends AppCompatActivity implements CartAdapter.Listener {

    private static final String TAG = "MainActivity";

    private final List<CartItem> cart = new ArrayList<>();
    private CartAdapter adapter;
    private final SupabaseClient supa = new SupabaseClient();
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean saving = new AtomicBoolean(false);
    private final Map<String, Long> recentSaleKeys = new HashMap<>();

    private TextInputEditText etBarcode;
    private TextView tvStatus, tvTotalQty, tvDiscountBadge, tvSubtotal, tvDiscount, tvTotal, tvSaveMsg;
    private View rowDiscount, progress;
    private RadioGroup rgPayment;
    private MaterialButton btnGuardar;
    private RecyclerView rvCart;

    private final ActivityResultLauncher<Intent> scannerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    String barcode = result.getData().getStringExtra(ScannerActivity.EXTRA_BARCODE);
                    if (barcode != null) {
                        etBarcode.setText(barcode);
                        searchBarcode(barcode);
                    }
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        etBarcode = findViewById(R.id.etBarcode);
        tvStatus = findViewById(R.id.tvStatus);
        tvTotalQty = findViewById(R.id.tvTotalQty);
        tvDiscountBadge = findViewById(R.id.tvDiscountBadge);
        tvSubtotal = findViewById(R.id.tvSubtotal);
        tvDiscount = findViewById(R.id.tvDiscount);
        tvTotal = findViewById(R.id.tvTotal);
        tvSaveMsg = findViewById(R.id.tvSaveMsg);
        rowDiscount = findViewById(R.id.rowDiscount);
        progress = findViewById(R.id.progress);
        rgPayment = findViewById(R.id.rgPayment);
        btnGuardar = findViewById(R.id.btnGuardar);
        rvCart = findViewById(R.id.rvCart);

        MaterialButton btnBuscar = findViewById(R.id.btnBuscar);
        MaterialButton btnScan = findViewById(R.id.btnScan);

        adapter = new CartAdapter(cart, this);
        rvCart.setLayoutManager(new LinearLayoutManager(this));
        rvCart.setAdapter(adapter);

        btnBuscar.setOnClickListener(v -> submitBarcode());
        etBarcode.setOnEditorActionListener((v, actionId, event) -> {
            submitBarcode();
            return true;
        });
        btnScan.setOnClickListener(v -> scannerLauncher.launch(new Intent(this, ScannerActivity.class)));
        btnGuardar.setOnClickListener(v -> saveTransaction());

        recalcTotals();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bgExecutor.shutdown();
    }

    private void submitBarcode() {
        String code = etBarcode.getText() == null ? "" : etBarcode.getText().toString().trim();
        if (TextUtils.isEmpty(code)) return;
        searchBarcode(code);
    }

    // ─── Barcode / customer lookup — mirrors /api/search_barcode ───────────
    private void searchBarcode(String rawBarcode) {
        String barcode = rawBarcode.trim();
        setStatus("Buscando…", true);
        bgExecutor.execute(() -> {
            try {
                CartItem item;
                if (barcode.toUpperCase(Locale.US).startsWith("CLIENTE:")) {
                    String phone = barcode.substring(barcode.indexOf(':') + 1).trim();
                    item = lookupCustomerCredit(phone);
                } else if (barcode.startsWith("9000") && barcode.length() == 13 && isAllDigits(barcode)) {
                    item = lookupCustomerByBarcode(barcode);
                } else if (barcode.startsWith("8000") && barcode.length() == 13) {
                    item = lookupLoyaltyCard(barcode);
                } else {
                    item = lookupProduct(barcode);
                }
                CartItem finalItem = item;
                runOnUiThread(() -> {
                    addToCart(finalItem);
                    etBarcode.setText("");
                    setStatus(finalItem.isLoyalty
                            ? "Cliente: " + (finalItem.customerEmail != null ? finalItem.customerEmail : finalItem.customerPhone)
                            : "Producto agregado", true);
                });
            } catch (Exception e) {
                Log.e(TAG, "searchBarcode error", e);
                runOnUiThread(() -> setStatus("Error: " + e.getMessage(), false));
            }
        });
    }

    private boolean isAllDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private CartItem lookupCustomerCredit(String phone) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("select", "id,reward_amount");
        params.put("phone_number", "eq." + phone);
        params.put("status", "eq.linked");
        JSONArray rewards = supa.select("qr_rewards", params);
        if (rewards.length() == 0) {
            throw new Exception("Cliente " + phone + " no tiene créditos disponibles");
        }
        double totalCredit = 0;
        JSONArray ids = new JSONArray();
        for (int i = 0; i < rewards.length(); i++) {
            JSONObject r = rewards.getJSONObject(i);
            totalCredit += r.optDouble("reward_amount", 0);
            ids.put(r.getInt("id"));
        }
        totalCredit = Math.round(totalCredit * 100) / 100.0;

        CartItem item = new CartItem();
        item.codigo = "CLIENTE:" + phone;
        item.name = "CREDITO CLIENTE (" + phone + ")";
        item.price = -totalCredit;
        item.originalPrice = -totalCredit;
        item.isLoyalty = true;
        item.isDiscountRow = true;
        item.customerPhone = phone;
        item.qrRewardIds = ids;
        return item;
    }

    private CartItem lookupCustomerByBarcode(String barcode) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("customer_barcode", "eq." + barcode);
        params.put("select", "id,phone_number");
        params.put("limit", "1");
        JSONArray rows = supa.select("customers", params);
        if (rows.length() == 0) {
            throw new Exception("Cliente con barcode " + barcode + " no encontrado");
        }
        String phone = rows.getJSONObject(0).getString("phone_number");
        return lookupCustomerCredit(phone);
    }

    private CartItem lookupLoyaltyCard(String barcode) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("select", "email,balance");
        params.put("barcode", "eq." + barcode);
        params.put("limit", "1");
        JSONArray rows = supa.select("loyalty_customers", params);
        if (rows.length() == 0) {
            throw new Exception("Cliente no encontrado");
        }
        JSONObject c = rows.getJSONObject(0);
        double balance = c.optDouble("balance", 0);

        CartItem item = new CartItem();
        item.codigo = barcode;
        item.name = "Saldo lealtad (" + c.optString("email", "") + ")";
        item.price = -balance;
        item.originalPrice = -balance;
        item.isLoyalty = true;
        item.isDiscountRow = true;
        item.customerEmail = c.optString("email", "");
        return item;
    }

    private CartItem lookupProduct(String barcode) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("select", "barcode,name,estilo,precio,terex1," + Config.INVENTORY_COL);
        params.put("barcode", "eq." + barcode);
        params.put("limit", "1");
        JSONArray rows = supa.select("inventario1", params);
        if (rows.length() == 0) {
            throw new Exception("Producto " + barcode + " no encontrado");
        }
        JSONObject p = rows.getJSONObject(0);

        CartItem item = new CartItem();
        item.codigo = barcode;
        item.name = p.optString("name", "");
        item.estilo = p.optString("estilo", "");
        item.price = p.optDouble("precio", 0);
        item.originalPrice = item.price;
        item.isLoyalty = false;
        return item;
    }

    // ─── Cart ────────────────────────────────────────────────────────────
    private void addToCart(CartItem item) {
        cart.add(item);
        adapter.notifyItemInserted(cart.size() - 1);
        recalcTotals();
    }

    @Override
    public void onQtyChanged(int position, int newQty) {
        if (position < 0 || position >= cart.size()) return;
        cart.get(position).qty = newQty;
        recalcTotals();
    }

    @Override
    public void onRemove(int position) {
        if (position < 0 || position >= cart.size()) return;
        cart.remove(position);
        adapter.notifyItemRemoved(position);
        recalcTotals();
    }

    /** Mirrors getDiscountPerPiece() in nota1.html. */
    private double getDiscountPerPiece(int totalQty) {
        if (totalQty > 100) return 10;
        if (totalQty > 50) return 5;
        return 0;
    }

    /**
     * Mirrors recalcTotal() in nota1.html — INCLUDING its quirk of applying the
     * per-piece bulk discount to every row's price, loyalty/credit rows included.
     * A large enough sale can zero out a loyalty-credit row's displayed value
     * (max(0, negativePrice - discount)). That's pre-existing behavior from the
     * web app, carried over here rather than silently "fixed".
     */
    private void recalcTotals() {
        int totalQty = 0;
        double subtotalBeforeDiscount = 0;
        for (CartItem it : cart) {
            totalQty += it.qty;
            subtotalBeforeDiscount += it.qty * it.originalPrice;
        }
        double discountPerPiece = getDiscountPerPiece(totalQty);
        double subtotalAfterDiscount = 0;
        for (CartItem it : cart) {
            double newPrice = discountPerPiece > 0
                    ? Math.max(0, Math.round(it.originalPrice - discountPerPiece))
                    : Math.round(it.originalPrice);
            it.price = newPrice;
            subtotalAfterDiscount += it.qty * newPrice;
        }
        double totalDiscountAmount = discountPerPiece > 0 ? totalQty * discountPerPiece : 0;

        tvTotalQty.setText(String.valueOf(totalQty));
        tvSubtotal.setText(fmt(subtotalBeforeDiscount));
        if (discountPerPiece > 0) {
            rowDiscount.setVisibility(View.VISIBLE);
            tvDiscount.setText("-" + fmt(totalDiscountAmount));
            tvDiscountBadge.setVisibility(View.VISIBLE);
            tvDiscountBadge.setText("-$" + (int) discountPerPiece + " por pieza!");
        } else {
            rowDiscount.setVisibility(View.GONE);
            tvDiscountBadge.setVisibility(View.GONE);
        }
        tvTotal.setText(fmt(subtotalAfterDiscount));
        btnGuardar.setEnabled(!cart.isEmpty());
        adapter.refreshPricesOnly();
    }

    private String fmt(double n) {
        return "$" + Math.round(n);
    }

    private void setStatus(String text, boolean ok) {
        tvStatus.setText(text);
        tvStatus.setTextColor(getColor(ok ? R.color.accent : R.color.error));
    }

    // ─── Save transaction — mirrors saveTransaction() in nota1.html + /api/save ─
    private void saveTransaction() {
        if (cart.isEmpty()) {
            tvSaveMsg.setText("Agrega productos primero");
            tvSaveMsg.setTextColor(getColor(R.color.error));
            return;
        }
        List<CartItem> zeroItems = new ArrayList<>();
        for (CartItem it : cart) {
            if (it.price <= 0) zeroItems.add(it);
        }
        if (!zeroItems.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < zeroItems.size(); i++) {
                if (i > 0) names.append(", ");
                CartItem it = zeroItems.get(i);
                names.append(!TextUtils.isEmpty(it.name) ? it.name : it.codigo);
            }
            new AlertDialog.Builder(this)
                    .setTitle("Confirmar")
                    .setMessage("⚠️ Hay mercancia con $0 en el precio:\n\n" + names
                            + "\n\n¿Estas seguro que la quieres vender gratis?")
                    .setPositiveButton("Sí", (d, w) -> doSaveTransaction())
                    .setNegativeButton("Cancelar", (d, w) -> {
                        tvSaveMsg.setText("Corrige los precios antes de guardar");
                        tvSaveMsg.setTextColor(getColor(R.color.error));
                    })
                    .show();
        } else {
            doSaveTransaction();
        }
    }

    private void doSaveTransaction() {
        if (!saving.compareAndSet(false, true)) return;
        btnGuardar.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        tvSaveMsg.setText("Guardando…");
        tvSaveMsg.setTextColor(getColor(R.color.text_secondary));

        String paymentMethod = rgPayment.getCheckedRadioButtonId() == R.id.rbEfectivo ? "efectivo" : "transferencia";

        // Snapshot the cart on the UI thread before handing off to the background thread
        List<CartItem> snapshot = new ArrayList<>(cart);
        String idemKey = System.currentTimeMillis() + "-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        bgExecutor.execute(() -> {
            try {
                long nowTs = System.currentTimeMillis();
                recentSaleKeys.entrySet().removeIf(e -> nowTs - e.getValue() > 60_000);
                if (recentSaleKeys.containsKey(idemKey)) {
                    throw new Exception("Venta duplicada detectada — intenta de nuevo");
                }
                recentSaleKeys.put(idemKey, nowTs);

                File pdfFile = processSale(snapshot, paymentMethod);
                runOnUiThread(() -> onSaveSuccess(pdfFile));
            } catch (Exception e) {
                Log.e(TAG, "saveTransaction error", e);
                runOnUiThread(() -> onSaveError(e.getMessage()));
            }
        });
    }

    /** Runs on bgExecutor. Mirrors api_save() in app.py end to end. */
    private File processSale(List<CartItem> products, String paymentMethod) throws Exception {
        int nextOrderId = getNextOrderId();

        TimeZone mx = TimeZone.getTimeZone("America/Mexico_City");
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
        dateFmt.setTimeZone(mx);
        timeFmt.setTimeZone(mx);
        Date now = new Date();
        String fecha = dateFmt.format(now);
        String hora = timeFmt.format(now);

        List<CartItem> ticketItems = new ArrayList<>();
        double total = 0;

        for (CartItem p : products) {
            if (p.codigo.toUpperCase(Locale.US).startsWith("CLIENTE:")) {
                processClienteRedemption(p, nextOrderId);
                ticketItems.add(p);
                total += p.subtotal();
                continue;
            }
            if (p.codigo.startsWith("8000") && p.codigo.length() == 13) {
                processLoyaltyDeduction(p, nextOrderId, fecha, hora);
                ticketItems.add(p);
                total += p.subtotal();
                continue;
            }

            // Regular product
            Map<String, String> params = new HashMap<>();
            params.put("select", "modelo,modelo_id,estilo,estilo_id," + Config.INVENTORY_COL + ",precio");
            params.put("barcode", "eq." + p.codigo);
            params.put("limit", "1");
            JSONArray invRows = supa.select("inventario1", params);
            if (invRows.length() == 0) {
                throw new Exception("Producto con barcode " + p.codigo + " no existe en inventario1");
            }
            JSONObject inv = invRows.getJSONObject(0);

            // $0 price guard — never sell at $0 (matches api_save's fallback chain)
            double salePrice = p.price;
            if (salePrice <= 0) {
                double catalogPrice = inv.optDouble("precio", 0);
                if (catalogPrice > 0) {
                    salePrice = catalogPrice;
                } else {
                    salePrice = 90;
                    try {
                        Map<String, String> priceFilter = new HashMap<>();
                        priceFilter.put("barcode", "eq." + p.codigo);
                        JSONObject priceBody = new JSONObject();
                        priceBody.put("precio", 90);
                        supa.update("inventario1", priceFilter, priceBody);
                    } catch (Exception ignored) {}
                }
            }

            JSONObject record = new JSONObject();
            record.put("qty", p.qty);
            record.put("name", p.name);
            record.put("name_id", p.codigo);
            record.put("price", salePrice);
            record.put("fecha", fecha);
            record.put("hora", hora);
            record.put("order_id", nextOrderId);
            record.put("modelo", inv.optString("modelo", ""));
            record.put("modelo_id", jsonField(inv, "modelo_id"));
            record.put("estilo", inv.optString("estilo", ""));
            record.put("estilo_id", jsonField(inv, "estilo_id"));
            record.put("payment_method", paymentMethod);
            supa.insert(Config.VENTAS_TABLE, record);

            int currentQty = inv.optInt(Config.INVENTORY_COL, 0);
            int newQty = currentQty - p.qty;
            Map<String, String> invFilter = new HashMap<>();
            invFilter.put("barcode", "eq." + p.codigo);
            JSONObject invBody = new JSONObject();
            invBody.put(Config.INVENTORY_COL, newQty);
            supa.update("inventario1", invFilter, invBody);

            CartItem ticketItem = new CartItem();
            ticketItem.qty = p.qty;
            ticketItem.name = p.name;
            ticketItem.price = salePrice;
            ticketItems.add(ticketItem);
            total += p.qty * salePrice;
        }

        // Cash register — only for cash sales
        if ("efectivo".equals(paymentMethod)) {
            try {
                double currentBalance = getCurrentBalance();
                double newBalance = currentBalance + total;
                JSONObject payload = new JSONObject();
                payload.put("nombre", "Venta #" + nextOrderId + " [terex3]");
                payload.put("tipo", "credito");
                payload.put("amount", total);
                payload.put("balance", newBalance);
                payload.put("order_id", nextOrderId);
                supa.insert(Config.CONTEO_EFECTIVO_TABLE, payload);
            } catch (Exception e) {
                Log.e(TAG, "cash register entry failed", e);
            }
        }

        String redemptionToken = generateRedemptionToken();
        try {
            JSONObject tokenPayload = new JSONObject();
            tokenPayload.put("order_id", nextOrderId);
            tokenPayload.put("token", redemptionToken);
            tokenPayload.put("total", total);
            tokenPayload.put("store", Config.STORE_ID);
            tokenPayload.put("used", false);
            supa.insert("redemption_tokens", tokenPayload);
        } catch (Exception e) {
            Log.e(TAG, "store redemption token failed", e);
        }
        try {
            double rewardAmount = Math.round(total * 0.01 * 100) / 100.0;
            JSONObject rewardPayload = new JSONObject();
            rewardPayload.put("qr_token", redemptionToken);
            rewardPayload.put("order_id", nextOrderId);
            rewardPayload.put("purchase_amount", total);
            rewardPayload.put("reward_amount", rewardAmount);
            rewardPayload.put("status", "pending");
            supa.insert("qr_rewards", rewardPayload);
        } catch (Exception e) {
            Log.e(TAG, "store qr reward failed", e);
        }

        File pdfFile = PdfReceiptBuilder.build(this, ticketItems, total, nextOrderId, redemptionToken);

        try {
            byte[] pdfBytes = Files.readAllBytes(pdfFile.toPath());
            supa.uploadToStorage("tickets", nextOrderId + ".pdf", pdfBytes, "application/pdf");
        } catch (Exception e) {
            Log.e(TAG, "ticket upload failed", e);
        }

        return pdfFile;
    }

    private Object jsonField(JSONObject obj, String key) {
        if (!obj.has(key) || obj.isNull(key)) return JSONObject.NULL;
        return obj.opt(key);
    }

    private int getNextOrderId() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("select", "order_id");
        params.put("order", "order_id.desc");
        params.put("limit", "1");
        JSONArray rows = supa.select(Config.VENTAS_TABLE, params);
        if (rows.length() > 0) {
            return rows.getJSONObject(0).optInt("order_id", 0) + 1;
        }
        return 1;
    }

    private double getCurrentBalance() throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("order", "created_at.desc");
        params.put("limit", "1");
        JSONArray rows = supa.select(Config.CONTEO_EFECTIVO_TABLE, params);
        if (rows.length() > 0) {
            return rows.getJSONObject(0).optDouble("balance", 0);
        }
        return 0;
    }

    private String generateRedemptionToken() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private void processClienteRedemption(CartItem p, int orderId) {
        String phone = p.customerPhone;
        if (phone == null) return;
        try {
            String nowIso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date());
            Map<String, String> filt = new HashMap<>();
            filt.put("phone_number", "eq." + phone);
            filt.put("status", "eq.linked");
            JSONObject body = new JSONObject();
            body.put("status", "redeemed");
            body.put("redeemed_at", nowIso);
            body.put("redeemed_order_id", orderId);
            supa.update("qr_rewards", filt, body);
        } catch (Exception e) {
            Log.e(TAG, "redeem qr_rewards failed", e);
        }
    }

    private void processLoyaltyDeduction(CartItem p, int orderId, String fecha, String hora) {
        try {
            double amount = Math.abs(p.price);
            Map<String, String> params = new HashMap<>();
            params.put("select", "id,email,balance");
            params.put("barcode", "eq." + p.codigo);
            params.put("limit", "1");
            JSONArray rows = supa.select("loyalty_customers", params);
            if (rows.length() == 0) {
                return; // not found — skip, matches process_loyalty_deduction's non-fatal behavior
            }
            JSONObject customer = rows.getJSONObject(0);
            double currentBalance = customer.optDouble("balance", 0);
            double newBalance = Math.max(0, currentBalance - amount);
            int customerId = customer.getInt("id");

            Map<String, String> filt = new HashMap<>();
            filt.put("id", "eq." + customerId);
            JSONObject body = new JSONObject();
            body.put("balance", newBalance);
            supa.update("loyalty_customers", filt, body);

            JSONObject txn = new JSONObject();
            txn.put("customer_id", customerId);
            txn.put("barcode", p.codigo);
            txn.put("amount", -amount);
            txn.put("balance_after", newBalance);
            txn.put("order_id", orderId);
            txn.put("fecha", fecha);
            txn.put("hora", hora);
            txn.put("store", Config.STORE_ID);
            supa.insert("loyalty_transactions", txn);
        } catch (Exception e) {
            Log.e(TAG, "processLoyaltyDeduction failed", e);
        }
    }

    private void onSaveSuccess(File pdfFile) {
        progress.setVisibility(View.GONE);
        btnGuardar.setEnabled(true);
        saving.set(false);
        cart.clear();
        adapter.notifyDataSetChanged();
        recalcTotals();
        tvSaveMsg.setText("Venta guardada y ticket generado ✅");
        tvSaveMsg.setTextColor(getColor(R.color.accent));
        openPdf(pdfFile);
    }

    private void onSaveError(String message) {
        progress.setVisibility(View.GONE);
        btnGuardar.setEnabled(true);
        saving.set(false);
        tvSaveMsg.setText("Error: " + message);
        tvSaveMsg.setTextColor(getColor(R.color.error));
    }

    private void openPdf(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this, "com.fundastock.terex3.fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/pdf");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(intent, "Abrir ticket"));
        } catch (Exception e) {
            Toast.makeText(this, "Ticket guardado: " + file.getName(), Toast.LENGTH_LONG).show();
        }
    }
}
