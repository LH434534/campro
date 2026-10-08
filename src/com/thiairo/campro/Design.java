package com.thiairo.campro;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.TypedValue;

/**
 * Sistema de desenho do CamPro.
 *
 * Paleta quente e neutra: creme, espresso, terracota queimado, sálvia.
 * Nada de amarelo-lima sobre preto. A interface é clara por padrão porque
 * clareza combina com fotografia: o viewfinder é a única coisa que deve
 * carregar cor.
 *
 * Regras: raio generoso, sombra difusa, sem borda dura, tipografia com
 * respiro. Cada cor existe por uma razão, não por decoração.
 */
public final class Design {

    // ---- superfícies ----
    public static final int CREME      = 0xFFFBF8F3;
    public static final int CREME_2    = 0xFFF4EFE7;
    public static final int AREIA      = 0xFFE7DFD3;
    public static final int LINHA      = 0xFFDCD3C6;

    // ---- tinta ----
    public static final int ESPRESSO   = 0xFF2A2320;
    public static final int CAFE       = 0xFF5C4F46;
    public static final int CINZA      = 0xFF9A8D82;
    public static final int CINZA_CLARO = 0xFFB9ADA1;

    // ---- acentos ----
    public static final int TERRACOTA  = 0xFFC4714B;
    public static final int TERRACOTA_SUAVE = 0x26C4714B;
    public static final int SALVIA     = 0xFF8FA68E;
    public static final int BLUSH      = 0xFFE3BDB2;

    // ---- modo escuro ----
    public static final int NOITE      = 0xFF17130F;
    public static final int NOITE_2    = 0xFF221C17;
    public static final int NOITE_LINHA = 0xFF3A312A;
    public static final int NOITE_TINTA = 0xFFF2EAE1;
    public static final int NOITE_CINZA = 0xFF8B7F73;

    private Design() { }

    public static float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                Resources.getSystem().getDisplayMetrics());
    }
    public static float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v,
                Resources.getSystem().getDisplayMetrics());
    }

    public static Paint tinta(int cor, float tamanhoSp) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(cor);
        p.setTextSize(sp(tamanhoSp));
        return p;
    }

    public static Paint tintaSerif(int cor, float tamanhoSp, boolean negrito) {
        Paint p = tinta(cor, tamanhoSp);
        p.setTypeface(Typeface.create(Typeface.SERIF,
                negrito ? Typeface.BOLD : Typeface.NORMAL));
        return p;
    }

    public static Paint pincel(int cor) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(cor);
        p.setStyle(Paint.Style.FILL);
        return p;
    }

    public static Paint traco(int cor, float espessuraDp) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(cor);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(dp(espessuraDp));
        p.setStrokeCap(Paint.Cap.ROUND);
        return p;
    }

    public static void arredondado(Canvas c, float x, float y, float w, float h, float r, Paint p) {
        RectF rect = new RectF(x, y, x + w, y + h);
        c.drawRoundRect(rect, r, r, p);
    }

    /** Sombra difusa desenhada como gradiente — sem elevação falsa. */
    public static void sombra(Canvas c, float x, float y, float w, float h, float r, int corBase) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int a = Color.alpha(corBase);
        Shader sh = new LinearGradient(0, y, 0, y + h + dp(10),
                Color.argb(Math.round(a * 0.55f), 0, 0, 0), Color.argb(0, 0, 0, 0),
                Shader.TileMode.CLAMP);
        p.setShader(sh);
        c.drawRoundRect(new RectF(x, y + dp(3), x + w, y + h + dp(12)), r, r, p);
    }

    /** Grão sutil: fotografia analógica tem textura, interface também pode ter. */
    public static Bitmap grao(int w, int h, int intensidade) {
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        java.util.Random rnd = new java.util.Random(7);
        for (int i = 0; i < (w * h) / 14; i++) {
            int x = rnd.nextInt(w);
            int y = rnd.nextInt(h);
            int v = rnd.nextInt(intensidade);
            p.setColor(Color.argb(Math.max(4, v), 60, 50, 42));
            c.drawPoint(x, y, p);
        }
        return b;
    }

    /** Linha de chamada fina, usada em rótulos de seção. */
    public static void filete(Canvas c, float x, float y, float w, int cor) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(cor);
        c.drawRoundRect(new RectF(x, y, x + w, y + dp(1)), dp(0.5f), dp(0.5f), p);
    }

    public static Path marcaCapture(float cx, float cy, float raio) {
        Path p = new Path();
        p.addCircle(cx, cy, raio, Path.Direction.CW);
        return p;
    }

    public static int mistura(int a, int b, float t) {
        return Color.argb(
                255,
                Math.round(Color.red(a) * (1 - t) + Color.red(b) * t),
                Math.round(Color.green(a) * (1 - t) + Color.green(b) * t),
                Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }
}
