package com.fundastock.terex3;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

/**
 * App home — native translation of index3.html's menu grid. Each card routes
 * to the native Activity for that feature, or shows "coming soon" for
 * features not ported yet (still available on the web app in the meantime).
 */
public class HomeActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        bindCard(R.id.cardNota, "🧾", R.string.menu_nota_title, R.string.menu_nota_sub,
                R.color.card_blue, MainActivity.class);
        bindCard(R.id.cardEntrada, "📦", R.string.menu_entrada_title, R.string.menu_entrada_sub,
                R.color.card_green, EntradaMercanciaActivity.class);
        bindCard(R.id.cardConteoEfectivo, "💰", R.string.menu_conteo_efectivo_title, R.string.menu_conteo_efectivo_sub,
                R.color.card_orange, ConteoEfectivoActivity.class);
        bindCard(R.id.cardTransferencias, "🏦", R.string.menu_transferencias_title, R.string.menu_transferencias_sub,
                R.color.card_purple, TransferenciasActivity.class);
        bindCard(R.id.cardInventarioCodigo, "🔍", R.string.menu_inventario_codigo_title, R.string.menu_inventario_codigo_sub,
                R.color.card_purple, InventoryCheckActivity.class);
        bindCard(R.id.cardCheckBarcode, "📱", R.string.menu_check_barcode_title, R.string.menu_check_barcode_sub,
                R.color.card_green, CheckBarcodeMobileActivity.class);
        bindCard(R.id.cardConteoPrevio, "📦", R.string.menu_conteo_previo_title, R.string.menu_conteo_previo_sub,
                R.color.card_orange, ConteoPrevioActivity.class);
        bindCard(R.id.cardInventarioModelo, "📊", R.string.menu_inventario_modelo_title, R.string.menu_inventario_modelo_sub,
                R.color.card_red, InventarioModeloActivity.class);
        bindCard(R.id.cardConteoEstilo, "🧮", R.string.menu_conteo_estilo_title, R.string.menu_conteo_estilo_sub,
                R.color.card_red, ConteoEstiloActivity.class);
    }

    private void bindCard(int cardId, String icon, int titleRes, int subtitleRes, int stripeColorRes,
                           Class<?> target) {
        View card = findViewById(cardId);
        ((TextView) card.findViewById(R.id.menuIcon)).setText(icon);
        ((TextView) card.findViewById(R.id.menuTitle)).setText(titleRes);
        ((TextView) card.findViewById(R.id.menuSubtitle)).setText(subtitleRes);
        card.findViewById(R.id.stripe).setBackgroundColor(getColor(stripeColorRes));
        card.setOnClickListener(v -> {
            if (target != null) {
                startActivity(new Intent(this, target));
            } else {
                Toast.makeText(this, R.string.menu_coming_soon, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
