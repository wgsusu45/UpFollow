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
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

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

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UpFollow:WakeLock");
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

        // 1. Worker WebView (Purana 1px working setup)
        workerWebView = new WebView(this);
        setupWebView(workerWebView);
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1, 1);
        workerWebView.setLayoutParams(params);
        workerWebView.setAlpha(0.01f);
        
        ViewGroup rootView = (ViewGroup) findViewById(android.R.id.content);
        if (rootView != null) {
            rootView.addView(workerWebView);
        }

        // Native Bridges
        mainWebView.addJavascriptInterface(new MainAppInterface(), "Android");
        workerWebView.addJavascriptInterface(new WorkerAppInterface(), "WorkerBridge");

        // Main WebView Client
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
                            if (isAddingSecondaryAccount) {
                                isAddingSecondaryAccount = false;
                                mainWebView.loadUrl(HOSTING_DASHBOARD + "?add_cookies=" + encoded);
                            } else {
                                mainWebView.loadUrl(HOSTING_DASHBOARD + "?cookies=" + encoded);
                            }
                        } catch (Exception e) {
                            mainWebView.loadUrl(HOSTING_DASHBOARD);
                        }
                    }
                }
            }
        });

        // Worker WebView Client (Purana working timing + Challenge check)
        workerWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                // Challenge / Checkpoint URL level detection
                if (url != null && (url.contains("/challenge/") || url.contains("/suspended/") || url.contains("/checkpoint/"))) {
                    mainWebView.evaluateJavascript("if(window.onWorkerResult) window.onWorkerResult(false, 'Blocked: Challenge / Checkpoint');", null);
                    return;
                }

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if ("like".equalsIgnoreCase(currentTaskType)) {
                        injectLikeScript(view);
                    } else {
                        injectFollowScript(view);
                    }
                }, 2000);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                mainWebView.evaluateJavascript("if(window.onWorkerResult) window.onWorkerResult(false, 'Page error');", null);
            }
        });

        // Auto login check
        String cookies = CookieManager.getInstance().getCookie("https://www.instagram.com");
        if (cookies != null && cookies.contains("sessionid")) {
            mainWebView.loadUrl(HOSTING_DASHBOARD);
        } else {
            mainWebView.loadUrl(IG_LOGIN_URL);
        }
    }

    private void setupWebView(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(USER_AGENT);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(wv, true);
    }

    // 101% Working Follow Script + Challenge Auto-Detector
    private void injectFollowScript(WebView view) {
        String js = "(function() {" +
            "   try {" +
            "       var text = (document.body ? document.body.innerText : '').toLowerCase();" +
            "       // Challenge / Block Detection" +
            "       if (text.includes('confirm you\\'re human') || text.includes('try again later') || text.includes('action blocked') || text.includes('suspended')) {" +
            "           window.WorkerBridge.onTaskResult(false, 'Blocked: Action Limit / Challenge');" +
            "           return;" +
            "       }" +
            "       var popups = Array.from(document.querySelectorAll('button, div[role=\"button\"]'));" +
            "       popups.forEach(p => {" +
            "           var pt = (p.innerText || '').trim().toLowerCase();" +
            "           if (pt === 'not now' || pt === 'cancel' || pt === 'allow all' || pt === 'accept') p.click();" +
            "       });" +
            "       var buttons = Array.from(document.querySelectorAll('header button, main button, button, div[role=\"button\"]'));" +
            "       var followBtn = buttons.find(b => {" +
            "           var t = (b.innerText || b.textContent || '').trim().toLowerCase();" +
            "           return t === 'follow' || t === 'follow back' || t === 'फॉलो करें';" +
            "       });" +
            "       if (!followBtn) {" +
            "           var isFollowing = buttons.some(b => {" +
            "               var t = (b.innerText || b.textContent || '').trim().toLowerCase();" +
            "               return t === 'following' || t === 'requested';" +
            "           });" +
            "           if (isFollowing) {" +
            "               window.WorkerBridge.onTaskResult(true, 'Already Following');" +
            "               return;" +
            "           }" +
            "           window.WorkerBridge.onTaskResult(false, 'Follow button not found');" +
            "           return;" +
            "       }" +
            "       followBtn.click();" +
            "       ['mousedown', 'mouseup', 'click'].forEach(function(evt) {" +
            "           followBtn.dispatchEvent(new MouseEvent(evt, { bubbles: true, cancelable: true, view: window }));" +
            "       });" +
            "       setTimeout(function() {" +
            "           window.WorkerBridge.onTaskResult(true, 'Clicked Successfully');" +
            "       }, 800);" +
            "   } catch (err) {" +
            "       window.WorkerBridge.onTaskResult(false, 'JS Error: ' + err.message);" +
            "   }" +
            "})();";

        view.evaluateJavascript(js, null);
    }

    // 101% Working Like & Reels Script + Challenge Auto-Detector
    private void injectLikeScript(WebView view) {
        String js = "(function() {" +
            "   try {" +
            "       var text = (document.body ? document.body.innerText : '').toLowerCase();" +
            "       if (text.includes('confirm you\\'re human') || text.includes('try again later') || text.includes('action blocked') || text.includes('suspended')) {" +
            "           window.WorkerBridge.onTaskResult(false, 'Blocked: Action Limit / Challenge');" +
            "           return;" +
            "       }" +
            "       var popups = Array.from(document.querySelectorAll('button, div[role=\"button\"]'));" +
            "       popups.forEach(p => {" +
            "           var pt = (p.innerText || '').trim().toLowerCase();" +
            "           if (pt === 'not now' || pt === 'cancel') p.click();" +
            "       });" +
            "       var unlike = document.querySelector('svg[aria-label=\"Unlike\"], svg[aria-label=\"पसंद रद्द करें\"]');" +
            "       if (unlike) {" +
            "           window.WorkerBridge.onTaskResult(true, 'Already Liked');" +
            "           return;" +
            "       }" +
            "       var likeSvg = document.querySelector('svg[aria-label=\"Like\"], svg[aria-label=\"पसंद करें\"]');" +
            "       if (likeSvg) {" +
            "           var btn = likeSvg.closest('button') || likeSvg.closest('div[role=\"button\"]') || likeSvg.closest('span[role=\"button\"]') || likeSvg.parentElement;" +
            "           btn.click();" +
            "           ['mousedown', 'mouseup', 'click'].forEach(function(evt) {" +
            "               btn.dispatchEvent(new MouseEvent(evt, { bubbles: true, cancelable: true, view: window }));" +
            "           });" +
            "           setTimeout(function() {" +
            "               window.WorkerBridge.onTaskResult(true, 'Post Liked Successfully');" +
            "           }, 800);" +
            "           return;" +
            "       }" +
            "       var videoEl = document.querySelector('video');" +
            "       if (videoEl) {" +
            "           videoEl.dispatchEvent(new MouseEvent('dblclick', { bubbles: true, cancelable: true, view: window }));" +
            "           setTimeout(function() {" +
            "               window.WorkerBridge.onTaskResult(true, 'Post Liked Successfully');" +
            "           }, 800);" +
            "           return;" +
            "       }" +
            "       var postLink = document.querySelector('main a[href*=\"/p/\"], main a[href*=\"/reel/\"]');" +
            "       if (postLink) {" +
            "           window.location.href = postLink.href;" +
            "           return;" +
            "       }" +
            "       window.WorkerBridge.onTaskResult(false, 'Like button not found');" +
            "   } catch (err) {" +
            "       window.WorkerBridge.onTaskResult(false, 'JS Error: ' + err.message);" +
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

    // App Bridges
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
        public void executeBrowserFollow(String target) {
            executeBrowserAction(target, "follow", "");
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
