package com.thiairo.campro;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.graphics.Rect;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Motor Camera2.
 *
 * Escolhi Camera2 puro em vez de CameraX porque CameraX vem de
 * dependencias externas (androidx), e este build nao tem acesso a Maven.
 * Camera2 e framework: compila sem baixar nada. E da controle manual
 * de verdade, que e justamente o que um app "pro" precisa.
 */
public class Camera2Engine {

    private static final String TAG = "CamPro.Engine";

    public interface BurstListener {
        void onBurst(List<Frame> frames);
        void onError(String msg);
    }

    public interface JpegListener {
        void onJpeg(byte[] data);
        void onError(String msg);
    }

    /** Um quadro YUV com sua croma de referencia. */
    public static final class Frame {
        public byte[] y;
        public byte[] u;
        public byte[] v;
        public int uRowStride, uPixStride;
        public int vRowStride, vPixStride;
        public int width, height;
        public float ev;
    }

    private final Context ctx;
    private CameraManager manager;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader readerYuv;
    private ImageReader readerJpeg;
    private HandlerThread thread;
    private Handler bg;

    private String cameraId;
    private CameraCharacteristics chars;
    private Size previewSize;
    private Size captureSize;

    private Surface previewSurface;
    private CaptureRequest.Builder previewBuilder;

    // parametros manuais; 0 = automatico
    private int iso = 0;
    private long exposureNanos = 0;
    private float focusDistance = -1f;
    private int evComp = 0;
    private int awbMode = CameraCharacteristics.CONTROL_AWB_MODE_AUTO;
    private boolean manualSensor = false;
    private Rect meteringRect = null;

    private final Object lock = new Object();

    public Camera2Engine(Context c) {
        ctx = c.getApplicationContext();
    }

    /* ------------------------------------------------------------------ *
     * Ciclo de vida
     * ------------------------------------------------------------------ */

    public void start(Surface preview) throws Exception {
        previewSurface = preview;
        manager = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        if (manager == null) throw new IllegalStateException("sem CameraManager");

        cameraId = pickBackCamera();
        chars = manager.getCameraCharacteristics(cameraId);

        StreamConfigurationMap map =
                chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) throw new IllegalStateException("sem StreamConfigurationMap");

        Size[] sizes = map.getOutputSizes(ImageFormat.YUV_420_888);
        captureSize = chooseSize(sizes, 4000);
        if (captureSize == null) {
            captureSize = map.getOutputSizes(ImageFormat.JPEG)[0];
        }
        previewSize = chooseSize(map.getOutputSizes(SurfaceHolderClass()), 1920);
        if (previewSize == null) previewSize = captureSize;

        Log.i(TAG, "preview=" + previewSize + " captura=" + captureSize);

        thread = new HandlerThread("campro");
        thread.start();
        bg = new Handler(thread.getLooper());

        readerYuv = ImageReader.newInstance(
                captureSize.getWidth(), captureSize.getHeight(),
                ImageFormat.YUV_420_888, 6);

        readerJpeg = ImageReader.newInstance(
                captureSize.getWidth(), captureSize.getHeight(),
                ImageFormat.JPEG, 2);

        openDevice();
    }

    private Class<?> SurfaceHolderClass() {
        // SurfaceHolder e o tipo classico; usamos apenas para pedir tamanhos
        return android.view.SurfaceHolder.class;
    }

    private void openDevice() throws Exception {
        final Exception[] err = new Exception[1];
        final Object done = new Object();

        manager.openCamera(cameraId, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice d) {
                device = d;
                try {
                    createSession();
                } catch (Exception e) {
                    Log.e(TAG, "sessao: " + e.getMessage());
                }
                synchronized (done) { done.notifyAll(); }
            }

            @Override
            public void onDisconnected(CameraDevice d) {
                close();
                synchronized (done) { done.notifyAll(); }
            }

            @Override
            public void onError(CameraDevice d, int error) {
                err[0] = new RuntimeException("camera erro " + error);
                synchronized (done) { done.notifyAll(); }
            }
        }, bg);

        synchronized (done) {
            try { done.wait(5000); } catch (InterruptedException e) { /* segue */ }
        }
        if (err[0] != null) throw err[0];
        if (device == null) throw new RuntimeException("camera nao abriu");
    }

    private void createSession() throws CameraAccessException {
        List<Surface> outs = new ArrayList<Surface>();
        outs.add(previewSurface);
        outs.add(readerYuv.getSurface());
        outs.add(readerJpeg.getSurface());

        device.createCaptureSession(outs, new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(CameraCaptureSession s) {
                session = s;
                startPreview();
            }

            @Override
            public void onConfigureFailed(CameraCaptureSession s) {
                Log.e(TAG, "falha ao configurar sessao");
            }
        }, bg);
    }

    private void startPreview() {
        if (session == null || device == null) return;
        try {
            previewBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(previewSurface);
            applyParams(previewBuilder);
            session.setRepeatingRequest(previewBuilder.build(),
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(CameraCaptureSession s,
                                                       CaptureRequest r,
                                                       TotalCaptureResult res) {
                            /* preview nao processa resultado */
                        }
                    }, bg);
        } catch (CameraAccessException e) {
            Log.e(TAG, "preview: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ *
     * Parametros manuais
     * ------------------------------------------------------------------ */

    private void applyParams(CaptureRequest.Builder b) {
        b.set(CaptureRequest.CONTROL_AWB_MODE, awbMode);

        if (iso > 0 && exposureNanos > 0 && supportsManual()) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNanos);
            manualSensor = true;
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            if (evComp != 0) b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evComp);
            manualSensor = false;
        }

        if (focusDistance >= 0f) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance);
        } else if (meteringRect != null) {
            // toque para focar e medir: e o que o usuario espera de um app pro
            MeteringRectangle[] reg = new MeteringRectangle[]{
                    new MeteringRectangle(meteringRect, MeteringRectangle.METERING_WEIGHT_MAX)
            };
            b.set(CaptureRequest.CONTROL_AF_REGIONS, reg);
            b.set(CaptureRequest.CONTROL_AE_REGIONS, reg);
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        }
    }

    public void setManual(int newIso, long newExposure, float newFocus) {
        iso = newIso;
        exposureNanos = newExposure;
        focusDistance = newFocus;
        restartPreview();
    }

    public void setEvComp(int ev) {
        evComp = ev;
        restartPreview();
    }

    /** Define a regiao de foco/medicao em coordenadas do sensor (ativo). */
    public void setMeteringRegion(Rect r) {
        meteringRect = r;
        restartPreview();
    }

    public Rect getSensorRect() {
        if (chars == null) return null;
        return chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
    }

    public void setAwb(int mode) {
        awbMode = mode;
        restartPreview();
    }

    private void restartPreview() {
        if (session == null) return;
        try {
            session.stopRepeating();
            startPreview();
        } catch (CameraAccessException e) {
            Log.e(TAG, "restart: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ *
     * Captura
     * ------------------------------------------------------------------ */

    /** Foto unica em JPEG, processada pela camera. */
    public void captureJpeg(final JpegListener listener) {
        if (device == null) { listener.onError("camera fechada"); return; }

        readerJpeg.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader r) {
                Image img = r.acquireLatestImage();
                if (img == null) return;
                ByteBuffer b = img.getPlanes()[0].getBuffer();
                byte[] data = new byte[b.remaining()];
                b.get(data);
                img.close();
                listener.onJpeg(data);
            }
        }, bg);

        try {
            final CaptureRequest.Builder cb =
                    device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            cb.addTarget(readerJpeg.getSurface());
            cb.addTarget(previewSurface);
            applyParams(cb);
            cb.set(CaptureRequest.JPEG_ORIENTATION, 90);
            session.capture(cb.build(), null, bg);
        } catch (CameraAccessException e) {
            listener.onError(e.getMessage());
        }
    }

    /**
     * Dispara uma sequencia com exposicoes diferentes e devolve os
     * planos Y prontos para fusao.
     */
    public void captureBurst(final float[] evs, final Size size, final BurstListener listener) {
        if (device == null) { listener.onError("camera fechada"); return; }

        final List<Frame> coletados = new ArrayList<Frame>();
        final int total = evs.length;

        readerYuv.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
            @Override
            public void onImageAvailable(ImageReader r) {
                Image img = r.acquireLatestImage();
                if (img == null) return;
                Frame f = toFrame(img);
                img.close();
                if (f != null) {
                    synchronized (lock) {
                        coletados.add(f);
                        Log.i(TAG, "quadro " + coletados.size() + "/" + total);
                        if (coletados.size() >= total) {
                            listener.onBurst(new ArrayList<Frame>(coletados));
                        }
                    }
                }
            }
        }, bg);

        try {
            List<CaptureRequest> pedidos = new ArrayList<CaptureRequest>();
            long base = baseExposureNanos();

            for (int i = 0; i < total; i++) {
                CaptureRequest.Builder cb =
                        device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                cb.addTarget(readerYuv.getSurface());

                if (supportsManual()) {
                    long exp = (long) (base * Math.pow(2.0, evs[i]));
                    exp = clampExposure(exp);
                    cb.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                    cb.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp);
                    cb.set(CaptureRequest.SENSOR_SENSITIVITY, iso > 0 ? iso : 100);
                } else {
                    // sem controle manual: compensa via AE
                    int ev = (int) Math.round(evs[i] * 2.0f);
                    ev = clampEv(ev);
                    cb.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                    cb.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, ev);
                }
                cb.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                pedidos.add(cb.build());
            }

            session.captureBurst(pedidos, new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s,
                                               CaptureRequest r,
                                               TotalCaptureResult res) {
                    /* os quadros chegam pelo listener do reader */
                }
            }, bg);

        } catch (CameraAccessException e) {
            listener.onError(e.getMessage());
        }
    }

    private Frame toFrame(Image img) {
        if (img.getFormat() != ImageFormat.YUV_420_888) return null;
        Image.Plane[] p = img.getPlanes();
        if (p.length < 3) return null;

        Frame f = new Frame();
        f.width = img.getWidth();
        f.height = img.getHeight();

        Image.Plane py = p[0];
        f.y = Fusion.tightY(py.getBuffer(), py.getRowStride(), f.width, f.height);

        Image.Plane pu = p[1];
        Image.Plane pv = p[2];

        // copia AGORA: depois de img.close() os buffers diretos ficam invalidos
        ByteBuffer bu = pu.getBuffer();
        ByteBuffer bv = pv.getBuffer();
        f.u = new byte[bu.remaining()];
        bu.duplicate().get(f.u);
        f.v = new byte[bv.remaining()];
        bv.duplicate().get(f.v);

        f.uRowStride = pu.getRowStride();
        f.uPixStride = pu.getPixelStride();
        f.vRowStride = pv.getRowStride();
        f.vPixStride = pv.getPixelStride();
        return f;
    }

    /* ------------------------------------------------------------------ *
     * Consultas de capacidade
     * ------------------------------------------------------------------ */

    public boolean supportsManual() {
        if (chars == null) return false;
        Integer level = chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        if (level == null) return false;
        return level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL
                || level == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3;
    }

    public int[] isoRange() {
        Range<Integer> r = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        if (r == null) return new int[]{100, 1600};
        return new int[]{r.getLower(), r.getUpper()};
    }

    public long[] exposureRange() {
        Range<Long> r = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        if (r == null) return new long[]{1000000L, 125000000L};
        return new long[]{r.getLower(), r.getUpper()};
    }

    public int[] evRange() {
        Range<Integer> r = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        if (r == null) return new int[]{-6, 6};
        return new int[]{r.getLower(), r.getUpper()};
    }

    public float maxFocus() {
        Float f = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        if (f == null) return 0f;
        return f;
    }

    public long baseExposureNanos() {
        if (exposureNanos > 0) return exposureNanos;
        return 8333333L; // ~1/120s
    }

    public long clampExposure(long e) {
        long[] r = exposureRange();
        if (e < r[0]) return r[0];
        if (e > r[1]) return r[1];
        return e;
    }

    public int clampEv(int ev) {
        int[] r = evRange();
        if (ev < r[0]) return r[0];
        if (ev > r[1]) return r[1];
        return ev;
    }

    public Size getCaptureSize() { return captureSize; }

    public boolean isManualActive() { return manualSensor; }

    /* ------------------------------------------------------------------ *
     * Selecao
     * ------------------------------------------------------------------ */

    private String pickBackCamera() throws CameraAccessException {
        String[] ids = manager.getCameraIdList();
        for (String id : ids) {
            CameraCharacteristics c = manager.getCameraCharacteristics(id);
            Integer facing = c.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        if (ids.length > 0) return ids[0];
        throw new IllegalStateException("nenhuma camera");
    }

    /** Maior tamanho cujo lado longo nao passa de maxLong. */
    private static Size chooseSize(Size[] sizes, int maxLong) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = null;
        int bestLong = 0;
        for (Size s : sizes) {
            int l = Math.max(s.getWidth(), s.getHeight());
            if (l <= maxLong && l > bestLong) {
                bestLong = l;
                best = s;
            }
        }
        if (best == null) {
            // nenhum abaixo do limite: pega o menor
            best = sizes[0];
            for (Size s : sizes) {
                int l = Math.max(s.getWidth(), s.getHeight());
                int bl = Math.max(best.getWidth(), best.getHeight());
                if (l < bl) best = s;
            }
        }
        return best;
    }

    public void close() {
        try {
            if (session != null) { session.close(); session = null; }
        } catch (Exception e) { /* ignora */ }
        try {
            if (device != null) { device.close(); device = null; }
        } catch (Exception e) { /* ignora */ }
        if (readerYuv != null) { readerYuv.close(); readerYuv = null; }
        if (readerJpeg != null) { readerJpeg.close(); readerJpeg = null; }
        if (thread != null) { thread.quitSafely(); thread = null; }
    }
}
