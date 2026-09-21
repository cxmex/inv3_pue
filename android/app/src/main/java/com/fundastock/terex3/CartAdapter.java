package com.fundastock.terex3;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.Locale;

public class CartAdapter extends RecyclerView.Adapter<CartAdapter.ViewHolder> {

    public interface Listener {
        void onQtyChanged(int position, int newQty);
        void onRemove(int position);
    }

    private final List<CartItem> items;
    private final Listener listener;

    public CartAdapter(List<CartItem> items, Listener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_cart, parent, false);
        return new ViewHolder(v);
    }

    private static final String PAYLOAD_PRICE = "price_subtotal";

    /** Refresh only the price/subtotal text of every row — avoids resetting the qty
     *  EditText (and its cursor) while the user may still be typing in it. */
    public void refreshPricesOnly() {
        notifyItemRangeChanged(0, items.size(), PAYLOAD_PRICE);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_PRICE)) {
            CartItem item = items.get(position);
            holder.tvPrice.setText(fmt(item.price));
            holder.tvSubtotal.setText(fmt(item.subtotal()));
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CartItem item = items.get(position);

        holder.tvName.setText(item.name);
        holder.tvCodigo.setText(item.codigo);
        holder.tvPrice.setText(fmt(item.price));
        holder.tvSubtotal.setText(fmt(item.subtotal()));

        // Detach previous watcher before rebinding a recycled view
        if (holder.qtyWatcher != null) {
            holder.etQty.removeTextChangedListener(holder.qtyWatcher);
        }
        holder.etQty.setText(String.valueOf(item.qty));
        holder.etQty.setEnabled(!item.isLoyalty);

        holder.qtyWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return;
                int qty;
                try { qty = Integer.parseInt(s.toString()); } catch (NumberFormatException e) { qty = 0; }
                if (qty < 1) qty = 1;
                listener.onQtyChanged(pos, qty);
            }
        };
        holder.etQty.addTextChangedListener(holder.qtyWatcher);

        holder.btnRemove.setOnClickListener(v -> {
            int pos = holder.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) listener.onRemove(pos);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private String fmt(double n) {
        return "$" + String.format(Locale.US, "%,.0f", n);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        EditText etQty;
        TextView tvName, tvCodigo, tvPrice, tvSubtotal;
        ImageButton btnRemove;
        TextWatcher qtyWatcher;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            etQty = itemView.findViewById(R.id.etQty);
            tvName = itemView.findViewById(R.id.tvName);
            tvCodigo = itemView.findViewById(R.id.tvCodigo);
            tvPrice = itemView.findViewById(R.id.tvPrice);
            tvSubtotal = itemView.findViewById(R.id.tvSubtotal);
            btnRemove = itemView.findViewById(R.id.btnRemove);
        }
    }
}
