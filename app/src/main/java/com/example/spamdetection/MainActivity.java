package com.example.spamdetection;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private TextView statusText;
    private LinearLayout emptyStateContainer;
    private RecyclerView recyclerView;
    private MessageAdapter adapter;
    private NotificationReceiver nReceiver;
    private SpamTrainer spamTrainer;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        
        View rootLayout = findViewById(R.id.main);
        
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        statusText = findViewById(R.id.status_text);
        emptyStateContainer = findViewById(R.id.empty_state_container);
        recyclerView = findViewById(R.id.recycler_messages);
        
        spamTrainer = new SpamTrainer(this);
        
        adapter = new MessageAdapter((message, label, position) -> {
            saveFeedback(message.rawContent, label, position);
        });

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        Button btnSettings = findViewById(R.id.btn_settings);
        Button btnTrain = findViewById(R.id.btn_train);
        Button btnClear = findViewById(R.id.btn_clear);

        btnSettings.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        });

        btnTrain.setOnClickListener(v -> {
            startTrainingProcess();
        });

        btnClear.setOnClickListener(v -> {
            adapter.clearAll();
            updateEmptyState();
        });

        nReceiver = new NotificationReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.example.spamdetection.NOTIFICATION_LISTENER_EXAMPLE");
        registerReceiver(nReceiver, filter, Context.RECEIVER_EXPORTED);
        
        updateEmptyState();
    }

    private void startTrainingProcess() {
        Toast.makeText(this, "Uploading feedback to FL server...", Toast.LENGTH_SHORT).show();
        dbExecutor.execute(() -> {
            List<SpamData> localData = AppDatabase.getInstance(this).spamDao().getAllData();
            spamTrainer.trainLocally(localData, new SpamTrainer.TrainCallback() {
                @Override
                public void onProgress(String message) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show());
                }
                @Override
                public void onComplete(String result) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, result, Toast.LENGTH_LONG).show());
                }
                @Override
                public void onError(String error) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error: " + error, Toast.LENGTH_LONG).show());
                }
            });
        });
    }

    private void saveFeedback(String text, int label, int position) {
        if (text == null) return;
        // Normalize for consistent database matching
        final String normalizedText = text.trim().toLowerCase();
        
        dbExecutor.execute(() -> {
            AppDatabase.getInstance(this).spamDao().insert(new SpamData(normalizedText, label));
            runOnUiThread(() -> {
                adapter.removeMessage(position);
                updateEmptyState();
                Toast.makeText(this, "AI Learned from your feedback", Toast.LENGTH_SHORT).show();
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
            statusText.setTextColor(getResources().getColor(R.color.status_green, getTheme()));
        } else {
            statusText.setText("Status: Inactive (Grant Access)");
            statusText.setTextColor(getResources().getColor(R.color.btn_clear, getTheme()));
        }
        // Silently check for a newer global model every time app comes to foreground
        dbExecutor.execute(() -> spamTrainer.checkForModelUpdate());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (nReceiver != null) {
            unregisterReceiver(nReceiver);
        }
    }

    private boolean isNotificationServiceEnabled() {
        String pkgName = getPackageName();
        final String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (!TextUtils.isEmpty(flat)) {
            final String[] names = flat.split(":");
            for (String name : names) {
                final ComponentName cn = ComponentName.unflattenFromString(name);
                if (cn != null) {
                    if (TextUtils.equals(pkgName, cn.getPackageName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    class NotificationReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String displayText = intent.getStringExtra("notification_event");
            String rawContent = intent.getStringExtra("raw_content");
            if (displayText == null) return;

            String header = "[NOTIFICATION]";
            
            if (displayText.contains("\n")) {
                header = displayText.substring(0, displayText.indexOf("\n"));
            }

            adapter.addMessage(new MessageAdapter.CapturedMessage(header, displayText, rawContent));
            updateEmptyState();
        }
    }
}
