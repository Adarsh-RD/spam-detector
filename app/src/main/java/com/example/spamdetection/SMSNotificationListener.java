package com.example.spamdetection;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.util.LruCache;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SMSNotificationListener extends NotificationListenerService {

    private static final String TAG = "SMSNotificationListener";
    private final LruCache<String, Boolean> processedMessages = new LruCache<>(50);
    private final ExecutorService workerExecutor = Executors.newSingleThreadExecutor();

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        workerExecutor.execute(() -> processNotification(sbn));
    }

    private void processNotification(StatusBarNotification sbn) {
        try {
            String packageName = sbn.getPackageName();
            
            // To prevent double notifications for SMS, we ignore the default messaging apps
            // in the NotificationListener, as SMSReceiver already handles them directly.
            if (isDefaultSmsApp(packageName)) {
                return;
            }

            Notification notification = sbn.getNotification();
            if (sbn.isOngoing() || (notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
                return;
            }

            Bundle extras = notification.extras;
            if (extras == null) return;

            String appType = getAppType(packageName);
            if (appType == null) return;

            String fromStr;
            String contentStr;

            fromStr = extras.getString(Notification.EXTRA_TITLE);
            CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
            CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
            contentStr = (bigText != null) ? bigText.toString() : (text != null ? text.toString() : "");

            if (contentStr.isEmpty()) return;

            String uniqueMsgKey = packageName + "|" + fromStr + "|" + contentStr + "|" + notification.when;
            if (processedMessages.get(uniqueMsgKey) != null) return;
            processedMessages.put(uniqueMsgKey, true);

            boolean isSpam = SpamClassifier.getInstance(this).isSpam(contentStr);
            String spamLabel = isSpam ? "[SPAM] " : "[HAM] ";

            StringBuilder displayMsg = new StringBuilder();
            displayMsg.append(spamLabel).append("[").append(appType).append("]\n");
            displayMsg.append("From: ").append(fromStr != null ? fromStr : "Unknown").append("\n");
            displayMsg.append("Content: ").append(contentStr).append("\n");

            Intent i = new Intent("com.example.spamdetection.NOTIFICATION_LISTENER_EXAMPLE");
            i.putExtra("notification_event", displayMsg.toString());
            i.putExtra("is_spam", isSpam);
            i.putExtra("raw_content", contentStr);
            sendBroadcast(i);
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing notification: " + e.getMessage());
        }
    }

    private boolean isDefaultSmsApp(String packageName) {
        return packageName.equals("com.google.android.apps.messaging") || 
               packageName.equals("com.android.mms") || 
               packageName.equals("com.samsung.android.messaging") ||
               packageName.contains("messaging");
    }

    private String getAppType(String packageName) {
        if (packageName.equals("com.whatsapp") || packageName.equals("com.whatsapp.w4b")) return "WHATSAPP";
        if (packageName.equals("com.google.android.gm") || packageName.equals("com.microsoft.office.outlook") || packageName.contains("email")) return "EMAIL";
        // Note: SMS types are now filtered out by isDefaultSmsApp to avoid duplication
        return null;
    }
}
