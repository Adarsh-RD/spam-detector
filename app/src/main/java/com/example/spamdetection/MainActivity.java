package com.example.spamdetection;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int SMS_PERMISSION_CODE = 101;
    private TextView statusText;
    private LinearLayout emptyStateContainer;
    private RecyclerView recyclerView;
    private MessageAdapter adapter;
    private NotificationReceiver nReceiver;
    private ModelUpdateReceiver mReceiver;
    private SpamTrainer spamTrainer;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        statusText = findViewById(R.id.status_text);
        emptyStateContainer = findViewById(R.id.empty_state_container);
        recyclerView = findViewById(R.id.recycler_messages);
        
        spamTrainer = new SpamTrainer(this);
        
        adapter = new MessageAdapter((message, label, position) -> {
            saveFeedback(message.rawContent, label, position);
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        findViewById(R.id.btn_settings).setOnClickListener(v -> 
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        findViewById(R.id.btn_train).setOnClickListener(v -> startManualSync());

        // LONG PRESS SYNC: Resets AI to factory and clears feedback DB (fixes "Hey=Spam" loop)
        findViewById(R.id.btn_train).setOnLongClickListener(v -> {
            resetAIModelAndDatabase();
            return true;
        });

        findViewById(R.id.btn_clear).setOnClickListener(v -> {
            adapter.clearAll();
            updateEmptyState();
        });

        checkPermissions();

        // Fix: Explicitly use RECEIVER_EXPORTED for cross-app/system communication
        nReceiver = new NotificationReceiver();
        IntentFilter filter = new IntentFilter("com.example.spamdetection.NOTIFICATION_LISTENER_EXAMPLE");
        
        mReceiver = new ModelUpdateReceiver();
        IntentFilter mFilter = new IntentFilter("com.example.spamdetection.MODEL_UPDATED");

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(nReceiver, filter, Context.RECEIVER_EXPORTED);
            registerReceiver(mReceiver, mFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(nReceiver, filter);
            registerReceiver(mReceiver, mFilter);
        }
        
        updateEmptyState();
        Log.d(TAG, "MainActivity initialized.");
    }

    private void resetAIModelAndDatabase() {
        dbExecutor.execute(() -> {
            // 1. Clear Feedback DB
            AppDatabase.getInstance(this).spamDao().deleteAll();
            // 2. Delete learned model
            File modelFile = new File(getFilesDir(), SpamClassifier.UPDATED_MODEL_NAME);
            if (modelFile.exists()) modelFile.delete();
            
            runOnUiThread(() -> {
                SpamClassifier.getInstance(this).loadModel();
                Toast.makeText(this, "AI & Feedback Data Reset to Factory Defaults", Toast.LENGTH_LONG).show();
            });
        });
    }

    private void checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS}, SMS_PERMISSION_CODE);
        }
    }

    private void startManualSync() {
        final View btnSync = findViewById(R.id.btn_train);
        btnSync.setEnabled(false);
        btnSync.setAlpha(0.5f);

        dbExecutor.execute(() -> {
            try {
                List<SpamData> localData = AppDatabase.getInstance(this).spamDao().getAllData();
                Log.d(TAG, "Starting sync with " + localData.size() + " samples.");

                if (localData.isEmpty()) {
                    runOnUiThread(() -> {
                        Toast.makeText(this, "No new data to sync. Checking for cloud brain updates...", Toast.LENGTH_SHORT).show();
                        spamTrainer.checkForModelUpdate();
                        btnSync.setEnabled(true);
                        btnSync.setAlpha(1.0f);
                    });
                    return;
                }
                
                runOnUiThread(() -> Toast.makeText(this, "Uploading to Render...", Toast.LENGTH_SHORT).show());
                
                spamTrainer.trainLocally(localData, new SpamTrainer.TrainCallback() {
                    @Override
                    public void onProgress(String message) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
                    }
                    @Override
                    public void onComplete(String result) {
                        
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this, "✅ Uploaded! Cloud is training (45s)...", Toast.LENGTH_LONG).show();
                            
                            // First attempt: check at 45s (training usually takes 20-40s)
                            mainHandler.postDelayed(() -> {
                                Toast.makeText(MainActivity.this, "Checking for new AI brain...", Toast.LENGTH_SHORT).show();
                                spamTrainer.checkForModelUpdate();
                            }, 45000);

                            // Second attempt: retry at 90s in case first was too early
                            mainHandler.postDelayed(() -> {
                                spamTrainer.checkForModelUpdate();
                                btnSync.setEnabled(true);
                                btnSync.setAlpha(1.0f);
                            }, 90000);
                        });
                    }
                    @Override
                    public void onError(String error) {
                        runOnUiThread(() -> {
                            btnSync.setEnabled(true);
                            btnSync.setAlpha(1.0f);
                            Toast.makeText(MainActivity.this, "Sync Fail: " + error, Toast.LENGTH_LONG).show();
                        });
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnSync.setEnabled(true);
                    btnSync.setAlpha(1.0f);
                });
            }
        });
    }

    private void saveFeedback(String text, int label, int position) {
        if (text == null) return;
        final String normalizedText = text.trim().toLowerCase();
        dbExecutor.execute(() -> {
            AppDatabase.getInstance(this).spamDao().insert(new SpamData(normalizedText, label));
            runOnUiThread(() -> {
                adapter.removeMessage(position);
                updateEmptyState();
                Toast.makeText(this, "Marked as " + (label == 1 ? "Spam" : "Ham"), Toast.LENGTH_SHORT).show();
            });
        });
    }

    private void updateEmptyState() {
        if (adapter.getItemCount() == 0) {
            emptyStateContainer.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyStateContainer.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isNotificationServiceEnabled()) {
            statusText.setText("Status: Protection Active");
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light));
        } else {
            statusText.setText("Status: Listener Disabled");
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            if (nReceiver != null) unregisterReceiver(nReceiver);
            if (mReceiver != null) unregisterReceiver(mReceiver);
        } catch (Exception e) { Log.e(TAG, "Cleanup error", e); }
    }

    private boolean isNotificationServiceEnabled() {
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (String name : names) {
                final ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null && TextUtils.equals(pkgName, cn.getPackageName())) return true;
            }
        }
        return false;
    }

    class NotificationReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String displayText = intent.getStringExtra("notification_event");
            String rawContent = intent.getStringExtra("raw_content");
            Log.d(TAG, "Recv in UI: " + rawContent);
            if (displayText == null) return;

            String header = "[NEW MESSAGE]";
            if (displayText.contains("\n")) {
                header = displayText.substring(0, displayText.indexOf("\n"));
            }

            adapter.addMessage(new MessageAdapter.CapturedMessage(header, displayText, rawContent));
            updateEmptyState();
        }
    }

    class ModelUpdateReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.i(TAG, "Hot-reload alert received!");
            Toast.makeText(context, "🚀 AI Engine Hot-Reloaded with New Brain!", Toast.LENGTH_LONG).show();
        }
    }
}
