package com.steelballrun.player;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * STEEL BALL RUN - a fixed, full-screen shell around the web player (assets/index.html).
 * No browser chrome, no pull-to-refresh, no overscroll bounce, no pinch zoom.
 */
public class MainActivity extends Activity {

    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";
    private static final int REQ_FILE = 11;
    private static final int REQ_TREE = 12;
    private static final int REQ_NOTIF = 13;
    private static final int MODE_FOLDER = 2; // Chromium "upload folder" mode (<input webkitdirectory>)

    static volatile WebView sWeb;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private WebView web;
    private ImageView splash;
    private ValueCallback<Uri[]> fileCb;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private boolean splashGone = false;

    /** Run JavaScript inside the player from anywhere (used by the media notification). */
    static void runJs(final String script) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                WebView w = sWeb;
                if (w != null) {
                    try {
                        w.evaluateJavascript(script, null);
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF1B110C);

        web = new WebView(this);
        sWeb = web;
        configureWebView();
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // The cover art covers the screen until the player has drawn its first frame.
        splash = new ImageView(this);
        splash.setScaleType(ImageView.ScaleType.CENTER_CROP);
        splash.setImageResource(R.drawable.cover_art);
        root.addView(splash, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }

        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                hideSplash();
            }
        }, 5000); // safety net

        web.loadUrl(START_URL);
    }

    private void hideSplash() {
        if (splashGone || splash == null) return;
        splashGone = true;
        splash.animate().alpha(0f).setDuration(450).withEndAction(new Runnable() {
            @Override
            public void run() {
                root.removeView(splash);
            }
        }).start();
    }

    // ------------------------------------------------------------------ WebView

    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setTextZoom(100);
        s.setLoadWithOverviewMode(false);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        // Keep it steady: no bounce, no scrollbars, no background flash.
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setBackgroundColor(0xFF1B110C);

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        web.addJavascriptInterface(new Bridge(), "AndroidMedia");

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain(HOST)
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                if (!r.isForMainFrame()) return false;
                Uri u = r.getUrl();
                if (HOST.equals(u.getHost())) return false;
                openExternal(u);
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                MAIN.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        hideSplash();
                    }
                }, 650);
            }

            @Override
            public boolean onRenderProcessGone(WebView v, RenderProcessGoneDetail d) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        recreate();
                    }
                });
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                try {
                    if (p.getMode() == MODE_FOLDER) {
                        // <input webkitdirectory>: let the user pick a folder, then hand over every song inside it.
                        Intent t = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                        t.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                        startActivityForResult(t, REQ_TREE);
                    } else {
                        Intent i;
                        try {
                            i = p.createIntent();
                        } catch (Exception e) {
                            i = new Intent(Intent.ACTION_GET_CONTENT);
                            i.addCategory(Intent.CATEGORY_OPENABLE);
                            i.setType("*/*");
                        }
                        if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                        }
                        startActivityForResult(i, REQ_FILE);
                    }
                } catch (Exception e) {
                    fileCb = null;
                    cb.onReceiveValue(null);
                }
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                // window.open / target=_blank -> open in the phone's browser, never inside the player.
                final WebView tmp = new WebView(MainActivity.this);
                tmp.setWebViewClient(new WebViewClient() {
                    private boolean done = false;

                    private void go(Uri u) {
                        if (done) return;
                        done = true;
                        if (u != null && !"about:blank".equals(u.toString())) openExternal(u);
                        MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                tmp.destroy();
                            }
                        });
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                        go(r.getUrl());
                        return true;
                    }

                    @Override
                    public void onPageStarted(WebView v, String url, Bitmap favicon) {
                        if (url != null) go(Uri.parse(url));
                    }
                });
                WebView.WebViewTransport tr = (WebView.WebViewTransport) resultMsg.obj;
                tr.setWebView(tmp);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) {
                    cb.onCustomViewHidden();
                    return;
                }
                customView = view;
                customCb = cb;
                view.setBackgroundColor(Color.BLACK);
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.GONE);
                immersive(true);
            }

            @Override
            public void onHideCustomView() {
                hideCustomView();
            }
        });

        web.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String ua, String cd, String mime, long len) {
                if (url != null && url.startsWith("http")) openExternal(Uri.parse(url));
            }
        });
    }

    private void hideCustomView() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        web.setVisibility(View.VISIBLE);
        if (customCb != null) customCb.onCustomViewHidden();
        customCb = null;
        immersive(false);
    }

    @SuppressWarnings("deprecation")
    private void immersive(boolean on) {
        View d = getWindow().getDecorView();
        if (on) {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        } else {
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private void openExternal(Uri u) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            toast("No app can open this link");
        } catch (Exception ignored) {
        }
    }

    private void toast(final String msg) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ------------------------------------------------------------------ activity plumbing

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FILE) {
            if (fileCb != null) {
                fileCb.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(res, data));
                fileCb = null;
            }
        } else if (req == REQ_TREE) {
            if (res == RESULT_OK && data != null && data.getData() != null) {
                scanTree(data.getData());
            } else if (fileCb != null) {
                fileCb.onReceiveValue(null);
                fileCb = null;
            }
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (customView != null) {
            hideCustomView();
            return;
        }
        web.evaluateJavascript(
                "(function(){try{return window.__sbrBack?window.__sbrBack():false}catch(e){return false}})()",
                new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String v) {
                        // Nothing left to close inside the player: send the app to the background
                        // instead of killing it, so the music keeps playing.
                        if (!"true".equals(v)) moveTaskToBack(true);
                    }
                });
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        web.resumeTimers();
        web.evaluateJavascript("window.__sbrPush&&window.__sbrPush()", null);
    }

    // NOTE: onPause()/onStop() deliberately do NOT pause the WebView - that is what keeps the music playing.

    @Override
    protected void onDestroy() {
        if (sWeb == web) sWeb = null;
        if (isFinishing()) {
            MediaService s = MediaService.inst;
            if (s != null) s.closeAll();
        }
        try {
            root.removeAllViews();
            web.destroy();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ folder scan (webkitdirectory)

    private void scanTree(final Uri tree) {
        toast("Scanning folder…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Uri> found = new ArrayList<>();
                try {
                    walk(tree, DocumentsContract.getTreeDocumentId(tree), found, 0);
                } catch (Exception ignored) {
                }
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fileCb != null) {
                            fileCb.onReceiveValue(found.isEmpty() ? null : found.toArray(new Uri[0]));
                            fileCb = null;
                        }
                        if (found.isEmpty()) toast("No songs found in that folder");
                    }
                });
            }
        }).start();
    }

    private void walk(Uri tree, String docId, List<Uri> out, int depth) {
        if (depth > 12 || out.size() > 20000) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        Cursor c = null;
        try {
            c = getContentResolver().query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null);
            if (c == null) return;
            while (c.moveToNext()) {
                String id = c.getString(0);
                String mime = c.getString(1);
                String name = c.getString(2);
                if (name == null) name = "";
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    if (!name.startsWith(".")) walk(tree, id, out, depth + 1);
                } else if (isAudio(mime, name)) {
                    out.add(DocumentsContract.buildDocumentUriUsingTree(tree, id));
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    private static boolean isAudio(String mime, String name) {
        if (mime != null && mime.startsWith("audio/")) return true;
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".flac") || n.endsWith(".ogg")
                || n.endsWith(".opus") || n.endsWith(".wav") || n.endsWith(".aac") || n.endsWith(".m4b")
                || n.endsWith(".wma");
    }

    // ------------------------------------------------------------------ JS <-> Android

    public class Bridge {
        @JavascriptInterface
        public void update(String json) {
            MediaService.post(MainActivity.this, json);
        }

        @JavascriptInterface
        public void setArt(String id, String b64) {
            if (id == null || b64 == null || b64.isEmpty()) return;
            try {
                byte[] raw = Base64.decode(b64, Base64.DEFAULT);
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
                int sample = 1;
                while (o.outWidth / sample > 1024 || o.outHeight / sample > 1024) sample *= 2;
                o.inJustDecodeBounds = false;
                o.inSampleSize = sample;
                Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
                if (bmp != null) MediaService.setArt(id, bmp);
            } catch (Exception ignored) {
            }
        }

        /** Used for "export / save a copy" buttons: writes into Downloads/STEEL BALL RUN. */
        @JavascriptInterface
        public void saveFile(final String name, final String mime, final String b64) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String safe = (name == null || name.isEmpty() ? "file" : name).replaceAll("[\\\\/:*?\"<>|]", "_");
                        byte[] data = Base64.decode(b64, Base64.DEFAULT);
                        String where;
                        if (Build.VERSION.SDK_INT >= 29) {
                            ContentValues cv = new ContentValues();
                            cv.put(MediaStore.Downloads.DISPLAY_NAME, safe);
                            cv.put(MediaStore.Downloads.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/STEEL BALL RUN");
                            Uri out = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                            if (out == null) throw new Exception("no output");
                            OutputStream os = getContentResolver().openOutputStream(out);
                            if (os == null) throw new Exception("no stream");
                            os.write(data);
                            os.close();
                            where = "Downloads/STEEL BALL RUN/" + safe;
                        } else {
                            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                            if (dir == null) dir = getFilesDir();
                            File f = new File(dir, safe);
                            FileOutputStream os = new FileOutputStream(f);
                            os.write(data);
                            os.close();
                            where = f.getAbsolutePath();
                        }
                        toast("Saved: " + where);
                    } catch (Exception e) {
                        toast("Could not save the file");
                    }
                }
            }).start();
        }
    }
}
