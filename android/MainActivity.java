package fr.brasselet.audit;

import android.app.Activity;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.core.content.FileProvider;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;

/**
 * Audit Rayonnages ASTO — la page index.html (copie de Vigie Rayonnages + devis estimatif)
 * affichée en plein écran, hors ligne. Ajouts par rapport à un navigateur :
 * appareil photo et choix de fichiers, impression / PDF par le service d'impression
 * d'Android, enregistrement des fichiers dans Téléchargements/Audit Rayonnages.
 */
public class MainActivity extends Activity {
    static final String ORIGIN = "https://appassets.androidplatform.net/";
    private static final int REQ_FILE = 2;

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private Uri photoUri;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF14283B);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        web.addJavascriptInterface(new Bridge(), "AuditNative");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return asset(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if (u.toString().startsWith(ORIGIN)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) { /* aucun lecteur */ }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                photoUri = null;
                boolean image = false;
                for (String t : p.getAcceptTypes()) if (t != null && t.startsWith("image")) image = true;
                Intent camera = image ? cameraIntent() : null;
                try {
                    if (camera != null && p.isCaptureEnabled()) {
                        startActivityForResult(camera, REQ_FILE);          // « Prendre une photo » : appareil photo direct
                        return true;
                    }
                    Intent pick = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType(image ? "image/*" : "*/*");
                    if (p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    Intent chooser = Intent.createChooser(pick, image ? "Ajouter une photo" : "Choisir le fichier");
                    if (camera != null) chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{camera});
                    startActivityForResult(chooser, REQ_FILE);
                    return true;
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
            }
        });
        web.loadUrl(ORIGIN + "index.html");
    }

    /** Appareil photo : la photo est écrite dans un fichier temporaire de l'application. */
    private Intent cameraIntent() {
        try {
            File dir = new File(getCacheDir(), "photos");
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) if (System.currentTimeMillis() - f.lastModified() > 86400000L) f.delete();
            File f = new File(dir, "photo-" + System.currentTimeMillis() + ".jpg");
            photoUri = FileProvider.getUriForFile(this, getPackageName() + ".fichiers", f);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.setClipData(ClipData.newRawUri("photo", photoUri));
            return i;
        } catch (Exception e) {
            photoUri = null;
            return null;
        }
    }

    /** Sert les fichiers de la page (assets/www) sous une adresse https locale. */
    private WebResourceResponse asset(Uri u) {
        if (!u.toString().startsWith(ORIGIN)) return null;
        String path = u.getPath();
        if (path == null || path.length() < 2) path = "/index.html";
        String mime = path.endsWith(".html") ? "text/html"
                : path.endsWith(".js") ? "text/javascript"
                : path.endsWith(".json") ? "application/json"
                : path.endsWith(".png") ? "image/png" : "application/octet-stream";
        try {
            return new WebResourceResponse(mime, "UTF-8", getAssets().open("www" + path));
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", null, new ByteArrayInputStream(new byte[0]));
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FILE || fileCallback == null) return;
        Uri[] result = null;
        if (res == RESULT_OK) {
            ArrayList<Uri> l = new ArrayList<>();
            if (data != null && data.getClipData() != null && data.getData() == null) {
                ClipData c = data.getClipData();
                for (int k = 0; k < c.getItemCount(); k++) {
                    Uri u = c.getItemAt(k).getUri();
                    if (u != null && !u.equals(photoUri)) l.add(u);
                }
            }
            if (data != null && data.getData() != null) l.add(data.getData());
            if (l.isEmpty() && photoUri != null) l.add(photoUri);      // photo prise avec l'appareil
            if (!l.isEmpty()) result = l.toArray(new Uri[0]);
        }
        fileCallback.onReceiveValue(result);
        fileCallback = null;
    }

    @Override
    public void onBackPressed() {
        // Retour : ferme d'abord la fenêtre ouverte dans la page ; sinon, met l'application en arrière-plan.
        web.evaluateJavascript("window.apkBack ? apkBack() : false", v -> {
            if (!"true".equals(v)) moveTaskToBack(true);
        });
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }

    /** Fonctions appelées par la page (window.AuditNative). */
    class Bridge {
        @JavascriptInterface
        public void print(String title, boolean landscape) {
            runOnUiThread(() -> {
                String t = title == null || title.isEmpty() ? "Audit Rayonnages" : title;
                PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                PrintAttributes.Builder a = new PrintAttributes.Builder()
                        .setMediaSize(landscape ? PrintAttributes.MediaSize.ISO_A4.asLandscape() : PrintAttributes.MediaSize.ISO_A4);
                if (pm != null) pm.print(t, web.createPrintDocumentAdapter(t), a.build());
            });
        }

        /** Enregistre un fichier (contenu en base64) ; renvoie l'emplacement, ou "" en cas d'échec. */
        @JavascriptInterface
        public String saveFile(String name, String mime, String b64) {
            try {
                byte[] data = Base64.decode(b64, Base64.DEFAULT);
                String n = name.replaceAll("[\\\\/:*?\"<>|]", "_");
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, n);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Audit Rayonnages");
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return "";
                    try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                        if (o == null) return "";
                        o.write(data);
                    }
                    return "Téléchargements/Audit Rayonnages/" + n;
                }
                File f = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), n);
                try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
                return f.getAbsolutePath();
            } catch (Exception e) {
                return "";
            }
        }
    }
}
