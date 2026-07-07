package com.zenmahjong.app;

import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebSettings;
import android.webkit.WebView;

/** Full-screen WebView shell around the offline HTML5 game in assets/. */
public class MainActivity extends Activity {
  private WebView web;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    web = new WebView(this);
    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);   // localStorage: best score, sound setting
    s.setAllowFileAccess(true);     // file:///android_asset on API 30+
    web.setBackgroundColor(0xFF0A2E22);
    web.loadUrl("file:///android_asset/index.html");
    setContentView(web);
  }

  @Override
  public void onBackPressed() {
    if (web.canGoBack()) web.goBack();
    else super.onBackPressed();
  }

  @Override
  protected void onDestroy() {
    web.destroy();
    super.onDestroy();
  }
}
