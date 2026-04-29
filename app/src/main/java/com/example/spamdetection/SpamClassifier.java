package com.example.spamdetection;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.util.Log;

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
    private static final String MODEL_FILE = "spam_model_quantized (2).tflite";
    public static final String UPDATED_MODEL_NAME = "updated_spam_model.tflite";
    private static final String VOCAB_FILE = "vocab (1).json";
    private final Context context;

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

    public void loadModel() {
        try {
            if (tflite != null) {
                tflite.close();
                tflite = null;
            }
            
            MappedByteBuffer modelBuffer = null;
            File updatedModel = new File(context.getFilesDir(), UPDATED_MODEL_NAME);
            
            if (updatedModel.exists()) {
                Log.d(TAG, "Federated Learning: Loading updated local model weights...");
                FileInputStream fis = new FileInputStream(updatedModel);
                modelBuffer = fis.getChannel().map(FileChannel.MapMode.READ_ONLY, 0, updatedModel.length());
            } else {
                Log.d(TAG, "Loading base model from assets...");
                modelBuffer = loadModelFromAssets(context);
            }

            if (modelBuffer != null) {
                tflite = new Interpreter(modelBuffer);
                loadVocab(context);
                Log.d(TAG, "SUCCESS: Model and Vocab ready.");
            }
        } catch (Exception e) {
            Log.e(TAG, "FATAL: Could not load model: " + e.getMessage());
        }
    }

    private MappedByteBuffer loadModelFromAssets(Context context) throws Exception {
        AssetFileDescriptor fileDescriptor = context.getAssets().openFd(MODEL_FILE);
        FileInputStream inputStream = new FileInputStream(fileDescriptor.getFileDescriptor());
        FileChannel fileChannel = inputStream.getChannel();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, fileDescriptor.getStartOffset(), fileDescriptor.getDeclaredLength());
    }

    private void loadVocab(Context context) throws Exception {
        if (!vocab.isEmpty()) return;
        try (InputStream is = context.getAssets().open(VOCAB_FILE)) {
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            String json = new String(buffer, "UTF-8");
            JSONObject jsonObject = new JSONObject(json);
            Iterator<String> keys = jsonObject.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                vocab.put(key.toLowerCase(), jsonObject.getInt(key));
            }
        }
    }

    public float[] tokenizeAndPad(String text, int maxLength) {
        String cleanedText = text.toLowerCase().replaceAll("[^a-z0-9\\s]", " ");
        String[] words = cleanedText.trim().split("\\s+");
        float[] encoded = new float[maxLength];
        
        for (int i = 0; i < maxLength; i++) {
            if (i < words.length) {
                Integer id = vocab.get(words[i]);
                encoded[i] = (id != null) ? (float)id : 1.0f; 
            } else {
                encoded[i] = 0.0f; 
            }
        }
        return encoded;
    }

    /**
     * Now performs synchronous database check. 
     * MUST be called from a background thread.
     */
    public boolean isSpam(String text) {
        if (text == null || text.isEmpty()) return false;
        
        final String searchContent = text.trim().toLowerCase();

        // --- STEP 0: Check Local User Feedback (Direct database check) ---
        // Since Room doesn't allow DB ops on UI thread, and we are calling this from 
        // a background thread in the Listener, we use the DB directly.
        SpamData feedback = AppDatabase.getInstance(context).spamDao().findByMessage(searchContent);
        if (feedback != null) {
            Log.d(TAG, "Feedback Found: User says " + (feedback.label == 1 ? "SPAM" : "HAM"));
            return feedback.label == 1;
        }

        String lower = searchContent;

        // 1. Keyword Checks (Fallback)
        if (lower.matches(".*\\b(win|winner|won|prize|gift|reward|cash|cashback|lottery|lucky|bonus|claim|giftcard|amazon|walmart|iphone|samsung|galaxy|exclusive|congratulations|congrats|unlocked|free)\\b.*") && 
            (lower.contains("link") || lower.contains("click") || lower.contains("http") || lower.contains("bit.ly") || lower.contains("wa.me") || lower.contains("tinyurl"))) {
            return true;
        }

        if (tflite == null) return false;

        // --- AI Model Inference ---
        float[] inputVector = tokenizeAndPad(searchContent, MAX_LENGTH);
        if (tflite.getInputTensor(0).dataType() == DataType.INT32) {
            int[][] input = new int[1][MAX_LENGTH];
            for (int i = 0; i < MAX_LENGTH; i++) input[0][i] = (int)inputVector[i];
            return runInference(input);
        } else {
            float[][] input = new float[1][MAX_LENGTH];
            input[0] = inputVector;
            return runInference(input);
        }
    }

    private boolean runInference(Object input) {
        try {
            int outputDim = tflite.getOutputTensor(0).shape()[1];
            if (outputDim == 1) {
                float[][] output = new float[1][1];
                tflite.run(input, output);
                return output[0][0] > 0.35f; // Slightly more aggressive threshold
            } else if (outputDim == 2) {
                float[][] output = new float[1][2];
                tflite.run(input, output);
                return (output[0][1] * 1.4f) > output[0][0]; // Give spam higher weight
            }
        } catch (Exception e) {
            Log.e(TAG, "Inference error: " + e.getMessage());
        }
        return false;
    }
}
