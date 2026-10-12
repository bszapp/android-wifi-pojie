package io.github.bszapp.wifitoolbox.service.camera;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.AttributionSource;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.media.Image;
import android.media.ImageReader;
import android.os.*;
import android.os.Process;
import android.util.Size;
import android.util.Range;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** app_process 诊断入口：只输出帧尺寸/数量，不输出或保存图像。 */
public final class CameraDeviceProbe {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object instance = thread.getMethod("systemMain").invoke(null);
        Context base = (Context) thread.getMethod("getSystemContext").invoke(instance);
        String pkg = Process.myUid() == 2000 ? "com.android.shell" : "android";
        Context context = new ContextWrapper(base.createPackageContext(pkg, 0)) {
            public String getOpPackageName() { return pkg; }
            public String getPackageName() { return pkg; }
            public AttributionSource getAttributionSource() {
                return new AttributionSource.Builder(Process.myUid()).setPackageName(pkg).build();
            }
        };
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        HandlerThread worker = new HandlerThread("camera-probe"); worker.start();
        Handler handler = new Handler(worker.getLooper());
        CountDownLatch done = new CountDownLatch(1);
        final CameraDevice[] device = new CameraDevice[1];
        final CameraCaptureSession[] session = new CameraCaptureSession[1];
        final List<Double> gaps = new ArrayList<>();
        final List<Double> delays = new ArrayList<>();
        final long[] timing = {0, 0};
        final int[] frames = {0};
        final int[] failures = {0};
        final long started = SystemClock.elapsedRealtimeNanos();
        ImageReader reader = null;
        try {
            String id = null;
            for (String candidate : manager.getCameraIdList()) {
                CameraCharacteristics c = manager.getCameraCharacteristics(candidate);
                if (Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(c.get(CameraCharacteristics.LENS_FACING))) {
                    id = candidate; break;
                }
            }
            if (id == null) throw new IllegalStateException("No rear camera");
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            final boolean realtime = Integer.valueOf(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME)
                    .equals(characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE));
            Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            Range<Integer> fps = null;
            for (Range<Integer> range : ranges) {
                if (range.getUpper() == 30 && (fps == null || range.getLower() > fps.getLower())) fps = range;
            }
            final Range<Integer> targetFps = fps;
            Size[] sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.YUV_420_888);
            Size selected = sizes[0];
            for (Size size : sizes) if (size.getWidth() == 640 && size.getHeight() == 480) selected = size;
            System.out.println("uid=" + Process.myUid() + " api=" + Build.VERSION.SDK_INT + " rear=" + id + " size=" + selected
                    + " fpsRange=" + fps + " realtimeTimestamp=" + realtime);
            reader = ImageReader.newInstance(selected.getWidth(), selected.getHeight(), ImageFormat.YUV_420_888, 2);
            final ImageReader output = reader;
            reader.setOnImageAvailableListener(source -> {
                try (Image image = source.acquireLatestImage()) {
                    if (image == null) return;
                    if (done.getCount() == 0) return;
                    long now = SystemClock.elapsedRealtimeNanos();
                    if (frames[0] == 0) {
                        timing[0] = now;
                        System.out.printf(Locale.US, "firstFrameMs=%.2f%n", (now - started) / 1e6);
                    } else gaps.add((now - timing[1]) / 1e6);
                    timing[1] = now;
                    if (realtime) delays.add((now - image.getTimestamp()) / 1e6);
                    frames[0]++;
                    if (now - timing[0] >= 5_000_000_000L) {
                        double seconds = (now - timing[0]) / 1e9;
                        System.out.printf(Locale.US, "PASS duration=%.3fs frames=%d fps=%.2f failures=%d gapP95=%.2fms gapMax=%.2fms gapsOver100ms=%d%n",
                                seconds, frames[0], (frames[0]-1)/seconds, failures[0], percentile(gaps, .95), percentile(gaps, 1),
                                gaps.stream().filter(value -> value > 100).count());
                        if (realtime) System.out.printf(Locale.US, "sensorToReaderDelay median=%.2fms p95=%.2fms max=%.2fms%n",
                                percentile(delays, .5), percentile(delays, .95), percentile(delays, 1));
                        done.countDown();
                    }
                }
            }, handler);
            manager.openCamera(id, new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice camera) {
                    device[0] = camera;
                    try {
                        camera.createCaptureSession(Collections.singletonList(output.getSurface()), new CameraCaptureSession.StateCallback() {
                            public void onConfigured(CameraCaptureSession capture) {
                                session[0] = capture;
                                try {
                                    CaptureRequest.Builder request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                    request.addTarget(output.getSurface());
                                    if (targetFps != null) request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetFps);
                                    capture.setRepeatingRequest(request.build(), new CameraCaptureSession.CaptureCallback() {
                                        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) { failures[0]++; }
                                    }, handler);
                                } catch (Exception error) { error.printStackTrace(); done.countDown(); }
                            }
                            public void onConfigureFailed(CameraCaptureSession capture) { System.out.println("FAIL configure"); done.countDown(); }
                        }, handler);
                    } catch (Exception error) { error.printStackTrace(); done.countDown(); }
                }
                public void onDisconnected(CameraDevice camera) { camera.close(); System.out.println("FAIL disconnected"); done.countDown(); }
                public void onError(CameraDevice camera, int error) { camera.close(); System.out.println("FAIL camera=" + error); done.countDown(); }
            }, handler);
            if (!done.await(15, TimeUnit.SECONDS)) System.out.println("FAIL timeout");
        } finally {
            if (session[0] != null) session[0].close();
            if (device[0] != null) device[0].close();
            if (reader != null) reader.close();
            worker.quitSafely();
        }
        System.exit(0);
    }

    private static double percentile(List<Double> values, double percentile) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(values); Collections.sort(sorted);
        return sorted.get(Math.min(sorted.size()-1, (int)Math.ceil(sorted.size()*percentile)-1));
    }
}
