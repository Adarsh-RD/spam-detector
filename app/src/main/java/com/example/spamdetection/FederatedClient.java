package com.example.spamdetection;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Handles all communication with the Federated Learning server.
 *
 * PRIVACY: Raw SMS text NEVER leaves the device.
 *          Only tokenized int[100] feature vectors + labels are uploaded.
 */
public class FederatedClient {

    private static final String TAG = "FederatedClient";

    // ⚠️ Replace this with your actual Render URL after deployment
    public static final String SERVER_URL = "https://spam-fl-server.onrender.com";

    private static final String PREFS_NAME   = "fl_prefs";
    private static final String KEY_DEVICE_ID     = "device_id";
    private static final String KEY_MODEL_VERSION = "model_version";

    private static final MediaType JSON_TYPE =
            MediaType.parse("application/json; charset=utf-8");

    private final Context context;
    private final OkHttpClient http;
    private final String deviceId;

    // ─── Callbacks ────────────────────────────────────────────────────────────
    public interface UploadCallback {
        void onSuccess(String message);
        void onError(String error);
    }

    public interface DownloadCallback {
        void onNewModelAvailable(int newVersion);
        void onAlreadyUpToDate();
        void onError(String error);
    }

    // ─── Constructor ──────────────────────────────────────────────────────────
    public FederatedClient(Context context) {
        this.context  = context.getApplicationContext();
        this.http     = new OkHttpClient.Builder()
                .connectTimeout(30,  TimeUnit.SECONDS)
                .readTimeout(120,    TimeUnit.SECONDS)   // model download can be large
                .writeTimeout(60,    TimeUnit.SECONDS)
                .build();
        this.deviceId = getOrCreateDeviceId();
    }

    // ─── Device identity ──────────────────────────────────────────────────────
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

    // ─── Upload ───────────────────────────────────────────────────────────────
    /**
     * Tokenizes feedback data on-device, then POSTs to /upload.
     * After a successful upload, automatically checks for a newer model.
     * Must be called from a background thread (OkHttp dispatches async).
     */
    public void uploadUpdate(List<SpamData> feedbackData, UploadCallback callback) {
        try {
            SpamClassifier classifier = SpamClassifier.getInstance(context);

            JSONArray featuresArray = new JSONArray();
            JSONArray labelsArray   = new JSONArray();

            for (SpamData data : feedbackData) {
                // Privacy: tokenize on-device; raw text stays here
                float[] floatTokens = classifier.tokenizeAndPad(data.message, 100);
                JSONArray row = new JSONArray();
                for (float f : floatTokens) row.put((int) f);
                featuresArray.put(row);
                labelsArray.put(data.label);
            }

            JSONObject payload = new JSONObject();
            payload.put("device_id",     deviceId);
            payload.put("features",      featuresArray);
            payload.put("labels",        labelsArray);
            payload.put("model_version", getLocalModelVersion());

            RequestBody body    = RequestBody.create(payload.toString(), JSON_TYPE);
            Request     request = new Request.Builder()
                    .url(SERVER_URL + "/upload")
                    .post(body)
                    .build();

            http.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e(TAG, "Upload failed: " + e.getMessage());
                    callback.onError("Network error — check your connection.");
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (!response.isSuccessful()) {
                        callback.onError("Server error: " + response.code());
                        return;
                    }
                    Log.i(TAG, "Upload OK. Checking for model update…");
                    // Chain: after upload → check if server has a new model
                    checkAndDownloadModel(new DownloadCallback() {
                        @Override
                        public void onNewModelAvailable(int v) {
                            callback.onSuccess("✅ Model updated to v" + v + " — AI improved!");
                        }
                        @Override
                        public void onAlreadyUpToDate() {
                            callback.onSuccess("✅ Feedback sent. Model is up-to-date.");
                        }
                        @Override
                        public void onError(String error) {
                            callback.onSuccess("✅ Feedback sent. (Model sync: " + error + ")");
                        }
                    });
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Upload error: " + e.getMessage());
            callback.onError("Error preparing upload: " + e.getMessage());
        }
    }

    // ─── Check + Download ─────────────────────────────────────────────────────
    /**
     * Compares local model version against server. Downloads if newer.
     */
    public void checkAndDownloadModel(DownloadCallback callback) {
        Request request = new Request.Builder()
                .url(SERVER_URL + "/status")
                .get()
                .build();

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("Status check failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        callback.onError("Status error: " + response.code());
                        return;
                    }
                    JSONObject status      = new JSONObject(response.body().string());
                    int serverVersion      = status.getInt("model_version");
                    boolean modelAvailable = status.getBoolean("model_available");
                    int localVersion       = getLocalModelVersion();

                    Log.i(TAG, "Server v" + serverVersion + " / Local v" + localVersion);

                    if (modelAvailable && serverVersion > localVersion) {
                        downloadModel(serverVersion, callback);
                    } else {
                        callback.onAlreadyUpToDate();
                    }
                } catch (Exception e) {
                    callback.onError("Parse error: " + e.getMessage());
                }
            }
        });
    }

    private void downloadModel(int serverVersion, DownloadCallback callback) {
        Request request = new Request.Builder()
                .url(SERVER_URL + "/get_model")
                .get()
                .build();

        http.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError("Download failed: " + e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    callback.onError("Download error: " + response.code());
                    return;
                }
                try {
                    File dest = new File(context.getFilesDir(), SpamClassifier.UPDATED_MODEL_NAME);
                    try (InputStream in  = response.body().byteStream();
                         FileOutputStream out = new FileOutputStream(dest)) {
                        byte[] buf = new byte[8192];
                        int read;
                        while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
                    }
                    Log.i(TAG, "Model v" + serverVersion + " saved (" + dest.length() + " bytes)");

                    // Hot-reload the classifier without restarting the app
                    SpamClassifier.getInstance(context).loadModel();
                    saveLocalModelVersion(serverVersion);

                    callback.onNewModelAvailable(serverVersion);

                } catch (Exception e) {
                    callback.onError("Save error: " + e.getMessage());
                }
            }
        });
    }
}
