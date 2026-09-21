package com.fundastock.terex3;

/**
 * Supabase connection + store identity.
 *
 * SUPABASE_KEY here MUST be the "anon" role key (verify the JWT payload has
 * "role":"anon"), never the service_role key. It is embedded in the APK by
 * design — Supabase Row Level Security policies on the project are what
 * actually restrict what this key can do, not secrecy of the key itself.
 */
public final class Config {
    private Config() {}

    public static final String SUPABASE_URL = "https://gbkhkbfbarsnpbdkxzii.supabase.co";
    public static final String SUPABASE_ANON_KEY =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imdia2hrYmZiYXJzbnBiZGt4emlpIiwicm9sZSI6ImFub24iLCJpYXQiOjE3MzQzODAzNzMsImV4cCI6MjA0OTk1NjM3M30.mcOcC2GVEu_wD3xNBzSCC3MwDck3CIdmz4D8adU-bpI";

    public static final String STORE_ID = "terex3";
    public static final String INVENTORY_COL = "terex3";
    public static final String VENTAS_TABLE = "ventas_terex3";
    public static final String CONTEO_EFECTIVO_TABLE = "conteo_efectivo3";

    public static final String WHATSAPP_BUSINESS_NUMBER = "525642460019";
}
