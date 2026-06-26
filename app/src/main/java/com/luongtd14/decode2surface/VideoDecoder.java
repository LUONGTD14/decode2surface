package com.luongtd14.decode2surface;

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

    public void decodeToFile(String inputPath, String outputFilePath) throws IOException {
        Log.d(TAG, "decodeToFile started: " + inputPath);
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(inputPath);

        int trackIndex = selectVideoTrack(extractor);
        if (trackIndex < 0) {
            extractor.release();
            throw new RuntimeException("No video track found");
        }
        extractor.selectTrack(trackIndex);

        MediaFormat format = extractor.getTrackFormat(trackIndex);
        MediaCodec decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        decoder.configure(format, null, null, 0);
        decoder.start();

        try (FileOutputStream fos = new FileOutputStream(outputFilePath)) {
            decodeLoop(extractor, decoder, null, fos);
        } finally {
            decoder.stop();
            decoder.release();
            extractor.release();
            Log.d(TAG, "decodeToFile finished");
        }
    }

    public void decodeToSurface(String inputPath, Surface surface) throws IOException {
        if (surface == null || !surface.isValid()) {
            throw new RuntimeException("Surface is null or invalid");
        }

        Log.d(TAG, "decodeToSurface started: " + inputPath);
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(inputPath);

        int trackIndex = selectVideoTrack(extractor);
        if (trackIndex < 0) {
            extractor.release();
            throw new RuntimeException("No video track found");
        }
        extractor.selectTrack(trackIndex);

        MediaFormat format = extractor.getTrackFormat(trackIndex);
        MediaCodec decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
        
        // Cấu hình decoder để render trực tiếp lên Surface
        decoder.configure(format, surface, null, 0);
        decoder.start();

        try {
            decodeLoop(extractor, decoder, surface, null);
        } finally {
            decoder.stop();
            decoder.release();
            extractor.release();
            Log.d(TAG, "decodeToSurface finished");
        }
    }

    private void decodeLoop(MediaExtractor extractor, MediaCodec decoder, Surface surface, FileOutputStream fos) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean isInputEOS = false;
        boolean isOutputEOS = false;
        
        long startMs = -1;
        long firstSampleTimeUs = -1;
        int frameCount = 0;

        while (!isOutputEOS) {
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

                if (surface != null) {
                    // Đồng bộ thời gian để video chạy đúng tốc độ
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
                    // Render lên surface (chỉ render nếu có dữ liệu)
                    decoder.releaseOutputBuffer(outIdx, info.size > 0);
                } else if (fos != null) {
                    ByteBuffer outBuf = decoder.getOutputBuffer(outIdx);
                    if (info.size > 0 && outBuf != null) {
                        byte[] data = new byte[info.size];
                        outBuf.get(data);
                        try {
                            fos.write(data);
                        } catch (IOException e) {
                            Log.e(TAG, "Write error", e);
                        }
                    }
                    decoder.releaseOutputBuffer(outIdx, false);
                } else {
                    decoder.releaseOutputBuffer(outIdx, false);
                }
                
                frameCount++;
                if (frameCount % 60 == 0) Log.d(TAG, "Decoded frames: " + frameCount);
            }
        }
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
