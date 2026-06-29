package com.luongtd14.decode2surface;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.luongtd14.decode2surface.databinding.ActivityMainBinding;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 100;

    private ActivityMainBinding binding;
    private final List<String> selectedPaths = new ArrayList<>();
    private final VideoDecoder videoDecoder = new VideoDecoder();
    
    private Surface legacySurface;
    private Surface glSurface;
    private volatile boolean isLegacySurfaceReady = false;
    private volatile boolean isGLSurfaceReady = false;
    private volatile boolean isRunning = false;

    private final ActivityResultLauncher<Intent> videoPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    selectedPaths.clear();
                    if (result.getData().getClipData() != null) {
                        int count = result.getData().getClipData().getItemCount();
                        for (int i = 0; i < count; i++) {
                            Uri uri = result.getData().getClipData().getItemAt(i).getUri();
                            String path = PathUtils.getPathFromUri(this, uri);
                            if (path != null) selectedPaths.add(path);
                        }
                    } else if (result.getData().getData() != null) {
                        Uri uri = result.getData().getData();
                        String path = PathUtils.getPathFromUri(this, uri);
                        if (path != null) selectedPaths.add(path);
                    }
                    updateSelectedFilesUI();
                }
            }
    );

    private enum DecodeMode { FILE, SURFACE, SURFACE_GL }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Setup Legacy SurfaceView
        binding.surfaceView.getHolder().addCallback(this);

        // Setup GLSurfaceView
        setupGL();

        // Đặt trạng thái ban đầu: Hiện SurfaceView cũ, ẩn GLSurfaceView (nhưng vẫn để Invisible để nó khởi tạo)
        binding.surfaceView.setVisibility(View.VISIBLE);
        binding.glSurfaceView.setVisibility(View.INVISIBLE);

        binding.btnPickVideos.setOnClickListener(v -> pickVideos());
        binding.btnDecodeToFile.setOnClickListener(v -> startDecoding(DecodeMode.FILE));
        binding.btnDecodeToSurface.setOnClickListener(v -> startDecoding(DecodeMode.SURFACE));
        binding.btnDecodeToSurfaceGL.setOnClickListener(v -> startDecoding(DecodeMode.SURFACE_GL));
        binding.btnStop.setOnClickListener(v -> stopDecoding());

        updateButtonStates();
        requestPermissions();
    }

    private void setupGL() {
        binding.glSurfaceView.setEGLContextClientVersion(2);
        GLRenderer renderer = new GLRenderer();
        renderer.setOnSurfaceReadyListener(surface -> {
            glSurface = surface;
            isGLSurfaceReady = true;
            Log.d(TAG, "OpenGL Surface Ready");
        });
        binding.glSurfaceView.setRenderer(renderer);
        binding.glSurfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    }

    private void pickVideos() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("video/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        videoPickerLauncher.launch(intent);
    }

    private void updateSelectedFilesUI() {
        StringBuilder sb = new StringBuilder();
        String storageRoot = Environment.getExternalStorageDirectory().getAbsolutePath();
        for (String path : selectedPaths) {
            String displayPath = path;
            if (path.startsWith(storageRoot)) {
                displayPath = path.substring(storageRoot.length());
                if (displayPath.startsWith("/")) displayPath = displayPath.substring(1);
            }
            sb.append(displayPath).append("\n");
        }
        binding.tvSelectedFiles.setText(selectedPaths.isEmpty() ? "No files selected" : sb.toString().trim());
    }

    private void updateButtonStates() {
        runOnUiThread(() -> {
            binding.btnPickVideos.setEnabled(!isRunning);
            binding.btnDecodeToFile.setEnabled(!isRunning);
            binding.btnDecodeToSurface.setEnabled(!isRunning);
            binding.btnDecodeToSurfaceGL.setEnabled(!isRunning);
            binding.btnStop.setEnabled(isRunning);
        });
    }

    private void stopDecoding() {
        videoDecoder.stop();
        Toast.makeText(this, "Stopping...", Toast.LENGTH_SHORT).show();
    }

    private void startDecoding(DecodeMode mode) {
        if (selectedPaths.isEmpty()) {
            Toast.makeText(this, "Please select videos first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (mode == DecodeMode.SURFACE && !isLegacySurfaceReady) {
            Toast.makeText(this, "Legacy Surface not ready", Toast.LENGTH_SHORT).show();
            return;
        }

        // Nếu chọn GL nhưng chưa ready, thử hiện view lên để ép hệ thống khởi tạo
        if (mode == DecodeMode.SURFACE_GL && !isGLSurfaceReady) {
            runOnUiThread(() -> {
                binding.glSurfaceView.setVisibility(View.VISIBLE);
                binding.surfaceView.setVisibility(View.GONE);
            });
            Toast.makeText(this, "Waiting for OpenGL initialization...", Toast.LENGTH_SHORT).show();
            return;
        }

        isRunning = true;
        updateButtonStates();

        // Switch View Visibility
        runOnUiThread(() -> {
            binding.surfaceView.setVisibility(mode == DecodeMode.SURFACE ? View.VISIBLE : View.GONE);
            binding.glSurfaceView.setVisibility(mode == DecodeMode.SURFACE_GL ? View.VISIBLE : View.GONE);
        });

        new Thread(() -> {
            for (String path : selectedPaths) {
                try {
                    switch (mode) {
                        case FILE:
                            String timeStamp = new SimpleDateFormat("ddMMyyyy_HHssmm", Locale.getDefault()).format(new Date());
                            String outPath = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), 
                                    "codec_" + timeStamp + ".raw").getAbsolutePath();
                            Log.d(TAG, "Decoding to file: " + outPath);
                            videoDecoder.decodeToFile(path, outPath);
                            break;
                        case SURFACE:
                            if (legacySurface != null && legacySurface.isValid()) {
                                Log.d(TAG, "Decoding to Legacy Surface (Slow): " + path);
                                videoDecoder.decodeToSurface(path, legacySurface);
                            }
                            break;
                        case SURFACE_GL:
                            if (glSurface != null && glSurface.isValid()) {
                                Log.d(TAG, "Decoding to OpenGL Surface (Fast): " + path);
                                videoDecoder.decodeToSurfaceGL(path, glSurface);
                            }
                            break;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error: " + path, e);
                }
            }
            isRunning = false;
            updateButtonStates();
            runOnUiThread(() -> Toast.makeText(this, "Process Finished", Toast.LENGTH_SHORT).show());
        }).start();
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        legacySurface = holder.getSurface();
        isLegacySurfaceReady = true;
        Log.d(TAG, "Legacy Surface Ready");
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        isLegacySurfaceReady = false;
        videoDecoder.stop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        binding.glSurfaceView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        binding.glSurfaceView.onPause();
        videoDecoder.stop();
    }

    private void requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                }
            }
        } else {
            String[] perms = {Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE};
            List<String> needed = new ArrayList<>();
            for (String p : perms) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) needed.add(p);
            }
            if (!needed.isEmpty()) ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            for (int res : grantResults) {
                if (res != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Permission required", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
}
