package com.example.spamdetection;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;sa

public class SMSReceiver extends BroadcastReceiver {
    private static final String TAG = "SMSReceiver";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onReceive(Context context, Intent intent) {
        if ("android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) {
            final PendingResult pendingResult = goAsync(); // Keep receiver alive for bg work
            
            executor.execute(() -> {
                try {
                    processSms(context, intent);
                } finally {
                    pendingResult.finish();
                }
            });
        }
    }

    private void processSms(Context context, Intent intent) {
        Bundle bundle = intent.getExtras();
        if (bundle == null) return;

        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null) return;

        for (Object pdu : pdus) {
            SmsMessage smsMessage = SmsMessage.createFromPdu((byte[]) pdu, bundle.getString("format"));
            String sender = smsMessage.getDisplayOriginatingAddress();
            String messageBody = smsMessage.getMessageBody();

            if (messageBody == null) continue;

            Log.d(TAG, "SMS Received in background from: " + sender);

            // Now safe to call isSpam (it does DB ops)
            boolean isSpam = SpamClassifier.getInstance(context).isSpam(messageBody);
            String spamLabel = isSpam ? "[SPAM] " : "[HAM] ";

            Intent i = new Intent("com.example.spamdetection.NOTIFICATION_LISTENER_EXAMPLE");
            String displayMsg = spamLabel + "DIRECT SMS\nFrom: " + sender + "\nContent: " + messageBody + "\n";
            i.putExtra("notification_event", displayMsg);
            i.putExtra("is_spam", isSpam);
            i.putExtra("raw_content", messageBody);
            context.sendBroadcast(i);
        }
    }
}
