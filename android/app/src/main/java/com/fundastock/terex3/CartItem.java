package com.fundastock.terex3;

import org.json.JSONArray;

/** One line in the cart — mirrors the row objects built in nota1.html's addProductRow(). */
public class CartItem {
    public String codigo;
    public String name;
    public String estilo = "";
    public int qty = 1;
    public double price;         // current (possibly discounted) unit price
    public double originalPrice; // catalog price, discount is recalculated off this
    public boolean isLoyalty = false;
    public boolean isDiscountRow = false; // negative price row (loyalty credit)

    // Only set for loyalty / customer-credit rows:
    public String customerEmail;
    public String customerPhone;
    public JSONArray qrRewardIds;

    public double subtotal() {
        return qty * price;
    }
}
