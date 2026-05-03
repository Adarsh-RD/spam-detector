package com.example.spamdetection;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SMSNotificationListener extends NotificationListenerService {

    private static final String TAG = "SMSNotificationListener";
    private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        Log.i(TAG, "✅ Notification Listener CONNECTED");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.w(TAG, "⚠️ Notification Listener DISCONNECTED — requesting rebind...");
        // Request system to rebind this service (auto-heal after being killed)
        requestRebind(new android.content.ComponentName(getPackageName(), SMSNotificationListener.class.getName()));
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        workerExecutor.execute(() -> processNotification(sbn));
    }

    private void processNotification(StatusBarNotification sbn) {
        try {
            String packageName = sbn.getPackageName();
            Notification notification = sbn.getNotification();
            
            if (sbn.isOngoing() || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
                return;
            }

            Bundle extras = notification.extras;
            if (extras == null) return;

            String fromStr = extras.getString(Notification.EXTRA_TITLE);
            CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
            CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            String contentStr = (bigText != null) ? bigText.toString() : (text != null ? text.toString() : "");

            if (contentStr == null || contentStr.isEmpty()) return;

            String appType = getAppType(packageName);
            if (appType == null) return;

            // --- CHECK DUPLICATE ---
            SpamClassifier classifier = SpamClassifier.getInstance(this);
            if (classifier.isRecentlyProcessed(fromStr, contentStr)) {
                return;
            }

            boolean isSpam = classifier.isSpam(contentStr);
            String spamLabel = isSpam ? "[SPAM] " : "[HAM] ";

            StringBuilder displayMsg = new StringBuilder();
            displayMsg.append(spamLabel).append("[").append(appType).append("]\n");
            displayMsg.append("From: ").append(fromStr != null ? fromStr : "Unknown").append("\n");
            displayMsg.append("Content: ").append(contentStr).append("\n");

            Intent i = new Intent("com.example.spamdetection.NOTIFICATION_LISTENER_EXAMPLE");
            i.setPackage(getPackageName());
            i.putExtra("notification_event", displayMsg.toString());
            i.putExtra("is_spam", isSpam);
            i.putExtra("raw_content", contentStr);
            sendBroadcast(i);
            
            // Mark as processed ONLY AFTER successful broadcast
            classifier.markAsProcessed(fromStr, contentStr);
            
        } catch (Exception e) {
            Log.e(TAG, "Error: " + e.getMessage());
        }
    }

    private String getAppType(String packageName) {
        String p = packageName.toLowerCase();
        if (p.contains("whatsapp")) return "WHATSAPP";
        if (p.contains("mms") || p.contains("sms") || p.contains("messaging") || p.contains("message") || p.contains("telephony")) return "SMS";
        if (p.contains("gm") || p.contains("email") || p.contains("outlook") || p.contains("mail")) return "EMAIL";
        if (p.contains("jio")) return "JIO";
        return null;
    }
}
