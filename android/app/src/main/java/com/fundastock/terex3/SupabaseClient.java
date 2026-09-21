package com.fundastock.terex3;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Minimal PostgREST + Storage client for Supabase, mirroring the calls
 * app.py's supabase_request() makes via httpx. All methods are blocking —
 * call them from a background thread.
 */
public class SupabaseClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();

    /** GET /rest/v1/{table}?params... */
    public JSONArray select(String table, @Nullable Map<String, String> params) throws IOException, JSONException {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(Config.SUPABASE_URL + "/rest/v1/" + table).newBuilder();
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                urlBuilder.addQueryParameter(e.getKey(), e.getValue());
            }
        }
        Request request = baseHeaders(new Request.Builder())
                .url(urlBuilder.build())
                .get()
                .build();
        try (Response resp = http.newCall(request).execute()) {
            String body = resp.body() != null ? resp.body().string() : "[]";
            if (!resp.isSuccessful()) {
                throw new IOException("Supabase GET " + table + " failed: " + resp.code() + " " + body);
            }
            return new JSONArray(body.isEmpty() ? "[]" : body);
        }
    }

    /** POST /rest/v1/{table} — returns the inserted row(s). */
    public JSONArray insert(String table, JSONObject data) throws IOException, JSONException {
        RequestBody body = RequestBody.create(data.toString(), JSON);
        Request request = baseHeaders(new Request.Builder())
                .url(Config.SUPABASE_URL + "/rest/v1/" + table)
                .header("Prefer", "return=representation")
                .post(body)
                .build();
        try (Response resp = http.newCall(request).execute()) {
            String respBody = resp.body() != null ? resp.body().string() : "[]";
            if (!resp.isSuccessful()) {
                throw new IOException("Supabase POST " + table + " failed: " + resp.code() + " " + respBody);
            }
            return new JSONArray(respBody.isEmpty() ? "[]" : respBody);
        }
    }

    /** PATCH /rest/v1/{table}?filters... */
    public void update(String table, Map<String, String> filters, JSONObject data) throws IOException {
        HttpUrl.Builder urlBuilder = HttpUrl.parse(Config.SUPABASE_URL + "/rest/v1/" + table).newBuilder();
        for (Map.Entry<String, String> e : filters.entrySet()) {
            urlBuilder.addQueryParameter(e.getKey(), e.getValue());
        }
        RequestBody body = RequestBody.create(data.toString(), JSON);
        Request request = baseHeaders(new Request.Builder())
                .url(urlBuilder.build())
                .header("Prefer", "return=minimal")
                .patch(body)
                .build();
        try (Response resp = http.newCall(request).execute()) {
            if (!resp.isSuccessful()) {
                String respBody = resp.body() != null ? resp.body().string() : "";
                throw new IOException("Supabase PATCH " + table + " failed: " + resp.code() + " " + respBody);
            }
        }
    }

    /** POST /storage/v1/object/{bucket}/{path} — raw upload. */
    public void uploadToStorage(String bucket, String path, byte[] bytes, String contentType) throws IOException {
        RequestBody body = RequestBody.create(bytes, MediaType.parse(contentType));
        Request request = baseHeaders(new Request.Builder())
                .url(Config.SUPABASE_URL + "/storage/v1/object/" + bucket + "/" + path)
                .header("Content-Type", contentType)
                .post(body)
                .build();
        try (Response resp = http.newCall(request).execute()) {
            if (!resp.isSuccessful()) {
                String respBody = resp.body() != null ? resp.body().string() : "";
                throw new IOException("Storage upload failed: " + resp.code() + " " + respBody);
            }
        }
    }

    private Request.Builder baseHeaders(Request.Builder builder) {
        return builder
                .header("apikey", Config.SUPABASE_ANON_KEY)
                .header("Authorization", "Bearer " + Config.SUPABASE_ANON_KEY)
                .header("Content-Type", "application/json");
    }
}
