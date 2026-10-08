package com.thiairo.campro;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.Scroller;

/**
 * Anel de foco. Aparece onde o dedo tocou, contrai de 1.4x até 1.0x e
 * permanece como um círculo fino. Sem moldura amarela piscando: o foco
 * é confirmado por geometria, não por alarde.
 */
class FocusRingView extends View {

    private float cx, cy;
    private float raioBase;
    private float progresso;      // 0 = aparecendo, 1 = assentado
    private boolean visivel;
    private int corOk;
    private long inicio;
    private static final long DURACAO = 420L;
    private final Scroller sc;
    private final DecelerateInterpolator interp = new DecelerateInterpolator(2.2f);

    FocusRingView(Context c) {
        super(c);
        setWillNotDraw(false);
        sc = new Scroller(c);
        corOk = Design.TERRACOTA;
        visivel = false;
    }

    void toque(float x, float y) {
        cx = x;
        cy = y;
        raioBase = Design.dp(46);
        visivel = true;
        progresso = 0f;
        inicio = android.os.SystemClock.uptimeMillis();
        invalidate();
    }

    void sumir() {
        visivel = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (!visivel) return;

        long agora = android.os.SystemClock.uptimeMillis();
        float t = (float) (agora - inicio) / DURACAO;
        if (t > 1f) t = 1f;
        progresso = interp.getInterpolation(t);

        float escala = 1.4f - 0.4f * progresso;
        float r = raioBase * escala;

        Paint fino = Design.traco(corOk, 1.6f);
        fino.setAlpha(Math.round(90 + 165 * progresso));
        c.drawCircle(cx, cy, r, fino);

        // quatro cantos: referência visual de enquadramento
        Paint canto = Design.traco(corOk, 2.2f);
        canto.setAlpha(Math.round(60 + 150 * progresso));
        float d = r * 0.62f;
        float len = r * 0.30f;
        float[] xs = { cx - d, cx + d, cx - d, cx + d };
        float[] ys = { cy - d, cy - d, cy + d, cy + d };
        float[] dx = { 1, -1, 1, -1 };
        float[] dy = { 1, 1, -1, -1 };
        for (int i = 0; i < 4; i++) {
            c.drawLine(xs[i], ys[i], xs[i] + len * dx[i], ys[i], canto);
            c.drawLine(xs[i], ys[i], xs[i], ys[i] + len * dy[i], canto);
        }

        if (t < 1f) invalidate();
    }
}

/**
 * Controle manual desenhado à mão: trilha fina, preenchimento em terracota,
 * pino sem borda, rótulo em serif. Um controle que parece instrumento,
 * não widget de sistema.
 */
class DialView extends View {

    interface Mudanca {
        void aoMudar(float valor);
    }

    private String rotulo = "";
    private String valor = "";
    private float min, max, atual;
    private Mudanca escuta;
    private boolean arrastando;
    private float ultimoX;

    private final Paint pTrilha;
    private final Paint pCheio;
    private final Paint pPino;
    private final Paint pRotulo;
    private final Paint pValor;

    DialView(Context c) {
        super(c);
        setWillNotDraw(false);
        min = 0f; max = 100f; atual = 50f;
        pTrilha = Design.pincel(Design.LINHA);
        pCheio = Design.pincel(Design.TERRACOTA);
        pPino = Design.pincel(Design.ESPRESSO);
        pRotulo = Design.tinta(Design.CINZA, 10f);
        pValor = Design.tintaSerif(Design.ESPRESSO, 15f, true);
    }

    void configurar(String r, float mn, float mx, float ini, String v) {
        rotulo = r; min = mn; max = mx; atual = ini; valor = v;
        invalidate();
    }

    void setValor(float v, String txt) {
        atual = Math.max(min, Math.min(max, v));
        valor = txt;
        invalidate();
    }

    float getValor() { return atual; }

    void aoMudar(Mudanca m) { escuta = m; }

    @Override
    public boolean onTouchEvent(android.view.MotionEvent e) {
        switch (e.getAction()) {
            case android.view.MotionEvent.ACTION_DOWN:
                arrastando = true;
                ultimoX = e.getX();
                aplicar(e.getX());
                return true;
            case android.view.MotionEvent.ACTION_MOVE:
                if (arrastando) {
                    float dx = e.getX() - ultimoX;
                    ultimoX = e.getX();
                    float faixa = max - min;
                    float w = getWidth() - Design.dp(32);
                    atual = Math.max(min, Math.min(max, atual + (dx / w) * faixa));
                    if (escuta != null) escuta.aoMudar(atual);
                    invalidate();
                }
                return true;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                arrastando = false;
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    private void aplicar(float x) {
        float w = getWidth() - Design.dp(32);
        float t = Math.max(0f, Math.min(1f, (x - Design.dp(16)) / w));
        atual = min + t * (max - min);
        if (escuta != null) escuta.aoMudar(atual);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth();
        float h = getHeight();

        pRotulo.setColor(Design.CINZA);
        c.drawText(rotulo.toUpperCase(), Design.dp(16), Design.dp(15), pRotulo);

        pValor.setColor(Design.ESPRESSO);
        float lw = pValor.measureText(valor);
        c.drawText(valor, w - Design.dp(16) - lw, Design.dp(17), pValor);

        float y = h - Design.dp(18);
        float x0 = Design.dp(16);
        float x1 = w - Design.dp(16);
        float esp = Design.dp(3);

        c.drawRoundRect(new RectF(x0, y - esp / 2, x1, y + esp / 2), esp / 2, esp / 2, pTrilha);

        float t = (atual - min) / (max - min);
        float px = x0 + (x1 - x0) * t;
        c.drawRoundRect(new RectF(x0, y - esp / 2, px, y + esp / 2), esp / 2, esp / 2, pCheio);

        float pr = Design.dp(arrastando ? 9 : 7);
        c.drawCircle(px, y, pr, pPino);
        Paint halo = Design.pincel(0x1A000000);
        c.drawCircle(px, y + Design.dp(1.5f), pr, halo);
    }
}
