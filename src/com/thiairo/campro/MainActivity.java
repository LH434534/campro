package com.thiairo.campro;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.camera2.CameraCharacteristics;
import android.media.Image;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_CAM = 11;
    private static final String[] MODOS = { "AUTO", "PRO", "HDR", "NOITE", "RETRATO" };

    private FrameLayout raiz;
    private FrameLayout palco;          // viewfinder + overlays
    private TextureView preview;
    private FocusRingView anel;
    private ImageView graos;
    private Camera2Engine engine;
    private Handler ui = new Handler();

    private int modo = 0;
    private boolean ocupado = false;
    private File ultimaFoto;

    private LinearLayout trilha;        // chips de modo
    private LinearLayout painel;        // controles manuais
    private TextView status;
    private TextView dica;
    private ImageView miniatura;
    private View obturador;

    private DialView dIso, dTempo, dFoco, dEv;
    private int isoAtual = 100;
    private long tempoAtual = 8000000L;
    private float focoAtual = 1.0f;
    private int evAtual = 0;
    private boolean gradeLigada = true;
    private boolean histogramaLigado = false;

    private static final Paint P_GRADE = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Design.CREME);
        getWindow().getDecorView().setSystemUiVisibility(
                getWindow().getDecorView().getSystemUiVisibility()
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        montarRaiz();
        setContentView(raiz);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ Manifest.permission.CAMERA }, REQ_CAM);
        }
    }

    // ------------------------------------------------------------------ UI

    private void montarRaiz() {
        raiz = new FrameLayout(this);
        raiz.setBackgroundColor(Design.CREME);

        // ---- viewfinder ----
        palco = new FrameLayout(this);
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                abrirCamera(new Surface(st));
            }
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
            public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        palco.addView(preview, lp);

        View grad = new View(this) {
            @Override
            protected void onDraw(Canvas c) {
                if (!gradeLigada) return;
                P_GRADE.setColor(0x40FFFFFF);
                P_GRADE.setStrokeWidth(Design.dp(0.8f));
                float w = getWidth(), h = getHeight();
                for (int i = 1; i < 3; i++) {
                    c.drawLine(w * i / 3f, 0, w * i / 3f, h, P_GRADE);
                    c.drawLine(0, h * i / 3f, w, h * i / 3f, P_GRADE);
                }
            }
        };
        grad.setWillNotDraw(false);
        palco.addView(grad, lp);

        anel = new FocusRingView(this);
        palco.addView(anel, lp);

        graos = new ImageView(this);
        graos.setScaleType(ImageView.ScaleType.FIT_XY);
        graos.setAlpha(0.35f);
        palco.addView(graos, lp);

        raiz.addView(palco, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- topo ----
        raiz.addView(topo(), topoLayout());
        // ---- base (inclui o painel manual) ----
        raiz.addView(base(), baseLayout());

        status = new TextView(this);
        status.setTextColor(Design.CAFE);
        status.setTextSize(12);
        status.setGravity(Gravity.CENTER);
        status.setAlpha(0f);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.gravity = Gravity.CENTER;
        raiz.addView(status, sp);

        obturador = new View(this);
        obturador.setBackgroundColor(Design.CREME);
        obturador.setAlpha(0f);
        obturador.setClickable(false);
        raiz.addView(obturador, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private View topo() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, (int) Design.dp(30), 0, 0);

        TextView marca = new TextView(this);
        marca.setText("CamPro");
        marca.setTextColor(Design.ESPRESSO);
        marca.setTextSize(26);
        marca.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD));
        marca.setLetterSpacing(0.02f);
        marca.setGravity(Gravity.CENTER);
        col.addView(marca);

        TextView sub = new TextView(this);
        sub.setText("fotografia manual");
        sub.setTextColor(Design.CINZA);
        sub.setTextSize(10);
        sub.setLetterSpacing(0.22f);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, (int) Design.dp(1), 0, 0);
        col.addView(sub);

        return col;
    }

    private FrameLayout.LayoutParams topoLayout() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.gravity = Gravity.TOP;
        return p;
    }

    private View base() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding((int) Design.dp(24), 0, (int) Design.dp(24), (int) Design.dp(26));

        construirPainel();
        col.addView(painel);

        trilha = new LinearLayout(this);
        trilha.setOrientation(LinearLayout.HORIZONTAL);
        trilha.setGravity(Gravity.CENTER);
        for (int i = 0; i < MODOS.length; i++) {
            trilha.addView(chip(MODOS[i], i));
        }
        col.addView(trilha, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout lin = new LinearLayout(this);
        lin.setOrientation(LinearLayout.HORIZONTAL);
        lin.setGravity(Gravity.CENTER_VERTICAL);
        lin.setPadding(0, (int) Design.dp(22), 0, 0);

        miniatura = new ImageView(this);
        miniatura.setBackgroundColor(Design.AREIA);
        int t = (int) Design.dp(46);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(t, t);
        mp.rightMargin = (int) Design.dp(28);
        miniatura.setLayoutParams(mp);
        miniatura.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { abrirUltima(); }
        });
        lin.addView(miniatura);

        lin.addView(botaoCapturar());

        View vazio = new View(this);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(t, t);
        vp.leftMargin = (int) Design.dp(28);
        vazio.setLayoutParams(vp);
        lin.addView(vazio);

        col.addView(lin);

        dica = new TextView(this);
        dica.setText("toque para focar");
        dica.setTextColor(Design.CINZA_CLARO);
        dica.setTextSize(10.5f);
        dica.setLetterSpacing(0.14f);
        dica.setGravity(Gravity.CENTER);
        dica.setPadding(0, (int) Design.dp(14), 0, 0);
        col.addView(dica);

        return col;
    }

    private FrameLayout.LayoutParams baseLayout() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.gravity = Gravity.BOTTOM;
        return p;
    }

    private View botaoCapturar() {
        final FrameLayout f = new FrameLayout(this);
        int d = (int) Design.dp(78);
        f.setLayoutParams(new LinearLayout.LayoutParams(d, d));

        final View anelExt = new View(this) {
            @Override
            protected void onDraw(Canvas c) {
                Paint p = Design.traco(Design.LINHA, 2f);
                float r = getWidth() / 2f - Design.dp(4);
                c.drawCircle(getWidth() / 2f, getHeight() / 2f, r, p);
            }
        };
        anelExt.setWillNotDraw(false);
        f.addView(anelExt, new FrameLayout.LayoutParams(d, d));

        final View nucleo = new View(this) {
            @Override
            protected void onDraw(Canvas c) {
                Paint p = Design.pincel(Design.TERRACOTA);
                float r = getWidth() / 2f - Design.dp(9);
                c.drawCircle(getWidth() / 2f, getHeight() / 2f, r, p);
            }
        };
        nucleo.setWillNotDraw(false);
        int nd = (int) (d - Design.dp(22));
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(nd, nd);
        np.gravity = Gravity.CENTER;
        f.addView(nucleo, np);

        f.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { disparar(); }
        });
        return f;
    }

    private View chip(String txt, final int idx) {
        final TextView t = new TextView(this);
        t.setText(txt);
        t.setTextSize(10.5f);
        t.setLetterSpacing(0.16f);
        t.setGravity(Gravity.CENTER);
        int pd = (int) Design.dp(11);
        t.setPadding((int) Design.dp(15), pd, (int) Design.dp(15), pd);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = (int) Design.dp(7);
        t.setLayoutParams(p);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { setModo(idx); }
        });
        pintarChip(t, idx == 0);
        return t;
    }

    private void pintarChip(TextView t, boolean ativo) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(Design.dp(20));
        if (ativo) {
            g.setColor(Design.ESPRESSO);
            t.setTextColor(Design.CREME);
        } else {
            g.setColor(Design.CREME_2);
            g.setStroke((int) Design.dp(1), Design.LINHA);
            t.setTextColor(Design.CAFE);
        }
        t.setBackground(g);
    }

    private void construirPainel() {
        painel = new LinearLayout(this);
        painel.setOrientation(LinearLayout.VERTICAL);
        painel.setBackgroundColor(Design.CREME_2);
        painel.setPadding((int) Design.dp(20), (int) Design.dp(16),
                (int) Design.dp(20), (int) Design.dp(18));

        TextView rot = new TextView(this);
        rot.setText("CONTROLE MANUAL");
        rot.setTextColor(Design.CINZA);
        rot.setTextSize(9.5f);
        rot.setLetterSpacing(0.2f);
        rot.setPadding(0, 0, 0, (int) Design.dp(12));
        painel.addView(rot);

        dIso = novoDial("ISO", 50, 6400, 100, "100");
        dTempo = novoDial("OBTURADOR", -13, 0, -7, "1/125");
        dFoco = novoDial("FOCO", 0, 20, 1, "auto");
        dEv = novoDial("EXPOSIÇÃO", -12, 12, 0, "0.0");
        painel.setVisibility(View.GONE);
        painel.addView(dIso);
        painel.addView(dTempo);
        painel.addView(dFoco);
        painel.addView(dEv);
    }

    private DialView novoDial(String rot, float mn, float mx, float ini, String txt) {
        DialView d = new DialView(this);
        d.configurar(rot, mn, mx, ini, txt);
        int h = (int) Design.dp(52);
        d.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, h));
        d.aoMudar(new DialView.Mudanca() {
            public void aoMudar(float v) { atualizarManual(); }
        });
        return d;
    }

    // -------------------------------------------------------------- câmera

    private void abrirCamera(Surface s) {
        try {
            engine = new Camera2Engine(this);
            engine.start(s);
            gerarGrao();
            if (engine.supportsManual()) {
                dica.setText("controle manual disponível");
            } else {
                dica.setText("este aparelho limita controles manuais");
            }
        } catch (Exception e) {
            dica.setText("câmera indisponível: " + e.getMessage());
        }
    }

    private void gerarGrao() {
        int w = 360, h = 640;
        Bitmap b = Design.grao(w, h, 26);
        graos.setImageBitmap(b);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        if (code == REQ_CAM && grants.length > 0
                && grants[0] == PackageManager.PERMISSION_GRANTED) {
            if (preview.isAvailable()) {
                abrirCamera(new Surface(preview.getSurfaceTexture()));
            }
        } else {
            dica.setText("permissão de câmera necessária");
        }
    }

    private void setModo(int m) {
        modo = m;
        for (int i = 0; i < trilha.getChildCount(); i++) {
            View v = trilha.getChildAt(i);
            if (v instanceof TextView) pintarChip((TextView) v, i == m);
        }
        boolean pro = (m == 1);
        painel.setVisibility(pro ? View.VISIBLE : View.GONE);
        if (pro) atualizarManual();
        else if (engine != null) {
            engine.setManual(-1, -1, -1);
        }
        mostrarStatus(MODOS[m]);
    }

    private void atualizarManual() {
        if (engine == null) return;
        isoAtual = (int) dIso.getValor();
        double exp = dTempo.getValor();
        long nanos = (long) (Math.pow(2, exp) * 1000000000.0);
        tempoAtual = nanos;
        focoAtual = dFoco.getValor();
        evAtual = (int) dEv.getValor();

        dIso.setValor(isoAtual, String.valueOf(isoAtual));
        dTempo.setValor(dTempo.getValor(), rotuloTempo(nanos));
        dFoco.setValor(focoAtual, focoAtual <= 0.05f ? "auto" : String.format(Locale.US, "%.2f m", focoAtual));
        dEv.setValor(evAtual, String.format(Locale.US, "%+.1f", evAtual / 3f));

        engine.setManual(isoAtual, tempoAtual, focoAtual);
        engine.setEvComp(evAtual);
    }

    private String rotuloTempo(long nanos) {
        double s = nanos / 1000000000.0;
        if (s >= 1.0) return String.format(Locale.US, "%.1fs", s);
        return "1/" + Math.round(1.0 / s);
    }

    private void disparar() {
        if (ocupado || engine == null) return;
        ocupado = true;
        obturador.animate().alpha(0.85f).setDuration(70)
                .withEndAction(new Runnable() {
                    public void run() {
                        obturador.animate().alpha(0f).setDuration(180).start();
                    }
                }).start();

        if (modo == 2) capturarHdr();
        else if (modo == 3) capturarNoite();
        else capturarSimples();
    }

    private void capturarSimples() {
        mostrarStatus("capturando");
        engine.captureJpeg(new Camera2Engine.JpegListener() {
            public void onJpeg(byte[] data) { salvar(data); }
            public void onError(String msg) { fim("erro: " + msg); }
        });
    }

    private void capturarHdr() {
        mostrarStatus("3 exposições · alinhando");
        Size s = engine.getCaptureSize();
        engine.captureBurst(new float[]{ -2f, 0f, 2f }, s,
                new Camera2Engine.BurstListener() {
                    public void onBurst(List<Camera2Engine.Frame> frames) {
                        if (frames.size() < 2) { capturarSimples(); return; }
                        Camera2Engine.Frame ref = frames.get(0);
                        int w = ref.width, h = ref.height;
                        byte[][] ys = new byte[frames.size()][];
                        int[][] sh = new int[frames.size()][];
                        float[] evs = new float[frames.size()];
                        for (int i = 0; i < frames.size(); i++) {
                            Camera2Engine.Frame f = frames.get(i);
                            ys[i] = f.y;
                            evs[i] = f.ev;
                            sh[i] = (i == 0) ? new int[]{ 0, 0 } : Fusion.align(ref.y, f.y, w, h);
                        }
                        byte[] y = Fusion.fuseHdr(ys, w, h, evs, sh);
                        byte[] nv = Fusion.toNv21(y, ref.u, ref.v, w, h, ref.uRowStride, ref.uPixStride, ref.vRowStride, ref.vPixStride);
                        salvarNv21(nv, w, h);
                    }
                    public void onError(String msg) { fim("erro: " + msg); }
                });
    }

    private void capturarNoite() {
        mostrarStatus("empilhando 6 quadros");
        Size s = engine.getCaptureSize();
        float[] evs = new float[6];
        for (int i = 0; i < 6; i++) evs[i] = 0f;
        engine.captureBurst(evs, s, new Camera2Engine.BurstListener() {
            public void onBurst(List<Camera2Engine.Frame> frames) {
                if (frames.size() < 2) { capturarSimples(); return; }
                Camera2Engine.Frame ref = frames.get(0);
                int w = ref.width, h = ref.height;
                byte[][] ys = new byte[frames.size()][];
                int[][] sh = new int[frames.size()][];
                for (int i = 0; i < frames.size(); i++) {
                    Camera2Engine.Frame f = frames.get(i);
                    ys[i] = f.y;
                    sh[i] = (i == 0) ? new int[]{ 0, 0 } : Fusion.align(ref.y, f.y, w, h);
                }
                byte[] y = Fusion.fuseNight(ys, w, h, sh);
                byte[] nv = Fusion.toNv21(y, ref.u, ref.v, w, h, ref.uRowStride, ref.uPixStride, ref.vRowStride, ref.vPixStride);
                salvarNv21(nv, w, h);
            }
            public void onError(String msg) { fim("erro: " + msg); }
        });
    }

    // ------------------------------------------------------------- arquivos

    private File dirFotos() {
        File d = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "CamPro");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private void salvar(byte[] jpeg) {
        FileOutputStream fos = null;
        try {
            String nome = "CMP_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".jpg";
            File f = new File(dirFotos(), nome);
            fos = new FileOutputStream(f);
            fos.write(jpeg);
            ultimaFoto = f;
            publicar(f);
            fim("salvo");
        } catch (Exception e) {
            fim("erro ao salvar: " + e.getMessage());
        } finally {
            if (fos != null) { try { fos.close(); } catch (Exception ig) { } }
        }
    }

    private void salvarNv21(byte[] nv21, int w, int h) {
        try {
            YuvImage img = new YuvImage(nv21, ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            img.compressToJpeg(new Rect(0, 0, w, h), 94, baos);
            salvar(baos.toByteArray());
        } catch (Exception e) {
            fim("erro na fusão: " + e.getMessage());
        }
    }

    private void publicar(File f) {
        Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b == null) return;
        int lado = Math.min(b.getWidth(), b.getHeight());
        Bitmap quad = Bitmap.createBitmap(b, (b.getWidth() - lado) / 2,
                (b.getHeight() - lado) / 2, lado, lado);
        miniatura.setImageBitmap(quad);
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(Design.dp(12));
        miniatura.setBackground(g);
        miniatura.setClipToOutline(true);
    }

    private void abrirUltima() {
        if (ultimaFoto == null) { mostrarStatus("nenhuma foto ainda"); return; }
        mostrarStatus(ultimaFoto.getName());
    }

    // ---------------------------------------------------------------- estado

    private void mostrarStatus(String txt) {
        status.setText(txt);
        status.animate().alpha(1f).setDuration(140).start();
        ui.removeCallbacks(esconder);
        ui.postDelayed(esconder, 1600);
    }

    private final Runnable esconder = new Runnable() {
        public void run() { status.animate().alpha(0f).setDuration(300).start(); }
    };

    private void fim(String msg) {
        runOnUiThread(new Runnable() {
            public void run() {
                ocupado = false;
                mostrarStatus(msg);
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && engine != null) {
            float x = e.getX(), y = e.getY();
            if (y < Design.dp(120) || y > getResources().getDisplayMetrics().heightPixels - Design.dp(240)) {
                return true;
            }
            anel.toque(x, y);
            Rect sensor = engine.getSensorRect();
            if (sensor != null && sensor.width() > 0) {
                float fx = x / palco.getWidth();
                float fy = y / palco.getHeight();
                int cx = sensor.left + (int) (sensor.width() * fx);
                int cy = sensor.top + (int) (sensor.height() * fy);
                int m = (int) Design.dp(90);
                Rect r = new Rect(
                        Math.max(sensor.left, cx - m),
                        Math.max(sensor.top, cy - m),
                        Math.min(sensor.right, cx + m),
                        Math.min(sensor.bottom, cy + m));
                engine.setMeteringRegion(r);
            }
            ui.postDelayed(new Runnable() {
                public void run() { anel.sumir(); }
            }, 1800);
        }
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (preview != null && preview.isAvailable() && engine == null) {
            abrirCamera(new Surface(preview.getSurfaceTexture()));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (engine != null) { engine.close(); engine = null; }
    }
}
