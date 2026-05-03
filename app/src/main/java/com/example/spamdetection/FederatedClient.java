package com.example.spamdetection;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.UnknownHostException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class FederatedClient {
    private static final String TAG = "FederatedClient";
    public static final String SERVER_URL = "https://spam-fl-server.onrender.com"; 

    private static final String PREFS_NAME   = "fl_prefs";
    private static final String KEY_DEVICE_ID     = "device_id";
    private static final String KEY_MODEL_VERSION = "model_version";

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final Context context;
    private final OkHttpClient http;
    private final String deviceId;

    public interface UploadCallback {
        void onSuccess(String message);
        void onError(String error);
    }

    public interface DownloadCallback {
        void onNewModelAvailable(int newVersion);
        void onAlreadyUpToDate();
        void onError(String error);
    }

    public FederatedClient(Context context) {
        this.context  = context.getApplicationContext();
        
        Dns safeDns = hostname -> {
            try { return Dns.SYSTEM.lookup(hostname); }
            catch (Exception e) { throw new UnknownHostException(e.getMessage()); }
        };

        this.http = new OkHttpClient.Builder()
                .connectTimeout(60,  TimeUnit.SECONDS)  // Render free tier can take 50s to cold-start
                .readTimeout(90,    TimeUnit.SECONDS)
                .writeTimeout(90,   TimeUnit.SECONDS)
                .dns(safeDns)
                .build();
        this.deviceId = getOrCreateDeviceId();
    }

    private String getOrCreateDeviceId() {
        SharedPreferences prefs = prefs();
        String id = prefs.getString(KEY_DEVICE_ID, null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    public int getLocalModelVersion() {
        return prefs().getInt(KEY_MODEL_VERSION, 0);
    }

    private void saveLocalModelVersion(int version) {
        prefs().edit().putInt(KEY_MODEL_VERSION, version).apply();
    }

    private SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void uploadUpdate(List<SpamData> feedbackData, UploadCallback callback) {
        try {
            Log.i(TAG, "=== uploadUpdate START ===");
            Log.i(TAG, "Samples to upload: " + feedbackData.size());
            
            SpamClassifier classifier = SpamClassifier.getInstance(context);
            JSONArray featuresArray = new JSONArray();
            JSONArray labelsArray   = new JSONArray();

            for (SpamData data : feedbackData) {
                float[] floatTokens = classifier.tokenizeAndPad(data.message, 100);
                JSONArray row = new JSONArray();
                for (float f : floatTokens) row.put((int) f);
                featuresArray.put(row);
                labelsArray.put(data.label);
            }
            Log.i(TAG, "Tokenization complete. Features rows: " + featuresArray.length());

            JSONObject payload = new JSONObject();
            payload.put("device_id",     deviceId);
            payload.put("features",      featuresArray);
            payload.put("labels",        labelsArray);
            payload.put("model_version", getLocalModelVersion());
            
            String payloadStr = payload.toString();
            Log.i(TAG, "Payload size: " + payloadStr.length() + " chars");
            Log.i(TAG, "Posting to: " + SERVER_URL + "/upload");

            RequestBody body = RequestBody.create(payloadStr, JSON_TYPE);
            Request request = new Request.Builder().url(SERVER_URL + "/upload").post(body).build();

            Log.i(TAG, "Enqueuing HTTP request...");
            http.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    Log.e(TAG, "❌ HTTP FAILURE: " + e.getClass().getSimpleName() + " → " + e.getMessage(), e);
                    callback.onError("Upload failed: " + e.getMessage());
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try {
                        Log.i(TAG, "✅ HTTP RESPONSE: code=" + response.code());
                        String responseBody = response.body() != null ? response.body().string() : "(empty)";
                        Log.i(TAG, "Response body: " + responseBody);
                        
                        if (!response.isSuccessful()) {
                            Log.e(TAG, "Server returned error: " + response.code() + " → " + responseBody);
                            callback.onError("Server error (" + response.code() + "): " + responseBody);
                            return;
                        }
                        callback.onSuccess("Learning data uploaded! Server is training. Wait 15s then Sync again to download new model.");
                    } catch (Exception e) {
                        Log.e(TAG, "Response processing error", e);
                        callback.onError("Process error: " + e.getMessage());
                    } finally {
                        response.close();
                    }
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "❌ uploadUpdate EXCEPTION before HTTP call", e);
            callback.onError("Sync failed: " + e.getMessage());
        }
    }

    public void checkAndDownloadModel(DownloadCallback callback) {
        Request request = new Request.Builder().url(SERVER_URL + "/status").get().build();
        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError("Server offline.");
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    if (!response.isSuccessful()) {
                        callback.onError("Check failed.");
                        return;
                    }
                    String body = response.body().string();
                    JSONObject status = new JSONObject(body);
                    int serverVersion = status.getInt("model_version");
                    
                    if (serverVersion > getLocalModelVersion()) {
                        downloadModel(serverVersion, callback);
                    } else {
                        callback.onAlreadyUpToDate();
                    }
                } catch (Exception e) {
                    callback.onError("Parse error.");
                } finally {
                    response.close();
                }
            }
        });
    }

    private void downloadModel(int serverVersion, DownloadCallback callback) {
        Request request = new Request.Builder().url(SERVER_URL + "/get_model").get().build();
        http.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) { callback.onError("Download failed."); }
            @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    if (!response.isSuccessful()) { callback.onError("Model error."); return; }
                    File dest = new File(context.getFilesDir(), SpamClassifier.UPDATED_MODEL_NAME);
                    try (InputStream in = response.body().byteStream(); 
                             FileOutputStream out = new FileOutputStream(dest)) {
                        byte[] buf = new byte[8192];
                        int read;
                        while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
                    }
                    SpamClassifier.getInstance(context).loadModel();
                    saveLocalModelVersion(serverVersion);
                    callback.onNewModelAvailable(serverVersion);
                } catch (Exception e) {
                    callback.onError("Save failed.");
                } finally {
                    response.close();
                }
            }
        });
    }
}
