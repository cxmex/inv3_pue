package com.fundastock.doublex1;

/**
 * Supabase connection — same project/credentials as the terex3 POS app.
 *
 * SUPABASE_ANON_KEY here MUST be the "anon" role key, never service_role.
 * It is embedded in the APK by design — Supabase Row Level Security policies
 * are what actually restrict this key, not secrecy of the key itself.
 */
public final class Config {
    private Config() {}

    public static final String SUPABASE_URL = "https://gbkhkbfbarsnpbdkxzii.supabase.co";
    public static final String SUPABASE_ANON_KEY =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imdia2hrYmZiYXJzbnBiZGt4emlpIiwicm9sZSI6ImFub24iLCJpYXQiOjE3MzQzODAzNzMsImV4cCI6MjA0OTk1NjM3M30.mcOcC2GVEu_wD3xNBzSCC3MwDck3CIdmz4D8adU-bpI";

    public static final String IMAGES_BUCKET = "images-colores";
}
