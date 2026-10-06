package com.thiairo.campro;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Interface do CamPro.
 *
 * Restricao visual deliberada: fundo preto, linhas finas, um unico acento.
 * Controles manuais so aparecem no modo PRO. O resto do tempo a tela e
 * so a imagem.
 */
public class MainActivity extends Activity {

    private static final String TAG = "CamPro";
    private static final int REQ_CAM = 10;

    private static final int MODO_FOTO = 0;
    private static final int MODO_PRO = 1;
    private static final int MODO_HDR = 2;
    private static final int MODO_NOITE = 3;

    // obturadores em nanossegundos
    private static final long[] OBTURADORES = {
            125000L, 250000L, 500000L, 1000000L, 2000000L, 4000000L,
            8000000L, 16666666L, 33333333L, 66666666L, 125000000L,
            250000000L, 500000000L, 1000000000L
    };
    private static final String[] OBT_TXT = {
            "1/8000", "1/4000", "1/2000", "1/1000", "1/500", "1/250",
            "1/125", "1/60", "1/30", "1/15", "1/8", "1/4", "1/2", "1s"
    };
    private static final int[] ISOS = {50, 100, 200, 400, 800, 1600, 3200, 6400};

    private TextureView preview;
    private FrameLayout root;
    private Camera2Engine engine;

    private int modo = MODO_FOTO;
    private boolean processando = false;

    private LinearLayout painelManual;
    private TextView txtIso, txtObt, txtFoco, txtEv, txtStatus;
    private SeekBar sbIso, sbObt, sbFoco, sbEv;
    private Histograma histograma;
    private Grade grade;

    private int isoIdx = 1;      // 100
    private int obtIdx = 7;      // 1/60
    private int focoPct = 0;     // 0 = automatico
    private int evComp = 0;

    private final Object uiLock = new Object();

    /* ------------------------------------------------------------------ */

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        montarUi();
        setContentView(root);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
                return;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{
                            Manifest.permission.CAMERA,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    }, REQ_CAM);
                    return;
                }
            }
        }
        iniciarCamera();
    }

    private void iniciarCamera() {
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                abrirCamera(new Surface(st));
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (engine != null) engine.close();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });

        if (preview.isAvailable() && preview.getSurfaceTexture() != null) {
            abrirCamera(new Surface(preview.getSurfaceTexture()));
        }
    }

    private void abrirCamera(Surface s) {
        if (engine != null) return;
        engine = new Camera2Engine(this);
        try {
            engine.start(s);
            atualizarManual();
            txtStatus.setText(engine.supportsManual() ? "manual disponivel" : "manual limitado");
        } catch (Exception e) {
            Log.e(TAG, "abrir: " + e.getMessage());
            txtStatus.setText("falhou: " + e.getMessage());
            Toast.makeText(this, "Camera indisponivel: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        if (code == REQ_CAM) {
            boolean ok = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) iniciarCamera();
            else Toast.makeText(this, "Sem permissao de camera", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (engine != null) { engine.close(); engine = null; }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (engine == null && preview.isAvailable() && preview.getSurfaceTexture() != null) {
            abrirCamera(new Surface(preview.getSurfaceTexture()));
        }
    }

    /* ------------------------------------------------------------------ *
     * Interface
     * ------------------------------------------------------------------ */

    private void montarUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        preview = new TextureView(this);
        root.addView(preview, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        grade = new Grade(this);
        root.addView(grade, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        histograma = new Histograma(this);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(260, 110);
        hp.gravity = Gravity.TOP | Gravity.END;
        hp.setMargins(0, dp(64), dp(12), 0);
        root.addView(histograma, hp);

        // toque para focar
        final Reticulo reticulo = new Reticulo(this);
        root.addView(reticulo, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        preview.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() != MotionEvent.ACTION_UP) return true;
                if (engine == null) return true;

                Rect ativo = engine.getSensorRect();
                if (ativo == null) return true;

                float x = e.getX() / v.getWidth();
                float y = e.getY() / v.getHeight();
                int cx = (int) (ativo.left + x * ativo.width());
                int cy = (int) (ativo.top + y * ativo.height());
                int lado = Math.max(ativo.width(), ativo.height()) / 8;

                Rect r = new Rect(cx - lado, cy - lado, cx + lado, cy + lado);
                r.left = Math.max(r.left, ativo.left);
                r.top = Math.max(r.top, ativo.top);
                r.right = Math.min(r.right, ativo.right);
                r.bottom = Math.min(r.bottom, ativo.bottom);

                engine.setMeteringRegion(r);
                reticulo.mostrar(e.getX(), e.getY());
                return true;
            }
        });

        // ---- barra de modos, no topo ----
        LinearLayout modos = new LinearLayout(this);
        modos.setOrientation(LinearLayout.HORIZONTAL);
        modos.setBackgroundColor(Color.TRANSPARENT);
        FrameLayout.LayoutParams mp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        mp.setMargins(0, dp(20), 0, 0);
        root.addView(modos, mp);

        final String[] nomes = {"FOTO", "PRO", "HDR", "NOITE"};
        final TextView[] botoes = new TextView[nomes.length];
        for (int i = 0; i < nomes.length; i++) {
            final int idx = i;
            TextView t = new TextView(this);
            t.setText(nomes[i]);
            t.setTextSize(12);
            t.setTextColor(Color.WHITE);
            t.setPadding(dp(14), dp(6), dp(14), dp(6));
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) { setModo(idx); }
            });
            botoes[i] = t;
            modos.addView(t);
        }
        this.botoesModo = botoes;

        // ---- area inferior ----
        LinearLayout baixo = new LinearLayout(this);
        baixo.setOrientation(LinearLayout.VERTICAL);
        baixo.setBackgroundColor(0xBB000000);
        baixo.setPadding(dp(12), dp(10), dp(12), dp(16));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.gravity = Gravity.BOTTOM;
        root.addView(baixo, bp);

        painelManual = new LinearLayout(this);
        painelManual.setOrientation(LinearLayout.VERTICAL);
        baixo.addView(painelManual);

        txtIso = rotulo("ISO");
        sbIso = busca(100, new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { atualizarManual(); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        painelManual.addView(linha(txtIso, sbIso));

        txtObt = rotulo("OBT");
        sbObt = busca(100, new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { atualizarManual(); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        painelManual.addView(linha(txtObt, sbObt));

        txtFoco = rotulo("FOC");
        sbFoco = busca(100, new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { atualizarManual(); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        painelManual.addView(linha(txtFoco, sbFoco));

        txtEv = rotulo("EV");
        sbEv = busca(100, new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) { atualizarManual(); }
            public void onStartTrackingTouch(SeekBar s) { }
            public void onStopTrackingTouch(SeekBar s) { }
        });
        painelManual.addView(linha(txtEv, sbEv));

        // ---- obturador ----
        LinearLayout disparo = new LinearLayout(this);
        disparo.setOrientation(LinearLayout.HORIZONTAL);
        disparo.setGravity(Gravity.CENTER_VERTICAL);
        baixo.addView(disparo);

        txtStatus = new TextView(this);
        txtStatus.setTextSize(11);
        txtStatus.setTextColor(0xFF9AA0A6);
        txtStatus.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        disparo.addView(txtStatus);

        final TextView btn = new TextView(this);
        btn.setText("●");
        btn.setTextSize(40);
        btn.setTextColor(0xFFFFD60A);
        btn.setGravity(Gravity.CENTER);
        btn.setPadding(dp(20), 0, dp(20), 0);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { disparar(); }
        });
        disparo.addView(btn);

        TextView vazio = new TextView(this);
        vazio.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        disparo.addView(vazio);

        setModo(MODO_FOTO);
    }

    private TextView[] botoesModo;

    private TextView rotulo(String t) {
        TextView v = new TextView(this);
        v.setText(t);
        v.setTextSize(11);
        v.setTextColor(0xFF9AA0A6);
        v.setWidth(dp(34));
        return v;
    }

    private SeekBar busca(int max, SeekBar.OnSeekBarChangeListener l) {
        SeekBar s = new SeekBar(this);
        s.setMax(max);
        s.setOnSeekBarChangeListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        s.setLayoutParams(p);
        return s;
    }

    private LinearLayout linha(View a, View b) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        l.addView(a);
        l.addView(b);
        return l;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setModo(int m) {
        modo = m;
        for (int i = 0; i < botoesModo.length; i++) {
            boolean on = (i == m);
            botoesModo[i].setTextColor(on ? 0xFFFFD60A : 0xFF9AA0A6);
            botoesModo[i].setTextSize(on ? 13 : 12);
        }
        painelManual.setVisibility(m == MODO_PRO ? View.VISIBLE : View.GONE);
        grade.setVisivel(m == MODO_PRO);
        if (m != MODO_PRO && engine != null) {
            // fora do PRO, devolve tudo ao automatico
            engine.setManual(0, 0, -1f);
        }
    }

    private void atualizarManual() {
        if (engine == null) return;
        int nIso = ISOS.length - 1;
        int nObt = OBTURADORES.length - 1;

        isoIdx = Math.round((float) sbIso.getProgress() / 100f * nIso);
        obtIdx = Math.round((float) sbObt.getProgress() / 100f * nObt);
        focoPct = sbFoco.getProgress();
        evComp = Math.round((sbEv.getProgress() - 50) / 50f * engine.evRange()[1]);

        int iso = ISOS[isoIdx];
        long exp = OBTURADORES[obtIdx];
        float foco = (focoPct == 0) ? -1f : (focoPct / 100f) * engine.maxFocus();

        txtIso.setText(engine.supportsManual() ? ("ISO " + iso) : "ISO auto");
        txtObt.setText(engine.supportsManual() ? OBT_TXT[obtIdx] : "auto");
        txtFoco.setText(foco < 0f ? "AF" : ("F " + (int) (foco * 100) + "cm"));
        txtEv.setText((evComp > 0 ? "+" : "") + evComp);

        if (engine.supportsManual()) {
            engine.setManual(iso, exp, foco);
        } else {
            engine.setManual(0, 0, foco);
            engine.setEvComp(evComp);
        }
    }

    /* ------------------------------------------------------------------ *
     * Captura
     * ------------------------------------------------------------------ */

    private void disparar() {
        if (engine == null) { aviso("camera nao pronta"); return; }
        synchronized (uiLock) {
            if (processando) { aviso("ainda processando"); return; }
            processando = true;
        }
        txtStatus.setText("capturando…");

        if (modo == MODO_HDR) {
            capturarHdr();
        } else if (modo == MODO_NOITE) {
            capturarNoite();
        } else {
            capturarSimples();
        }
    }

    private void capturarSimples() {
        engine.captureJpeg(new Camera2Engine.JpegListener() {
            @Override
            public void onJpeg(byte[] data) {
                salvar(data);
                fim("salvo");
            }

            @Override
            public void onError(String msg) { fim("erro: " + msg); }
        });
    }

    private void capturarHdr() {
        final float[] evs = {-2.0f, 0.0f, 2.0f};
        engine.captureBurst(evs, engine.getCaptureSize(),
                new Camera2Engine.BurstListener() {
                    @Override
                    public void onBurst(List<Camera2Engine.Frame> frames) {
                        if (frames.size() < 2) { fim("poucos quadros"); return; }
                        processar(frames, evs, true);
                    }

                    @Override
                    public void onError(String msg) { fim("erro: " + msg); }
                });
    }

    private void capturarNoite() {
        final int n = 6;
        float[] evs = new float[n];
        for (int i = 0; i < n; i++) evs[i] = 0f;
        engine.captureBurst(evs, engine.getCaptureSize(),
                new Camera2Engine.BurstListener() {
                    @Override
                    public void onBurst(List<Camera2Engine.Frame> frames) {
                        if (frames.size() < 2) { fim("poucos quadros"); return; }
                        processar(frames, new float[frames.size()], false);
                    }

                    @Override
                    public void onError(String msg) { fim("erro: " + msg); }
                });
    }

    /** Roda a fusao fora da thread de UI: sao milhoes de pixels. */
    private void processar(final List<Camera2Engine.Frame> frames,
                           final float[] evs, final boolean hdr) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final int n = frames.size();
                    Camera2Engine.Frame ref = frames.get(n / 2);
                    final int w = ref.width, h = ref.height;

                    byte[][] ys = new byte[n][];
                    for (int i = 0; i < n; i++) ys[i] = frames.get(i).y;

                    // alinha os quadros contra o do meio
                    int[][] shift = new int[n][];
                    for (int i = 0; i < n; i++) {
                        if (i == n / 2) shift[i] = new int[]{0, 0};
                        else shift[i] = Fusion.align(ref.y, ys[i], w, h);
                    }

                    byte[] saida;
                    if (hdr) {
                        float[] e = new float[n];
                        for (int i = 0; i < n; i++) e[i] = (i < evs.length) ? evs[i] : 0f;
                        saida = Fusion.fuseHdr(ys, w, h, e, shift);
                    } else {
                        saida = Fusion.fuseNight(ys, w, h, shift);
                    }

                    byte[] nv21 = Fusion.toNv21(saida, ref.u, ref.v, w, h,
                            ref.uRowStride, ref.uPixStride,
                            ref.vRowStride, ref.vPixStride);

                    YuvImage img = new YuvImage(nv21, android.graphics.ImageFormat.NV21,
                            w, h, null);
                    java.io.ByteArrayOutputStream bos =
                            new java.io.ByteArrayOutputStream();
                    img.compressToJpeg(new Rect(0, 0, w, h), 95, bos);
                    final byte[] jpeg = bos.toByteArray();

                    final int[] hist = Fusion.histogram(saida, w, h);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            histograma.set(hist);
                            salvar(jpeg);
                            fim("fusao de " + n + " quadros — salvo");
                        }
                    });
                } catch (Throwable t) {
                    Log.e(TAG, "processar: " + t.getMessage());
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() { fim("falhou na fusao"); }
                    });
                }
            }
        }).start();
    }

    private void fim(final String msg) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                txtStatus.setText(msg);
                synchronized (uiLock) { processando = false; }
            }
        });
    }

    private void aviso(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    /* ------------------------------------------------------------------ *
     * Gravacao
     * ------------------------------------------------------------------ */

    private void salvar(byte[] jpeg) {
        String nome = "CAMPRO_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                .format(new Date()) + ".jpg";
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, nome);
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/CamPro");
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);

                Uri uri = getContentResolver().insert(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new java.io.IOException("insert falhou");

                OutputStream os = getContentResolver().openOutputStream(uri);
                os.write(jpeg);
                os.close();

                cv.clear();
                cv.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, cv, null, null);
            } else {
                File dir = new File(
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DCIM), "CamPro");
                if (!dir.exists()) dir.mkdirs();
                File f = new File(dir, nome);
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(jpeg);
                fos.close();
                MediaScannerConnection.scanFile(this,
                        new String[]{f.getAbsolutePath()},
                        new String[]{"image/jpeg"}, null);
            }
            Log.i(TAG, "salvo " + nome + " (" + jpeg.length + " bytes)");
        } catch (Exception e) {
            Log.e(TAG, "salvar: " + e.getMessage());
            aviso("falhou ao salvar");
        }
    }

    /* ------------------------------------------------------------------ *
     * Overlays
     * ------------------------------------------------------------------ */

    private final class Grade extends View {
        private boolean visivel = false;
        private final Paint p = new Paint();

        Grade(android.content.Context c) {
            super(c);
            p.setColor(0x55FFFFFF);
            p.setStrokeWidth(1f);
        }

        void setVisivel(boolean v) { visivel = v; invalidate(); }

        @Override
        protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            if (!visivel) return;
            int w = getWidth(), h = getHeight();
            for (int i = 1; i < 3; i++) {
                cv.drawLine(w * i / 3f, 0, w * i / 3f, h, p);
                cv.drawLine(0, h * i / 3f, w, h * i / 3f, p);
            }
        }
    }

    private final class Histograma extends View {
        private int[] hist = null;
        private final Paint p = new Paint();

        Histograma(android.content.Context c) {
            super(c);
            p.setColor(0xCCFFFFFF);
        }

        void set(int[] h) { hist = h; invalidate(); }

        @Override
        protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            if (hist == null) return;
            int w = getWidth(), h = getHeight();
            int max = 1;
            for (int v : hist) if (v > max) max = v;
            float bw = w / (float) hist.length;
            for (int i = 0; i < hist.length; i++) {
                float alt = (hist[i] / (float) max) * h;
                cv.drawRect(i * bw, h - alt, (i + 1) * bw, h, p);
            }
        }
    }

    private final class Reticulo extends View {
        private float x = -1, y = -1;
        private long t = 0;
        private final Paint p = new Paint();

        Reticulo(android.content.Context c) {
            super(c);
            p.setColor(0xFFFFD60A);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2f);
        }

        void mostrar(float px, float py) {
            x = px; y = py; t = System.currentTimeMillis();
            invalidate();
            postDelayed(new Runnable() {
                @Override
                public void run() { invalidate(); }
            }, 1200);
        }

        @Override
        protected void onDraw(Canvas cv) {
            super.onDraw(cv);
            if (x < 0) return;
            if (System.currentTimeMillis() - t > 1200) return;
            float r = dp(34);
            cv.drawCircle(x, y, r, p);
            cv.drawLine(x - r - dp(6), y, x - r + dp(6), y, p);
            cv.drawLine(x + r - dp(6), y, x + r + dp(6), y, p);
            cv.drawLine(x, y - r - dp(6), x, y - r + dp(6), p);
            cv.drawLine(x, y + r - dp(6), x, y + r + dp(6), p);
        }
    }
}
