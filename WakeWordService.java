package com.smartsaathi.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Locale;

public class WakeWordService extends Service {
    public static final String ACTION_START = "com.smartsaathi.app.WAKE_START";
    public static final String ACTION_STOP = "com.smartsaathi.app.WAKE_STOP";
    public static final String ACTION_WAKE_COMMAND = "com.smartsaathi.app.WAKE_COMMAND";
    public static final String EXTRA_COMMAND = "command";
    public static final String EXTRA_LANGUAGE = "language";
    private static final String CHANNEL_ID = "smart_saathi_voice";
    private static final int NOTIFICATION_ID = 4207;
    private SpeechRecognizer recognizer;
    private String language = "hi-IN";
    private boolean listening = false;
    private boolean shouldRestart = false;
    private boolean waitingForCommand = false;
    private long commandDeadline = 0L;
    private PowerManager.WakeLock wakeLock;

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) { stopListeningAndSelf(); return START_NOT_STICKY; }
        if (intent != null) {
            String requestedLanguage = intent.getStringExtra(EXTRA_LANGUAGE);
            if (requestedLanguage != null && !requestedLanguage.trim().isEmpty()) language = requestedLanguage;
        }
        shouldRestart = true;
        startForeground(NOTIFICATION_ID, buildNotification());
        acquireWakeLock();
        startRecognizer();
        return START_STICKY;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Smart Saathi hands-free", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("दिखाता है कि Smart Saathi की आवाज़ पहचान चालू है");
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPending = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, WakeWordService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        b.setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Smart Saathi सुन रहा है")
            .setContentText("वेक-वर्ड चालू है। रोकने के लिए नीचे दबाएँ।")
            .setContentIntent(openPending)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "सुनना बंद करें", stopPending);
        return b.build();
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartSaathi:VoiceListening");
                wakeLock.acquire(10 * 60 * 60 * 1000L);
            }
        } catch (Exception ignored) {}
    }

    private void startRecognizer() {
        if (!shouldRestart) return;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { stopListeningAndSelf(); return; }
        try {
            if (recognizer != null) { try { recognizer.destroy(); } catch (Exception ignored) {} recognizer = null; }
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { listening = true; }
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onEndOfSpeech() { listening = false; }
                @Override public void onError(int error) { listening = false; scheduleRestart(error); }
                @Override public void onResults(Bundle results) { listening = false; handleResults(results); scheduleRestart(0); }
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language);
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
            intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L);
            intent.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 700L);
            recognizer.startListening(intent);
        } catch (Exception e) { scheduleRestart(0); }
    }

    private void handleResults(Bundle results) {
        ArrayList<String> candidates = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (candidates == null || candidates.isEmpty()) return;
        String heard = candidates.get(0) == null ? "" : candidates.get(0).trim();
        if (heard.isEmpty()) return;
        String normalized = normalize(heard);
        String wake = findWakeWord(normalized);
        long now = System.currentTimeMillis();
        if (wake != null) {
            int at = normalized.indexOf(wake);
            String command = normalized.substring(Math.min(normalized.length(), at + wake.length())).trim();
            if (!command.isEmpty()) {
                sendCommand(command);
                waitingForCommand = false;
            } else {
                waitingForCommand = true;
                commandDeadline = now + 9000L;
                sendCommand("");
            }
        } else if (waitingForCommand && now <= commandDeadline) {
            sendCommand(heard);
            waitingForCommand = false;
        } else {
            waitingForCommand = false;
        }
    }

    private String normalize(String value) {
        String s = Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).trim();
        s = s.replaceAll("[.,!?،؛:]+", " ").replaceAll("\\s+", " ");
        return s;
    }

    private String findWakeWord(String s) {
        // Longer names come first so they are not reduced to a shorter alias.
        String[] phrases = {
            "hey siri smart saathi", "hey smart saathi bro", "smart saathi bro", "smart sathi bro", "hey smart saathi", "hey smart sathi",
            "hey siri saathi", "hey siri sathi", "hey saathi", "hey sathi", "hey smart saathi bro",
            "smart saathi", "smart sathi", "hey bhaiya", "hey bhai", "hey dost", "hey jaanu", "hey jaan",
            "hey darling", "hey bibi", "hey biwi", "hey yaar", "hey saathi ji", "hey sathi ji", "hey siri",
            "हे स्मार्ट साथी", "ए स्मार्ट साथी", "हे सिरी स्मार्ट साथी", "हे सिरी साथी", "हे सिरी साठी",
            "हे साथी", "हे साठी", "हे भाईया", "हे भैया", "हे भाई", "हे दोस्त", "हे जानू", "हे जान",
            "हे डार्लिंग", "हे बीबी", "हे बीवी", "हे बिबी", "हे यार", "स्मार्ट साथी जी", "स्मार्ट साथी",
            "स्मार्ट साठी", "साथी जी", "साथी", "साठी", "भैया", "भाई", "दोस्त", "जानू", "जान",
            "डार्लिंग", "बीबी", "बीवी", "बिबी", "यार", "darling", "saathi", "sathi", "jaan", "jaanu", "bibi", "biwi", "bhaiya", "bhai", "dost", "yaar"
        };
        for (String phrase : phrases) {
            int from = 0;
            while (from <= s.length() - phrase.length()) {
                int at = s.indexOf(phrase, from);
                if (at < 0) break;
                int end = at + phrase.length();
                boolean leftBoundary = at == 0 || Character.isWhitespace(s.charAt(at - 1));
                boolean rightBoundary = end == s.length() || Character.isWhitespace(s.charAt(end));
                if (leftBoundary && rightBoundary) return phrase;
                from = at + 1;
            }
        }
        return null;
    }

    private void sendCommand(String command) {
        Intent intent = new Intent(ACTION_WAKE_COMMAND);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_COMMAND, command);
        sendBroadcast(intent);
    }

    private void scheduleRestart(int error) {
        if (!shouldRestart) return;
        // Do not busy-loop on absent speech services or a revoked microphone permission.
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS || error == SpeechRecognizer.ERROR_CLIENT) {
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { stopListeningAndSelf(); return; }
        }
        new android.os.Handler(getMainLooper()).postDelayed(() -> { if (shouldRestart) startRecognizer(); }, error == 0 ? 350L : 1000L);
    }

    private void stopListeningAndSelf() {
        shouldRestart = false;
        listening = false;
        waitingForCommand = false;
        try { if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); recognizer = null; } } catch (Exception ignored) {}
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
        stopForeground(true);
        stopSelf();
    }

    @Override public void onDestroy() {
        shouldRestart = false;
        try { if (recognizer != null) { recognizer.cancel(); recognizer.destroy(); } } catch (Exception ignored) {}
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
