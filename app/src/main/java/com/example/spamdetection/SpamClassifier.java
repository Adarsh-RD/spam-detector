package com.example.spamdetection;

import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.util.Log;
import android.util.LruCache;

import org.json.JSONObject;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class SpamClassifier {
    private static final String TAG = "SpamClassifier";
    private static SpamClassifier instance;
    
    private Interpreter tflite;
    private Map<String, Integer> vocab = new HashMap<>();
    private static final int MAX_LENGTH = 100; 
    private static final String MODEL_FILE = "spam_model.tflite";
    public static final String UPDATED_MODEL_NAME = "updated_spam_model.tflite";
    private static final String VOCAB_FILE = "vocab.json";
    private final Context context;
    
    private final LruCache<String, Long> recentEvents = new LruCache<>(50); // stores timestamp of last processing
    private static final long DEDUP_WINDOW_MS = 10_000; // 10 seconds

    private static final String[] SPAM_KEYWORDS = {
        "offer", "win", "prize", "cashback", "lucky", "urgent", "jio", "vi", "airtel", 
        "loan", "credited", "debited", "discount", "free", "lottery", "congrats", "verify", "otp"
    };

    private SpamClassifier(Context context) {
        this.context = context.getApplicationContext();
        loadModel();
    }

    public static synchronized SpamClassifier getInstance(Context context) {
        if (instance == null) {
            instance = new SpamClassifier(context);
        }
        return instance;
    }

    public boolean isRecentlyProcessed(String sender, String content) {
        if (content == null) return false;
        String key = (sender + "|" + content).trim().toLowerCase();
        synchronized (recentEvents) {
            Long lastTime = recentEvents.get(key);
            // Only consider it a duplicate if processed within last 10 seconds
            return lastTime != null && (System.currentTimeMillis() - lastTime) < DEDUP_WINDOW_MS;
        }
    }

    public void markAsProcessed(String sender, String content) {
        if (content == null) return;
        String key = (sender + "|" + content).trim().toLowerCase();
        synchronized (recentEvents) {
            recentEvents.put(key, System.currentTimeMillis());
        }
    }

    public synchronized void loadModel() {
        try {
            if (tflite != null) { tflite.close(); tflite = null; }
            MappedByteBuffer modelBuffer = null;
            File updatedModel = new File(context.getFilesDir(), UPDATED_MODEL_NAME);
            
            if (updatedModel.exists()) {
                try (FileInputStream fis = new FileInputStream(updatedModel)) {
                    modelBuffer = fis.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, updatedModel.length());
                    Log.i(TAG, "Hot-reloading LEARNED AI brain");
                }
            } else {
                AssetFileDescriptor fd = context.getAssets().openFd(MODEL_FILE);
                FileInputStream fis = new FileInputStream(fd.getFileDescriptor());
                modelBuffer = fis.getChannel().map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
                Log.i(TAG, "Loading default factory AI model");
            }

            if (modelBuffer != null) {
                tflite = new Interpreter(modelBuffer);
                loadVocab();
                Log.i(TAG, "AI Engine Ready.");

                Intent intent = new Intent("com.example.spamdetection.MODEL_UPDATED");
                intent.setPackage(context.getPackageName());
                context.sendBroadcast(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "AI Load Error: " + e.getMessage());
        }
    }

    private void loadVocab() throws Exception {
        if (!vocab.isEmpty()) return;
        try (InputStream is = context.getAssets().open(VOCAB_FILE)) {
            byte[] buf = new byte[is.available()];
            is.read(buf);
            JSONObject json = new JSONObject(new String(buf, "UTF-8"));
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                vocab.put(k.toLowerCase(), json.getInt(k));
            }
        }
    }

    public synchronized boolean isSpam(String text) {
        if (text == null || text.isEmpty()) return false;
        final String cleanText = text.trim().toLowerCase().replaceAll("\\s+", " ");

        // 1. User Feedback check (Highest Priority)
        try {
            SpamData feedback = AppDatabase.getInstance(context).spamDao().findByMessage(cleanText);
            if (feedback != null) return feedback.label == 1;
        } catch (Exception e) { Log.e(TAG, "DB Error", e); }

        // 2. Keyword Check
        for (String keyword : SPAM_KEYWORDS) {
            if (cleanText.contains(keyword)) return true;
        }

        // 3. Length Safety: Very short messages like "Hey" or "Hi" are NOT spam
        if (cleanText.length() < 5) {
            return false;
        }

        // 4. AI Prediction
        if (tflite == null) return false;
        try {
            float[] tokens = tokenizeAndPad(cleanText, MAX_LENGTH);
            int outputSize = tflite.getOutputTensor(0).shape()[1];
            float[][] out = new float[1][outputSize];

            if (tflite.getInputTensor(0).dataType() == DataType.INT32) {
                int[][] in = new int[1][MAX_LENGTH];
                for (int i = 0; i < MAX_LENGTH; i++) in[0][i] = (int) tokens[i];
                tflite.run(in, out);
            } else {
                float[][] in = new float[1][MAX_LENGTH]; in[0] = tokens;
                tflite.run(in, out);
            }

            if (outputSize == 1) {
                // Single sigmoid output: value = P(spam)
                Log.d(TAG, "1-output model: spam_prob=" + out[0][0]);
                return out[0][0] > 0.65f;
            } else {
                // Two-output softmax: [ham_score, spam_score]
                float hamScore  = out[0][0];
                float spamScore = out[0][1];
                Log.d(TAG, "2-output model: ham=" + hamScore + " spam=" + spamScore);
                // Only flag as spam if it's CLEARLY spam (0.3 confidence gap)
                return spamScore > (hamScore + 0.3f);
            }
        } catch (Exception e) {
            Log.e(TAG, "AI inference error: " + e.getMessage());
            return false;
        }
    }

    public float[] tokenizeAndPad(String text, int maxLength) {
        String[] words = text.toLowerCase().replaceAll("[^a-z0-9\\s]", " ").trim().split("\\s+");
        float[] encoded = new float[maxLength];
        for (int i = 0; i < maxLength; i++) {
            if (i < words.length) {
                Integer id = vocab.get(words[i]);
                encoded[i] = (id != null) ? (float)id : 1.0f;
            } else encoded[i] = 0.0f;
        }
        return encoded;
    }
}
