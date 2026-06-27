package com.luongtd14.decode2surface;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoDecoder {
    private static final String TAG = "VideoDecoder";
    private static final long TIMEOUT_US = 10000;
    private volatile boolean isStopped = false;

    public void stop() {
        isStopped = true;
    }

    public void decodeToFile(String inputPath, String outputFilePath) throws IOException {
        isStopped = false;
        process(inputPath, null, outputFilePath);
    }

    public void decodeToSurface(String inputPath, Surface surface) throws IOException {
        isStopped = false;
        process(inputPath, surface, null);
    }

    private void process(String inputPath, Surface surface, String outputFilePath) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(inputPath);

        int trackIndex = selectVideoTrack(extractor);
        if (trackIndex < 0) {
            extractor.release();
            throw new RuntimeException("No video track found");
        }
        extractor.selectTrack(trackIndex);

        MediaFormat format = extractor.getTrackFormat(trackIndex);
        String mime = format.getString(MediaFormat.KEY_MIME);

        // Lấy thông tin xoay của video từ metadata
        int rotation = 0;
        if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            rotation = format.getInteger(MediaFormat.KEY_ROTATION);
        }

        MediaCodec decoder = MediaCodec.createDecoderByType(mime);
        // BUFFER MODE: Cấu hình với surface = null
        decoder.configure(format, null, null, 0);
        decoder.start();

        FileOutputStream fos = (outputFilePath != null) ? new FileOutputStream(outputFilePath) : null;

        try {
            decodeLoop(extractor, decoder, surface, fos, rotation);
        } finally {
            decoder.stop();
            decoder.release();
            extractor.release();
            if (fos != null) {
                try {
                    fos.close();
                } catch (IOException ignored) {}
            }
        }
    }

    private void decodeLoop(MediaExtractor extractor, MediaCodec decoder, Surface surface, FileOutputStream fos, int rotation) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean isInputEOS = false;
        boolean isOutputEOS = false;
        long startMs = -1;
        long firstSampleTimeUs = -1;

        while (!isOutputEOS && !isStopped) {
            if (!isInputEOS) {
                int inIdx = decoder.dequeueInputBuffer(TIMEOUT_US);
                if (inIdx >= 0) {
                    ByteBuffer buffer = decoder.getInputBuffer(inIdx);
                    int sampleSize = extractor.readSampleData(buffer, 0);
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        isInputEOS = true;
                    } else {
                        decoder.queueInputBuffer(inIdx, 0, sampleSize, extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }

            int outIdx = decoder.dequeueOutputBuffer(info, TIMEOUT_US);
            if (outIdx >= 0) {
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    isOutputEOS = true;
                }

                if (fos != null && info.size > 0) {
                    ByteBuffer outBuf = decoder.getOutputBuffer(outIdx);
                    byte[] data = new byte[info.size];
                    outBuf.get(data);
                    try {
                        fos.write(data);
                    } catch (IOException ignored) {}
                } else if (surface != null && info.size > 0) {
                    // Render nội dung từ Buffer lên Surface, có xử lý xoay hình ảnh
                    renderBufferToSurface(decoder, outIdx, info, surface, rotation);

                    // Đồng bộ thời gian thực
                    if (startMs == -1) {
                        startMs = System.currentTimeMillis();
                        firstSampleTimeUs = info.presentationTimeUs;
                    }
                    long playTimeUs = info.presentationTimeUs - firstSampleTimeUs;
                    long sleepTimeMs = (playTimeUs / 1000) - (System.currentTimeMillis() - startMs);
                    if (sleepTimeMs > 10) {
                        try {
                            Thread.sleep(sleepTimeMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }

                decoder.releaseOutputBuffer(outIdx, false);
            }
        }
    }

    private void renderBufferToSurface(MediaCodec decoder, int index, MediaCodec.BufferInfo info, Surface surface, int rotation) {
        try (Image image = decoder.getOutputImage(index)) {
            if (image != null) {
                Bitmap bitmap = yuv420ToBitmap(image);
                Canvas canvas = surface.lockCanvas(null);
                if (canvas != null) {
                    int canvasWidth = canvas.getWidth();
                    int canvasHeight = canvas.getHeight();
                    int bitmapWidth = bitmap.getWidth();
                    int bitmapHeight = bitmap.getHeight();

                    // Tính toán kích thước hiệu dụng sau khi xoay (90/270 thì W và H đổi chỗ)
                    int rotatedWidth = (rotation == 90 || rotation == 270) ? bitmapHeight : bitmapWidth;
                    int rotatedHeight = (rotation == 90 || rotation == 270) ? bitmapWidth : bitmapHeight;

                    // Tính toán tỉ lệ Fit Center
                    float scale = Math.min((float) canvasWidth / rotatedWidth, (float) canvasHeight / rotatedHeight);
                    
                    canvas.drawColor(android.graphics.Color.BLACK); // Xóa nền

                    Matrix matrix = new Matrix();
                    // 1. Dời tâm bitmap về gốc (0,0)
                    matrix.postTranslate(-bitmapWidth / 2f, -bitmapHeight / 2f);
                    // 2. Thực hiện xoay
                    matrix.postRotate(rotation);
                    // 3. Thực hiện scale
                    matrix.postScale(scale, scale);
                    // 4. Dời về tâm màn hình
                    matrix.postTranslate(canvasWidth / 2f, canvasHeight / 2f);

                    canvas.drawBitmap(bitmap, matrix, new Paint(Paint.FILTER_BITMAP_FLAG));
                    
                    surface.unlockCanvasAndPost(canvas);
                }
                bitmap.recycle();
            }
        } catch (Exception e) {
            Log.e(TAG, "Manual render error", e);
        }
    }

    private Bitmap yuv420ToBitmap(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();

        int yRowStride = planes[0].getRowStride();
        int uvRowStride = planes[1].getRowStride();
        int uvPixelStride = planes[1].getPixelStride();

        int[] pixels = new int[width * height];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int yIdx = y * yRowStride + x;
                int uvIdx = (y / 2) * uvRowStride + (x / 2) * uvPixelStride;

                int Y = (yBuffer.get(yIdx) & 0xff);
                int U = (uBuffer.get(uvIdx) & 0xff) - 128;
                int V = (vBuffer.get(uvIdx) & 0xff) - 128;

                int r = (int) (Y + 1.370705f * V);
                int g = (int) (Y - 0.337633f * U - 0.698001f * V);
                int b = (int) (Y + 1.732446f * U);

                r = Math.max(0, Math.min(255, r));
                g = Math.max(0, Math.min(255, g));
                b = Math.max(0, Math.min(255, b));

                pixels[y * width + x] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    private int selectVideoTrack(MediaExtractor extractor) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("video/")) return i;
        }
        return -1;
    }
}
