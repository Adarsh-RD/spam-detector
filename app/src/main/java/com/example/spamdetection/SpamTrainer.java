package com.example.spamdetection;

import android.content.Context;
import android.util.Log;

import java.util.List;

/**
 * SpamTrainer — Real Federated Learning implementation.
 *
 * What actually happens when the user taps "Train":
 *  1. Load all user-corrected messages from Room DB
 *  2. Tokenize each message ON-DEVICE → int[100] (raw text never leaves phone)
 *  3. Upload (features[], labels[]) to FL server via FederatedClient
 *  4. Server fine-tunes global Keras model with FedAvg
 *  5. Server exports new .tflite, increments version
 *  6. Client checks server version, downloads if newer
 *  7. SpamClassifier hot-reloads the new model
 */
public class SpamTrainer {

    private static final String TAG = "SpamTrainer";

    private final Context context;
    private final FederatedClient federatedClient;

    public interface TrainCallback {
        void onProgress(String message);
        void onComplete(String result);
        void onError(String error);
    }

    public SpamTrainer(Context context) {
        this.context         = context.getApplicationContext();
        this.federatedClient = new FederatedClient(context);
    }

    /**
     * Entry point called from MainActivity (must be called from a background thread).
     */
    public void trainLocally(List<SpamData> trainingData, TrainCallback callback) {
        if (trainingData == null || trainingData.isEmpty()) {
            callback.onError("No correction data yet. Mark some messages first!");
            return;
        }

        callback.onProgress("Tokenizing " + trainingData.size() + " messages on-device…");
        Log.i(TAG, "Starting FL upload with " + trainingData.size() + " samples.");

        federatedClient.uploadUpdate(trainingData, new FederatedClient.UploadCallback() {
            @Override
            public void onSuccess(String message) {
                Log.i(TAG, "FL result: " + message);
                callback.onComplete(message);
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "FL error: " + error);
                callback.onError(error);
            }
        });
    }

    /**
     * Called at app startup — silently checks if a newer global model is available.
     */
    public void checkForModelUpdate() {
        federatedClient.checkAndDownloadModel(new FederatedClient.DownloadCallback() {
            @Override
            public void onNewModelAvailable(int newVersion) {
                Log.i(TAG, "Background sync: model updated to v" + newVersion);
            }
            @Override
            public void onAlreadyUpToDate() {
                Log.i(TAG, "Background sync: model is current.");
            }
            @Override
            public void onError(String error) {
                Log.w(TAG, "Background sync failed (non-critical): " + error);
            }
        });
    }
}
