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
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
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

    private String currentTaskType = "follow";
    // Flag to detect if adding secondary account vs main login
    private boolean isAddingSecondaryAccount = false;

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

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
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1, 1);
        workerWebView.setLayoutParams(params);
        workerWebView.setAlpha(0.01f);
        
        ViewGroup rootView = (ViewGroup) findViewById(android.R.id.content);
        rootView.addView(workerWebView);

        mainWebView.addJavascriptInterface(new MainAppInterface(), "Android");
        workerWebView.addJavascriptInterface(new WorkerAppInterface(), "WorkerBridge");

        mainWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                // Detect when adding another account is requested
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
                                // Append as secondary account
                                mainWebView.loadUrl(HOSTING_DASHBOARD + "?add_cookies=" + encoded);
                            } else {
                                // Primary Login
                                mainWebView.loadUrl(HOSTING_DASHBOARD + "?cookies=" + encoded);
                            }
                        } catch (Exception e) {
                            mainWebView.loadUrl(HOSTING_DASHBOARD);
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
                        injectLikeScript(view);
                    } else {
                        injectFollowScript(view);
                    }
                }, 2500);
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
            "           var e = new MouseEvent(evt, { bubbles: true, cancelable: true, view: window });" +
            "           followBtn.dispatchEvent(e);" +
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

    private void injectLikeScript(WebView view) {
        String js = "(function() {" +
            "   try {" +
            "       var unlikeSvg = document.querySelector('svg[aria-label=\"Unlike\"]') || document.querySelector('svg[aria-label=\"पसंद रद्द करें\"]');" +
            "       if (unlikeSvg) {" +
            "           window.WorkerBridge.onTaskResult(true, 'Success');" +
            "           return;" +
            "       }" +
            "       var likeSvg = document.querySelector('svg[aria-label=\"Like\"]') || document.querySelector('svg[aria-label=\"पसंद करें\"]');" +
            "       if (likeSvg) {" +
            "           var btn = likeSvg.closest('button') || likeSvg.closest('div[role=\"button\"]') || likeSvg.parentElement;" +
            "           ['mousedown', 'mouseup', 'click'].forEach(function(evt) {" +
            "               var e = new MouseEvent(evt, { bubbles: true, cancelable: true, view: window });" +
            "               btn.dispatchEvent(e);" +
            "           });" +
            "           setTimeout(function() {" +
            "               window.WorkerBridge.onTaskResult(true, 'Success');" +
            "           }, 1000);" +
            "           return;" +
            "       }" +
            "       var firstPost = document.querySelector('article a[href*=\"/p/\"]') || document.querySelector('a[href*=\"/p/\"]');" +
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
            "       window.WorkerBridge.onTaskResult(false, 'Like button not found');" +
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
}
