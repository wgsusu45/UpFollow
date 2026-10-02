package com.upfollow.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.net.URLEncoder;

public class MainActivity extends Activity {

    private WebView mainWebView;
    private WebView workerWebView;

    private static final String HOSTING_DASHBOARD = "https://follow2follow.shop/index.php";
    private static final String IG_LOGIN_URL = "https://www.instagram.com/accounts/login/";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final String CHANNEL_ID = "upfollow_automation_channel";
    private static final int NOTIFICATION_ID = 1001;
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;

    private String currentTaskType = "follow";
    private boolean isAddingSecondaryAccount = false;
    private String hardwareDeviceId = "";

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            hardwareDeviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception e) {
            hardwareDeviceId = "DEV_FALLBACK";
        }

        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UpFollow:WakeLock");
            }
        } catch (Exception ignored) {}

        createNotificationChannel();

        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        mainWebView = findViewById(R.id.webView);
        setupWebView(mainWebView);

        workerWebView = new WebView(this);
        setupWebView(workerWebView);
        
        ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(1, 1);
        workerWebView.setLayoutParams(params);
        workerWebView.setAlpha(0.01f);
        
        ViewGroup rootView = (ViewGroup) findViewById(android.R.id.content);
        if (rootView != null) {
            rootView.addView(workerWebView);
        }

        mainWebView.addJavascriptInterface(new MainAppInterface(), "Android");
        workerWebView.addJavascriptInterface(new WorkerAppInterface(), "WorkerBridge");

        mainWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                if (url != null && url.contains("force_authentication=1")) {
                    isAddingSecondaryAccount = true;
                }

                if (url != null && (url.contains("instagram.com/?") || url.equals("https://www.instagram.com/"))) {
                    String cookies = CookieManager.getInstance().getCookie("https://www.instagram.com");
                    if (cookies != null && cookies.contains("sessionid")) {
                        try {
                            String encoded = URLEncoder.encode(cookies, "UTF-8");
                            String dest = HOSTING_DASHBOARD + "?hw_id=" + hardwareDeviceId;
                            if (isAddingSecondaryAccount) {
                                isAddingSecondaryAccount = false;
                                mainWebView.loadUrl(dest + "&add_cookies=" + encoded);
                            } else {
                                mainWebView.loadUrl(dest + "&cookies=" + encoded);
                            }
                        } catch (Exception e) {
                            mainWebView.loadUrl(HOSTING_DASHBOARD + "?hw_id=" + hardwareDeviceId);
                        }
                    }
                }
            }
        });

        // Worker: Immediate script injection with active polling
        workerWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                
                // Instant URL level checkpoint detection
                if (url != null && (url.contains("/challenge/") || url.contains("/suspended/") || url.contains("/checkpoint/"))) {
                    mainWebView.evaluateJavascript("if(window.onWorkerResult) window.onWorkerResult(false, 'Blocked: Account Checkpoint / Suspended');", null);
                    return;
                }

                if ("like".equalsIgnoreCase(currentTaskType)) {
                    injectFastLikeScript(view);
                } else {
                    injectFastFollowScript(view);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                mainWebView.evaluateJavascript("if(window.onWorkerResult) window.onWorkerResult(false, 'Page load error');", null);
            }
        });

        String cookies = CookieManager.getInstance().getCookie("https://www.instagram.com");
        if (cookies != null && cookies.contains("sessionid")) {
            mainWebView.loadUrl(HOSTING_DASHBOARD + "?hw_id=" + hardwareDeviceId);
        } else {
            mainWebView.loadUrl(HOSTING_DASHBOARD + "?hw_id=" + hardwareDeviceId + "&check_saved=1");
        }
    }

    private void setupWebView(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(USER_AGENT);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);
    }

    // FAST FOLLOW SCRIPT (250ms Polling + Instant Checkpoint Detection)
    private void injectFastFollowScript(WebView view) {
        String js = "(function() {" +
            "   var startTime = Date.now();" +
            "   var timer = setInterval(function() {" +
            "       var url = window.location.href.toLowerCase();" +
            "       var text = (document.body ? document.body.innerText : '').toLowerCase();" +
            "       // 1. Instant Checkpoint / Block / Human Verification Detection" +
            "       if (url.includes('/challenge/') || url.includes('/suspended/') || url.includes('/checkpoint/') || " +
            "           text.includes('confirm you\\'re human') || text.includes('try again later') || " +
            "           text.includes('action blocked') || text.includes('we limit how often') || " +
            "           text.includes('help us confirm') || text.includes('suspended')) {" +
            "           clearInterval(timer);" +
            "           window.WorkerBridge.onTaskResult(false, 'Blocked: Action Limited / Checkpoint');" +
            "           return;" +
            "       }" +
            "       // 2. Dismiss Popups" +
            "       var popups = document.querySelectorAll('button, div[role=\"button\"]');" +
            "       for (var i = 0; i < popups.length; i++) {" +
            "           var pt = (popups[i].innerText || '').trim().toLowerCase();" +
            "           if (pt === 'not now' || pt === 'cancel' || pt === 'allow all' || pt === 'accept') {" +
            "               popups[i].click();" +
            "           }" +
            "       }" +
            "       // 3. Search Follow Button" +
            "       var buttons = Array.from(document.querySelectorAll('header button, main button, button, div[role=\"button\"]'));" +
            "       var followBtn = buttons.find(b => {" +
            "           var t = (b.innerText || b.textContent || '').trim();" +
            "           return /^follow$/i.test(t) || /^follow back$/i.test(t) || t === 'फॉलो करें' || t === 'Seguir';" +
            "       });" +
            "       if (followBtn) {" +
            "           clearInterval(timer);" +
            "           followBtn.click();" +
            "           ['touchstart', 'touchend', 'mousedown', 'mouseup', 'click'].forEach(function(evt) {" +
            "               followBtn.dispatchEvent(new MouseEvent(evt, { bubbles: true, cancelable: true, view: window }));" +
            "           });" +
            "           setTimeout(function() {" +
            "               var afterText = (document.body ? document.body.innerText : '').toLowerCase();" +
            "               if (afterText.includes('try again later') || afterText.includes('action blocked')) {" +
            "                   window.WorkerBridge.onTaskResult(false, 'Blocked: Action Limited');" +
            "               } else {" +
            "                   window.WorkerBridge.onTaskResult(true, 'Success');" +
            "               }" +
            "           }, 400);" +
            "           return;" +
            "       }" +
            "       // 4. Check if already following" +
            "       var isFollowing = buttons.some(b => {" +
            "           var t = (b.innerText || b.textContent || '').trim();" +
            "           return (/^following$/i.test(t) || /^requested$/i.test(t)) && b.closest('header, main');" +
            "       });" +
            "       if (isFollowing) {" +
            "           clearInterval(timer);" +
            "           window.WorkerBridge.onTaskResult(false, 'Already Following');" +
            "           return;" +
            "       }" +
            "       // Timeout after 4.5 seconds" +
            "       if (Date.now() - startTime > 4500) {" +
            "           clearInterval(timer);" +
            "           window.WorkerBridge.onTaskResult(false, 'Follow button not found');" +
            "       }" +
            "   }, 250);" +
            "})();";

        view.evaluateJavascript(js, null);
    }

    // FAST LIKE SCRIPT (Reels + Posts)
    private void injectFastLikeScript(WebView view) {
        String js = "(function() {" +
            "   var startTime = Date.now();" +
            "   var timer = setInterval(function() {" +
            "       var url = window.location.href.toLowerCase();" +
            "       var text = (document.body ? document.body.innerText : '').toLowerCase();" +
            "       // 1. Instant Checkpoint / Block Detection" +
            "       if (url.includes('/challenge/') || url.includes('/suspended/') || url.includes('/checkpoint/') || " +
            "           text.includes('confirm you\\'re human') || text.includes('try again later') || " +
            "           text.includes('action blocked') || text.includes('we limit how often')) {" +
            "           clearInterval(timer);" +
            "           window.WorkerBridge.onTaskResult(false, 'Blocked: Action Limited / Checkpoint');" +
            "           return;" +
            "       }" +
            "       // 2. Dismiss Popups" +
            "       var popups = document.querySelectorAll('button, div[role=\"button\"]');" +
            "       for (var i = 0; i < popups.length; i++) {" +
            "           var pt = (popups[i].innerText || '').trim().toLowerCase();" +
            "           if (pt === 'not now' || pt === 'cancel') popups[i].click();" +
            "       }" +
            "       // 3. Check if already liked" +
            "       var unlike = document.querySelector('svg[aria-label=\"Unlike\"], svg[aria-label=\"पसंद रद्द करें\"]');" +
            "       if (unlike) {" +
            "           clearInterval(timer);" +
            "           window.WorkerBridge.onTaskResult(false, 'Already Liked');" +
            "           return;" +
            "       }" +
            "       // 4. Direct Like heart SVG search" +
            "       var likeSvg = document.querySelector('svg[aria-label=\"Like\"], svg[aria-label=\"पसंद करें\"], svg[aria-label=\"Me gusta\"]');" +
            "       if (likeSvg) {" +
            "           clearInterval(timer);" +
            "           var btn = likeSvg.closest('button') || likeSvg.closest('div[role=\"button\"]') || likeSvg.closest('span[role=\"button\"]') || likeSvg.parentElement;" +
            "           btn.click();" +
            "           ['touchstart', 'touchend', 'mousedown', 'mouseup', 'click'].forEach(function(evt) {" +
            "               btn.dispatchEvent(new MouseEvent(evt, { bubbles: true, cancelable: true, view: window }));" +
            "           });" +
            "           setTimeout(function() {" +
            "               window.WorkerBridge.onTaskResult(true, 'Success');" +
            "           }, 400);" +
            "           return;" +
            "       }" +
            "       // 5. Reels Video Double-Tap" +
            "       var videoEl = document.querySelector('video');" +
            "       if (videoEl) {" +
            "           clearInterval(timer);" +
            "           videoEl.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true, view: window }));" +
            "           setTimeout(function() {" +
            "               window.WorkerBridge.onTaskResult(true, 'Success');" +
            "           }, 400);" +
            "           return;" +
            "       }" +
            "       // 6. Profile fallback: open first post" +
            "       var postLink = document.querySelector('main a[href^=\"/p/\"], main a[href^=\"/reel/\"]');" +
            "       if (postLink) {" +
            "           clearInterval(timer);" +
            "           window.location.href = postLink.href;" +
            "           return;" +
            "       }" +
            "       // Timeout after 4.5 seconds" +
            "       if (Date.now() - startTime > 4500) {" +
            "           clearInterval(timer);" +
            "           window.WorkerBridge.onTaskResult(false, 'Like button not found');" +
            "       }" +
            "   }, 250);" +
            "})();";

        view.evaluateJavascript(js, null);
    }

    private void createNotificationChannel() {
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Automation Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Shows live automation stats in status bar");
            channel.setShowBadge(false);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private void showOrUpdateNotification(int accounts, int tasks, int coins) {
        if (notificationManager == null) return;

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        builder.setContentTitle("UpFollow Running")
               .setContentText("Accounts: " + accounts + " Active  |  Tasks: " + tasks + "  |  Coins: +" + coins)
               .setSmallIcon(android.R.drawable.stat_notify_sync)
               .setOngoing(true);

        notificationManager.notify(NOTIFICATION_ID, builder.build());
    }

    private void clearAutomationNotification() {
        if (notificationManager != null) {
            notificationManager.cancel(NOTIFICATION_ID);
        }
    }

    public class MainAppInterface {
        @JavascriptInterface
        public void executeBrowserAction(String target, String taskType, String mediaId) {
            new Handler(Looper.getMainLooper()).post(() -> {
                currentTaskType = taskType;
                String cleanTarget = target.replace("@", "").trim();

                if ("like".equalsIgnoreCase(taskType)) {
                    if (mediaId != null && !mediaId.isEmpty() && !mediaId.equals("25025320")) {
                        if (mediaId.startsWith("http")) {
                            workerWebView.loadUrl(mediaId);
                        } else if (mediaId.length() <= 12) {
                            workerWebView.loadUrl("https://www.instagram.com/reel/" + mediaId + "/");
                        } else {
                            workerWebView.loadUrl("https://www.instagram.com/p/" + mediaId + "/");
                        }
                    } else {
                        workerWebView.loadUrl("https://www.instagram.com/" + cleanTarget + "/");
                    }
                } else {
                    workerWebView.loadUrl("https://www.instagram.com/" + cleanTarget + "/");
                }
            });
        }

        @JavascriptInterface
        public void setKeepScreenOn(boolean keepOn) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (keepOn) {
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    if (wakeLock != null && !wakeLock.isHeld()) {
                        wakeLock.acquire(12 * 60 * 60 * 1000L);
                    }
                } else {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    if (wakeLock != null && wakeLock.isHeld()) {
                        wakeLock.release();
                    }
                }
            });
        }

        @JavascriptInterface
        public void updateNotification(int accounts, int tasks, int coins) {
            new Handler(Looper.getMainLooper()).post(() -> {
                showOrUpdateNotification(accounts, tasks, coins);
            });
        }

        @JavascriptInterface
        public void stopNotification() {
            new Handler(Looper.getMainLooper()).post(() -> {
                clearAutomationNotification();
            });
        }
    }

    public class WorkerAppInterface {
        @JavascriptInterface
        public void onTaskResult(boolean success, String message) {
            new Handler(Looper.getMainLooper()).post(() -> {
                mainWebView.evaluateJavascript("window.onWorkerResult(" + success + ", '" + message.replace("'", "\\'") + "');", null);
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (mainWebView.canGoBack()) {
            mainWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }
}
