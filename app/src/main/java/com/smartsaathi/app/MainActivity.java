package com.smartsaathi.app;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.net.Uri;
import android.app.AlertDialog;
import android.os.Build;
import android.os.Bundle;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

public class MainActivity extends Activity {
    private WebView webView;
    private PermissionRequest pendingWebPermission;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;
    private boolean receiverRegistered = false;
    private static final int MIC_PERMISSION = 100;
    private static final int LOCATION_PERMISSION = 101;
    private static final int NOTIFICATION_PERMISSION = 102;
    private static final int CONTACT_PERMISSION = 103;
    private static final int CONTACT_PICK_REQUEST = 104;
    private String pendingContactQuery = "";
    private String pendingLanguage = "hi-IN";
    private TextToSpeech textToSpeech;
    private boolean ttsReady = false;
    private boolean pendingWakeStart = false;
    private String pendingSpeech = null;
    private String pendingSpeechLanguage = "hi-IN";
    private float pendingSpeechRate = 1.0f;
    private float pendingSpeechPitch = 1.0f;
    private float pendingSpeechVolume = 1.0f;

    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !WakeWordService.ACTION_WAKE_COMMAND.equals(intent.getAction())) return;
            final String command = intent.getStringExtra(WakeWordService.EXTRA_COMMAND);
            if (webView != null) {
                final String safe = org.json.JSONObject.quote(command == null ? "" : command);
                runOnUiThread(() -> webView.evaluateJavascript("window.handleNativeWakeCommand && window.handleNativeWakeCommand(" + safe + ")", null));
            }
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setGeolocationEnabled(true);

        WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
            .build();
        webView.setWebViewClient(new WebViewClient() {
            @Override public android.webkit.WebResourceResponse shouldInterceptRequest(WebView view, android.webkit.WebResourceRequest request) {
                return assetLoader.shouldInterceptRequest(request.getUrl());
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                android.net.Uri uri = request.getUrl();
                if ("appassets.androidplatform.net".equals(uri.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean wantsAudio = false;
                    for (String resource : request.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) wantsAudio = true;
                    if (wantsAudio && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        pendingWebPermission = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION);
                    } else request.grant(request.getResources());
                });
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) { pendingWebPermission = null; }
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                } else {
                    pendingGeoOrigin = origin;
                    pendingGeoCallback = callback;
                    requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_PERMISSION);
                }
            }
        });
        webView.addJavascriptInterface(new NativeBridge(), "SmartSaathiAndroid");
        textToSpeech = new TextToSpeech(this, status -> {
            ttsReady = status == TextToSpeech.SUCCESS;
            if (ttsReady && pendingSpeech != null) speakPendingText();
        });
        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html");
    }

    @Override protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(WakeWordService.ACTION_WAKE_COMMAND);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(wakeReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(wakeReceiver, filter);
            receiverRegistered = true;
        }
    }


    private class NativeBridge {
        @JavascriptInterface public boolean isNativeAndroid() { return true; }
        @JavascriptInterface public void speakText(String text, String language, float rate, float pitch, float volume) {
            pendingSpeech = text == null ? "" : text;
            pendingSpeechLanguage = (language == null || language.trim().isEmpty()) ? "hi-IN" : language;
            pendingSpeechRate = Math.max(0.5f, Math.min(2.0f, rate));
            pendingSpeechPitch = Math.max(0.5f, Math.min(2.0f, pitch));
            pendingSpeechVolume = Math.max(0.0f, Math.min(1.0f, volume));
            runOnUiThread(() -> { if (ttsReady) speakPendingText(); });
        }
        @JavascriptInterface public void stopSpeaking() {
            runOnUiThread(() -> { try { if (textToSpeech != null) textToSpeech.stop(); } catch (Exception ignored) {} });
        }
        @JavascriptInterface public void startWakeListening(String language) {
            pendingLanguage = (language == null || language.trim().isEmpty()) ? "hi-IN" : language.trim();
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingWakeStart = true;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION);
                    Toast.makeText(MainActivity.this, "माइक्रोफ़ोन अनुमति माँगी गई है। अनुमति मिलने पर आवाज़ सेवा शुरू होगी।", Toast.LENGTH_LONG).show();
                    return;
                }
                startWakeService(pendingLanguage);
            });
        }
        @JavascriptInterface public void stopWakeListening() {
            runOnUiThread(() -> {
                Intent service = new Intent(MainActivity.this, WakeWordService.class);
                service.setAction(WakeWordService.ACTION_STOP);
                try { startService(service); } catch (Exception ignored) { stopService(new Intent(MainActivity.this, WakeWordService.class)); }
            });
        }
        @JavascriptInterface public void openApp(String spokenName) {
            runOnUiThread(() -> launchRequestedApp(spokenName == null ? "" : spokenName));
        }
        @JavascriptInterface public void openSettings(String settingName) {
            runOnUiThread(() -> launchRequestedSettings(settingName == null ? "" : settingName));
        }
        @JavascriptInterface public void dialNumber(String number) {
            runOnUiThread(() -> dialSafely(number == null ? "" : number));
        }
        @JavascriptInterface public void callContact(String contactName) {
            pendingContactQuery = contactName == null ? "" : contactName.trim();
            runOnUiThread(() -> {
                if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, CONTACT_PERMISSION);
                } else if (isGenericContactName(pendingContactQuery)) pickContactToDial();
                else findAndDialContact(pendingContactQuery);
            });
        }
        @JavascriptInterface public void openMapsRoute(String destination, String origin, String mode) {
            runOnUiThread(() -> {
                String travel = "walking".equalsIgnoreCase(mode) ? "walking" : "driving";
                String url = "https://www.google.com/maps/dir/?api=1&destination=" + Uri.encode(destination == null ? "" : destination);
                if (origin != null && !origin.trim().isEmpty()) url += "&origin=" + Uri.encode(origin.trim());
                Uri uri = Uri.parse(url + "&travelmode=" + travel);
                launchIntent(new Intent(Intent.ACTION_VIEW, uri), "Google Maps नहीं खुला।");
            });
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CONTACT_PICK_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Cursor c = null;
            try {
                c = getContentResolver().query(data.getData(), new String[]{ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null);
                if (c != null && c.moveToFirst()) dialSafely(c.getString(0));
                else showVoiceResult("चुने गए संपर्क का नंबर नहीं मिला।");
            } catch (Exception e) { showVoiceResult("संपर्क नहीं पढ़ पाए।"); }
            finally { if (c != null) c.close(); }
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == MIC_PERMISSION) {
            if (pendingWebPermission != null) {
                if (granted) pendingWebPermission.grant(pendingWebPermission.getResources()); else pendingWebPermission.deny();
                pendingWebPermission = null;
            }
            if (granted && webView != null) webView.evaluateJavascript("window.onNativeMicrophonePermission && window.onNativeMicrophonePermission(true)", null);
            if (pendingWakeStart) {
                pendingWakeStart = false;
                if (granted) startWakeService(pendingLanguage);
                else Toast.makeText(this, "माइक्रोफ़ोन अनुमति के बिना हैंड्स-फ्री नहीं चल सकता।", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == LOCATION_PERMISSION && pendingGeoCallback != null) {
            boolean locationGranted = false;
            for (int i = 0; i < permissions.length; i++) if ((Manifest.permission.ACCESS_FINE_LOCATION.equals(permissions[i]) || Manifest.permission.ACCESS_COARSE_LOCATION.equals(permissions[i])) && grantResults[i] == PackageManager.PERMISSION_GRANTED) locationGranted = true;
            pendingGeoCallback.invoke(pendingGeoOrigin, locationGranted, false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        } else if (requestCode == CONTACT_PERMISSION) {
            if (granted) { if (isGenericContactName(pendingContactQuery)) pickContactToDial(); else findAndDialContact(pendingContactQuery); }
            else showVoiceResult("कॉन्टैक्ट अनुमति नहीं मिली। कृपया फोन ऐप में संपर्क चुनकर कॉल करें।");
        }
    }

    private void showVoiceResult(String message) {
        if (webView != null) {
            String safe = org.json.JSONObject.quote(message);
            runOnUiThread(() -> webView.evaluateJavascript("window.handleNativeActionResult && window.handleNativeActionResult(" + safe + ")", null));
        } else Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private void launchRequestedApp(String spokenName) {
        String n = spokenName.toLowerCase(java.util.Locale.ROOT);
        String pkg = null;
        if (n.contains("google pay") || n.contains("gpay") || n.contains("गूगल पे")) pkg = "com.google.android.apps.nbu.paisa.user";
        else if (n.contains("paytm") || n.contains("पेटीएम")) pkg = "net.one97.paytm";
        else if (n.contains("phonepe") || n.contains("phone pe") || n.contains("फोनपे")) pkg = "com.phonepe.app";
        else if (n.contains("whatsapp") || n.contains("व्हाट्सऐप")) pkg = "com.whatsapp";
        else if (n.contains("youtube") || n.contains("यूट्यूब")) pkg = "com.google.android.youtube";
        else if (n.contains("chrome") || n.contains("क्रोम")) pkg = "com.android.chrome";
        else if (n.contains("maps") || n.contains("map") || n.contains("मैप")) pkg = "com.google.android.apps.maps";
        else if (n.contains("camera") || n.contains("कैमरा")) { launchIntent(new Intent("android.media.action.IMAGE_CAPTURE"), "कैमरा ऐप उपलब्ध नहीं है।"); return; }
        else if (n.contains("gallery") || n.contains("photos") || n.contains("गैलरी")) { launchIntent(new Intent(Intent.ACTION_VIEW, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI), "गैलरी ऐप नहीं खुल पाया।"); return; }
        else if (n.contains("phone") || n.contains("dialer") || n.contains("फोन")) { launchIntent(new Intent(Intent.ACTION_DIAL), "फोन ऐप नहीं खुल पाया।"); return; }
        else if (n.contains("message") || n.contains("sms") || n.contains("मैसेज")) { launchIntent(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING), "मैसेज ऐप नहीं खुल पाया।"); return; }
        if (pkg != null) {
            Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); launchIntent(i, "ऐप नहीं खुल पाया।"); }
            else launchIntent(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=" + Uri.encode(spokenName) + "&c=apps")), "यह ऐप फोन में इंस्टॉल नहीं है।");
            return;
        }
        launchIntent(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=" + Uri.encode(spokenName) + "&c=apps")), "ऐप खोज नहीं पाया।");
    }

    private void launchRequestedSettings(String spokenName) {
        String n = spokenName.toLowerCase(java.util.Locale.ROOT);
        String action = android.provider.Settings.ACTION_SETTINGS;
        if (n.contains("wifi") || n.contains("वाई-फाई") || n.contains("वाईफाई")) action = android.provider.Settings.ACTION_WIFI_SETTINGS;
        else if (n.contains("bluetooth") || n.contains("ब्लूटूथ")) action = android.provider.Settings.ACTION_BLUETOOTH_SETTINGS;
        else if (n.contains("location") || n.contains("लोकेशन") || n.contains("gps")) action = android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS;
        else if (n.contains("sound") || n.contains("आवाज़") || n.contains("ध्वनि")) action = android.provider.Settings.ACTION_SOUND_SETTINGS;
        else if (n.contains("battery") || n.contains("बैटरी")) action = android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS;
        else if (n.contains("display") || n.contains("brightness") || n.contains("स्क्रीन")) action = android.provider.Settings.ACTION_DISPLAY_SETTINGS;
        else if (n.contains("app permission") || n.contains("अनुमति")) action = android.provider.Settings.ACTION_APPLICATION_SETTINGS;
        launchIntent(new Intent(action), "यह सेटिंग सीधे नहीं खुल पाई। सेटिंग ऐप खोलें।");
    }

    private void dialSafely(String number) {
        String clean = number.replaceAll("[^+0-9]", "");
        if (!clean.matches("[+]?[0-9]{6,15}")) { showVoiceResult("कृपया सही फोन नंबर बोलें।"); return; }
        launchIntent(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + clean)), "डायलर नहीं खुल पाया।");
    }

    private boolean isGenericContactName(String query) {
        String n = query.toLowerCase(java.util.Locale.ROOT).trim();
        return n.isEmpty() || n.matches("(मेरा |मेरे |मेरी )?(दोस्त|यार|जान|जानू|बीबी|बीवी|बिबी|भाई|बहन|friend|best friend|jaan|jaanu|bibi|wife|husband|bro|sister)");
    }

    private void pickContactToDial() {
        try {
            Intent pick = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
            startActivityForResult(pick, CONTACT_PICK_REQUEST);
        } catch (Exception e) { showVoiceResult("संपर्क सूची नहीं खुली। Contacts ऐप खोलकर नंबर चुनें।"); }
    }

    private void findAndDialContact(String query) {
        if (query == null || query.trim().isEmpty()) { showVoiceResult("किसे कॉल करना है, उनका नाम बताइए।"); return; }
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        java.util.ArrayList<String> numbers = new java.util.ArrayList<>();
        Cursor c = null;
        try {
            c = getContentResolver().query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC");
            String q = query.toLowerCase(java.util.Locale.ROOT).replaceAll("^(मेरे|मेरी|मेरा|को|से|call|phone|कॉल|लगाओ|लगा दो|लगा)\\s*", "").trim();
            if (c != null) while (c.moveToNext()) {
                String name = c.getString(0), number = c.getString(1);
                if (name != null && number != null && name.toLowerCase(java.util.Locale.ROOT).contains(q)) { names.add(name + " — " + number); numbers.add(number); }
            }
        } catch (Exception ignored) { } finally { if (c != null) c.close(); }
        if (numbers.isEmpty()) { pickContactToDial(); return; }
        if (numbers.size() == 1) { dialSafely(numbers.get(0)); return; }
        String[] options = names.toArray(new String[0]);
        new AlertDialog.Builder(this).setTitle("किस संपर्क को कॉल करना है?").setItems(options, (d, which) -> dialSafely(numbers.get(which))).setNegativeButton("रद्द", null).show();
    }

    private void launchIntent(Intent intent, String error) {
        try { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent); }
        catch (Exception e) { showVoiceResult(error); }
    }

    private void startWakeService(String lang) {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION);
        }
        Intent service = new Intent(this, WakeWordService.class);
        service.setAction(WakeWordService.ACTION_START);
        service.putExtra(WakeWordService.EXTRA_LANGUAGE, lang);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service); else startService(service);
            Toast.makeText(this, "हैंड्स-फ्री सुनना शुरू किया जा रहा है। स्थायी सूचना दिखेगी।", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "सुनना शुरू नहीं हुआ। ऐप खुला रखें और अनुमति जाँचें।", Toast.LENGTH_LONG).show();
        }
    }

    private void speakPendingText() {
        if (!ttsReady || textToSpeech == null || pendingSpeech == null) return;
        try {
            textToSpeech.setLanguage(java.util.Locale.forLanguageTag(pendingSpeechLanguage));
            textToSpeech.setSpeechRate(pendingSpeechRate);
            textToSpeech.setPitch(pendingSpeechPitch);
            Bundle params = new Bundle();
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, pendingSpeechVolume);
            textToSpeech.speak(pendingSpeech, TextToSpeech.QUEUE_FLUSH, params, "smart-saathi-" + System.currentTimeMillis());
        } catch (Exception ignored) {}
        pendingSpeech = null;
    }

    @Override public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (receiverRegistered) {
            try { unregisterReceiver(wakeReceiver); } catch (Exception ignored) {}
            receiverRegistered = false;
        }
        if (webView != null) { webView.removeJavascriptInterface("SmartSaathiAndroid"); webView.destroy(); webView = null; }
        if (textToSpeech != null) { try { textToSpeech.stop(); textToSpeech.shutdown(); } catch (Exception ignored) {} textToSpeech = null; }
                super.onDestroy();
    }
}
