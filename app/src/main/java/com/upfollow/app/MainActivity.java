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
import android.util.Log;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
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

        // Hardware Device ID (Persistent across app installs)
        try {
            hardwareDeviceId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Exception e) {
            hardwareDeviceId = "DEV_FALLBACK";
        }

        // WakeLock Setup (Screen & CPU keep awake)
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UpFollow:WakeLock");
            }
        } catch (Exception ignored) {}

        // Notification Channel
        createNotificationChannel();

        // Android 13+ Notification Permission Check
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        mainWebView = findViewById(R.id.webView);
        setupWebView(mainWebView);

        // Worker WebView (1px Off-screen safe layout)
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

        workerWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if ("like".equalsIgnoreCase(currentTaskType)) {
                        injectLikeAndReelsScript(view);
                    } else {
                        injectFollowScript(view);
                    }
                }, 2500);
            }
        });

        // Launch Check
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

    private void injectFollowScript(WebView view) {
        String js = "(function() {" +
            "   try {" +
            "       var buttons = Array.from(document.querySelectorAll('button'));" +
            "       var followBtn = buttons.find(b => {" +
            "           var t = (b.innerText || b.textContent || '').trim().toLowerCase();" +
            "           return t === 'follow' || t === 'follow back';" +
            "       });" +
            "       if (!followBtn) {" +
            "           var isFollowing = buttons.some(b => {" +
            "               var t = (b.innerText || b.textContent || '').trim().toLowerCase();" +
            "               return t === 'following' || t === 'requested';" +
            "           });" +
            "           if (isFollowing) {" +
            "               window.WorkerBridge.onTaskResult(true, 'Success');" +
            "               return;" +
            "           }" +
            "           window.WorkerBridge.onTaskResult(false, 'Follow button not found');" +
            "           return;" +
            "       }" +
            "       ['mousedown', 'mouseup', 'click'].forEach(function(evt) {" +
            "           followBtn.dispatchEvent(new MouseEvent(evt, { bubbles: true, cancelable: true, view: window }));" +
            "       });" +
            "       setTimeout(function() {" +
            "           window.WorkerBridge.onTaskResult(true, 'Success');" +
            "       }, 1000);" +
            "   } catch (err) {" +
            "       window.WorkerBridge.onTaskResult(false, 'Action error');" +
            "   }" +
            "})();";

        view.evaluateJavascript(js, null);
    }

    private void injectLikeAndReelsScript(WebView view) {
        String js = "(function() {" +
            "   try {" +
            "       var unlike = document.querySelector('svg[aria-label=\"Unlike\"]') || document.querySelector('svg[aria-label=\"पसंद रद्द करें\"]');" +
            "       if (unlike) {" +
            "           window.WorkerBridge.onTaskResult(true, 'Success');" +
            "           return;" +
            "       }" +
            "       var likeSvg = document.querySelector('svg[aria-label=\"Like\"]') || document.querySelector('svg[aria-label=\"पसंद करें\"]');" +
            "       if (likeSvg) {" +
            "           var btn = likeSvg.closest('button') || likeSvg.closest('div[role=\"button\"]') || likeSvg.parentElement;" +
            "           ['mousedown', 'mouseup', 'click'].forEach(function(evt) {" +
            "               btn.dispatchEvent(new MouseEvent(evt, { bubbles: true, cancelable: true, view: window }));" +
            "           });" +
            "           setTimeout(function() {" +
            "               window.WorkerBridge.onTaskResult(true, 'Success');" +
            "           }, 1000);" +
            "           return;" +
            "       }" +
            "       var videoEl = document.querySelector('video');" +
            "       if (videoEl) {" +
            "           var dblClick = new MouseEvent('dblclick', { bubbles: true, cancelable: true, view: window });" +
            "           videoEl.dispatchEvent(dblClick);" +
            "           setTimeout(function() {" +
            "               window.WorkerBridge.onTaskResult(true, 'Success');" +
            "           }, 1000);" +
            "           return;" +
            "       }" +
            "       var firstPost = document.querySelector('article a[href*=\"/p/\"]') || document.querySelector('article a[href*=\"/reel/\"]');" +
            "       if (firstPost) {" +
            "           firstPost.click();" +
            "           setTimeout(function() {" +
            "               var modalLike = document.querySelector('svg[aria-label=\"Like\"]');" +
            "               if (modalLike) {" +
            "                   var mBtn = modalLike.closest('button') || modalLike.parentElement;" +
            "                   ['mousedown', 'mouseup', 'click'].forEach(function(evt) {" +
            "                       mBtn.dispatchEvent(new MouseEvent(evt, { bubbles: true, cancelable: true, view: window }));" +
            "                   });" +
            "                   window.WorkerBridge.onTaskResult(true, 'Success');" +
            "               } else {" +
            "                   window.WorkerBridge.onTaskResult(false, 'Like button not found');" +
            "               }" +
            "           }, 2000);" +
            "           return;" +
            "       }" +
            "       window.WorkerBridge.onTaskResult(false, 'Post not found');" +
            "   } catch (err) {" +
            "       window.WorkerBridge.onTaskResult(false, 'Action error');" +
            "   }" +
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

        // System built-in sync icon (Zero custom file dependency)
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
