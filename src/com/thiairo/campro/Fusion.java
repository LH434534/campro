package com.thiairo.campro;

/**
 * Fusao computacional sobre o plano de luminancia Y.
 *
 * Trabalha em Y (1 byte/pixel) em vez de RGB float: uma imagem de 12 MP ocupa
 * 12 MB por quadro, o que cabe na memoria. Em float RGB seriam 144 MB por
 * quadro e o app morreria por OOM em qualquer aparelho.
 *
 * Nada de lambda: o javac com -bootclasspath android.jar nao resolve
 * LambdaMetafactory. Tudo em classes anonimas.
 */
public final class Fusion {

    private static final String TAG = "CamPro.Fusion";

    /* ------------------------------------------------------------------ *
     * LUTs
     * ------------------------------------------------------------------ */

    /** sRGB (0..255) -> linear (0..1) */
    private static final float[] SRGB_TO_LIN = new float[256];
    /** linear (0..1) -> sRGB (0..255) */
    private static final byte[] LIN_TO_SRGB = new byte[1024];

    static {
        for (int i = 0; i < 256; i++) {
            float c = i / 255.0f;
            SRGB_TO_LIN[i] = (c <= 0.04045f)
                    ? c / 12.92f
                    : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
        }
        for (int i = 0; i < 1024; i++) {
            float c = i / 1023.0f;
            float s = (c <= 0.0031308f)
                    ? c * 12.92f
                    : 1.055f * (float) Math.pow(c, 1.0 / 2.4f) - 0.055f;
            int v = Math.round(s * 255.0f);
            LIN_TO_SRGB[i] = (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
        }
    }

    private static float lin(int v) { return SRGB_TO_LIN[v & 0xFF]; }

    private static byte srgb(float c) {
        int i = (int) (c * 1023.0f + 0.5f);
        if (i < 0) i = 0;
        if (i > 1023) i = 1023;
        return LIN_TO_SRGB[i];
    }

    /**
     * Curva filmica: comprime realces e levanta sombras.
     * Referencia aproximada de ACES, reduzida a um unico tranco por canal
     * de luminancia. x em 0..1, saida em 0..1.
     */
    private static float tonemap(float x) {
        if (x <= 0.0f) return 0.0f;
        float a = 2.51f, b = 0.03f, c = 2.43f, d = 0.59f, e = 0.14f;
        float r = (x * (a * x + b)) / (x * (c * x + d) + e);
        if (r < 0.0f) r = 0.0f;
        if (r > 1.0f) r = 1.0f;
        return r;
    }

    private static final float[] TONE = new float[2048];
    static {
        // amostra a curva em alta resolucao para nao recomputar pow() por pixel
        for (int i = 0; i < 2048; i++) {
            TONE[i] = tonemap(i / 2047.0f * 4.0f); // entrada ate 4.0 (realces estourados)
        }
    }

    private static float tone(float x) {
        int i = (int) (x * 0.25f * 2047.0f + 0.5f);
        if (i < 0) return 0.0f;
        if (i > 2047) i = 2047;
        return TONE[i];
    }

    /* ------------------------------------------------------------------ *
     * Alinhamento
     * ------------------------------------------------------------------ */

    /**
     * Alinhamento por translacao global: busca em SAD sobre luminancia
     * reduzida. Sem isso, tres quadros na mao produzem fantasma visivel.
     * Procura deslocamento inteiro em pixels da imagem cheia.
     */
    public static int[] align(byte[] ref, byte[] src, int w, int h) {
        final int step = 8;                 // fator de reducao
        final int lw = w / step, lh = h / step;
        if (lw < 8 || lh < 8) return new int[]{0, 0};

        byte[] a = downsample(ref, w, h, step, lw, lh);
        byte[] b = downsample(src, w, h, step, lw, lh);

        final int maxShift = 12;            // em pixels da imagem reduzida
        int bestDx = 0, bestDy = 0;
        long best = Long.MAX_VALUE;

        for (int dy = -maxShift; dy <= maxShift; dy++) {
            for (int dx = -maxShift; dx <= maxShift; dx++) {
                long sad = 0;
                // amostra uma grade para nao varrer tudo
                for (int y = maxShift; y < lh - maxShift; y += 2) {
                    int ro = y * lw + maxShift;
                    int so = (y + dy) * lw + (maxShift + dx);
                    for (int x = maxShift; x < lw - maxShift; x += 2) {
                        int d = (a[ro] & 0xFF) - (b[so] & 0xFF);
                        sad += (d < 0) ? -d : d;
                        ro += 2; so += 2;
                    }
                }
                if (sad < best) { best = sad; bestDx = dx; bestDy = dy; }
            }
        }
        return new int[]{bestDx * step, bestDy * step};
    }

    private static byte[] downsample(byte[] src, int w, int h, int step, int lw, int lh) {
        byte[] out = new byte[lw * lh];
        for (int y = 0; y < lh; y++) {
            int base = (y * step) * w;
            int o = y * lw;
            for (int x = 0; x < lw; x++) {
                out[o + x] = src[base + x * step];
            }
        }
        return out;
    }

    /* ------------------------------------------------------------------ *
     * Pesos de Mertens
     * ------------------------------------------------------------------ */

    /**
     * Constroi o mapa de pesos de uma exposicao.
     * Combinacao classica: boa-exposicao x contraste x saturacao.
     * Retorna bytes 0..255 (a normalizacao entre quadros vem depois).
     */
    private static byte[] weights(byte[] y, int w, int h) {
        byte[] out = new byte[w * h];
        final float sigma2 = 2.0f * 0.2f * 0.2f;

        for (int row = 0; row < h; row++) {
            int yUp = (row > 0) ? row - 1 : row;
            int yDn = (row < h - 1) ? row + 1 : row;
            int o = row * w;
            int oU = yUp * w;
            int oD = yDn * w;

            for (int x = 0; x < w; x++) {
                int xL = (x > 0) ? x - 1 : x;
                int xR = (x < w - 1) ? x + 1 : x;

                float c = lin(y[o + x]);
                float up = lin(y[oU + x]);
                float dn = lin(y[oD + x]);
                float lf = lin(y[o + xL]);
                float rt = lin(y[o + xR]);

                // boa exposicao: favorece meio-tons
                float d = c - 0.5f;
                float we = (float) Math.exp(-(d * d) / sigma2);

                // contraste: laplaciano
                float lap = 4.0f * c - up - dn - lf - rt;
                float wc = (lap < 0.0f) ? -lap : lap;

                float wgt = we * (0.4f + 0.6f * wc * 8.0f);
                if (wgt < 0.0f) wgt = 0.0f;

                int v = (int) (wgt * 255.0f);
                out[o + x] = (byte) (v > 255 ? 255 : v);
            }
        }
        return out;
    }

    /** Suaviza o mapa de pesos: sem isso aparecem costuras nos limites. */
    private static void blur(byte[] buf, int w, int h, int radius) {
        if (radius < 1) return;
        byte[] tmp = new byte[w * h];
        // horizontal
        for (int y = 0; y < h; y++) {
            int o = y * w;
            for (int x = 0; x < w; x++) {
                int sum = 0, n = 0;
                for (int k = -radius; k <= radius; k++) {
                    int xx = x + k;
                    if (xx < 0 || xx >= w) continue;
                    sum += buf[o + xx] & 0xFF; n++;
                }
                tmp[o + x] = (byte) (sum / n);
            }
        }
        // vertical
        for (int y = 0; y < h; y++) {
            int o = y * w;
            for (int x = 0; x < w; x++) {
                int sum = 0, n = 0;
                for (int k = -radius; k <= radius; k++) {
                    int yy = y + k;
                    if (yy < 0 || yy >= h) continue;
                    sum += tmp[yy * w + x] & 0xFF; n++;
                }
                buf[o + x] = (byte) (sum / n);
            }
        }
    }

    /* ------------------------------------------------------------------ *
     * Fusao HDR
     * ------------------------------------------------------------------ */

    /**
     * Fusao de exposicoes (Mertens simplificado, dominio de luminancia).
     *
     * @param frames luminancia Y de cada quadro, todos w*h
     * @param evs    valor de exposicao de cada quadro (0 = referencia)
     * @param shift  deslocamento {dx,dy} de cada quadro
     * @return luminancia fundida, w*h
     */
    public static byte[] fuseHdr(byte[][] frames, int w, int h, float[] evs, int[][] shift) {
        final int n = frames.length;
        byte[][] ws = new byte[n][];
        for (int i = 0; i < n; i++) {
            ws[i] = weights(frames[i], w, h);
            blur(ws[i], w, h, 3);
        }

        byte[] out = new byte[w * h];

        for (int y = 0; y < h; y++) {
            int o = y * w;
            for (int x = 0; x < w; x++) {
                int idx = o + x;
                float wsum = 0.0f;
                float acc = 0.0f;

                for (int i = 0; i < n; i++) {
                    int sx = x + shift[i][0];
                    int sy = y + shift[i][1];
                    if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
                    int si = sy * w + sx;

                    float wi = (ws[i][si] & 0xFF) / 255.0f;
                    if (wi <= 0.0f) continue;

                    // radiancia da cena: desfaz a exposicao aplicada
                    float scene = lin(frames[i][si] & 0xFF) * (float) Math.pow(2.0, -evs[i]);

                    acc += wi * scene;
                    wsum += wi;
                }

                float v;
                if (wsum > 0.0f) v = acc / wsum;
                else v = lin(frames[n / 2][idx] & 0xFF);

                out[idx] = srgb(tone(v));
            }
        }
        return out;
    }

    /**
     * Empilhamento para pouca luz: media em luz linear.
     * Cada quadro dobrado reduz o ruido por sqrt(2).
     */
    public static byte[] fuseNight(byte[][] frames, int w, int h, int[][] shift) {
        final int n = frames.length;
        byte[] out = new byte[w * h];

        for (int y = 0; y < h; y++) {
            int o = y * w;
            for (int x = 0; x < w; x++) {
                int idx = o + x;
                float acc = 0.0f;
                int cnt = 0;

                for (int i = 0; i < n; i++) {
                    int sx = x + shift[i][0];
                    int sy = y + shift[i][1];
                    if (sx < 0 || sy < 0 || sx >= w || sy >= h) continue;
                    int si = sy * w + sx;
                    acc += lin(frames[i][si] & 0xFF);
                    cnt++;
                }

                float v = (cnt > 0) ? acc / cnt : lin(frames[0][idx] & 0xFF);
                // levanta sombras e segura os realces
                out[idx] = srgb(tone(v * 1.6f));
            }
        }
        return out;
    }

    /* ------------------------------------------------------------------ *
     * Utilidades de buffer
     * ------------------------------------------------------------------ */

    /**
     * Copia o plano Y de um Image YUV_420_888 para um array compacto,
     * removendo o rowStride. Sem isso as linhas vem desalinhadas em
     * varios aparelhos.
     */
    public static byte[] tightY(java.nio.ByteBuffer buf, int rowStride, int w, int h) {
        byte[] out = new byte[w * h];
        int pos = buf.position();
        if (rowStride == w) {
            buf.get(out);
        } else {
            for (int y = 0; y < h; y++) {
                buf.position(pos + y * rowStride);
                buf.get(out, y * w, w);
            }
        }
        buf.position(pos);
        return out;
    }

    /**
     * Monta NV21 a partir de Y processado + croma do quadro de referencia.
     * NV21 e o formato que YuvImage aceita para comprimir a JPEG.
     */
    public static byte[] toNv21(byte[] y, byte[] uu, byte[] vv,
                                int w, int h, int uRowStride, int uPixStride,
                                int vRowStride, int vPixStride) {
        int size = w * h;
        byte[] nv21 = new byte[size + (size / 2)];
        System.arraycopy(y, 0, nv21, 0, size);

        int cw = w / 2, ch = h / 2;
        int o = size;

        for (int row = 0; row < ch; row++) {
            for (int col = 0; col < cw; col++) {
                int ui = row * uRowStride + col * uPixStride;
                int vi = row * vRowStride + col * vPixStride;
                nv21[o++] = vv[vi];   // NV21 pede V antes de U
                nv21[o++] = uu[ui];
            }
        }
        return nv21;
    }

    /** Histograma de luminancia em 64 faixas. */
    public static int[] histogram(byte[] y, int w, int h) {
        int[] hist = new int[64];
        int step = (w * h) / 40000;
        if (step < 1) step = 1;
        int c = 0;
        for (int i = 0; i < w * h; i += step) {
            int b = (y[i] & 0xFF) >> 2;
            if (b > 63) b = 63;
            hist[b]++;
            c++;
        }
        return hist;
    }
}
