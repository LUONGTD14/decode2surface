package com.luongtd14.decode2surface;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.luongtd14.decode2surface.databinding.ActivityMainBinding;

import java.io.File;
import java.io.IOException;
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
    private boolean isSurfaceReady = false;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        binding.surfaceView.getHolder().addCallback(this);

        binding.btnPickVideos.setOnClickListener(v -> pickVideos());
        binding.btnDecodeToFile.setOnClickListener(v -> startDecoding(true));
        binding.btnDecodeToSurface.setOnClickListener(v -> startDecoding(false));
        binding.btnStop.setOnClickListener(v -> stopDecoding());

        updateButtonStates();
        requestPermissions();
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
            binding.btnStop.setEnabled(isRunning);
        });
    }

    private void stopDecoding() {
        videoDecoder.stop();
        Toast.makeText(this, "Stopping...", Toast.LENGTH_SHORT).show();
    }

    private void startDecoding(boolean toFile) {
        if (selectedPaths.isEmpty()) {
            Toast.makeText(this, "Please select videos first", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!toFile && !isSurfaceReady) {
            Toast.makeText(this, "Surface is not ready yet", Toast.LENGTH_SHORT).show();
            return;
        }

        isRunning = true;
        updateButtonStates();

        new Thread(() -> {
            for (String path : selectedPaths) {
                try {
                    if (toFile) {
                        String timeStamp = new SimpleDateFormat("ddMMyyyy_HHssmm", Locale.getDefault()).format(new Date());
                        String outName = "codec_" + timeStamp + ".raw";
                        File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                        if (!downloadsDir.exists()) downloadsDir.mkdirs();
                        String outPath = new File(downloadsDir, outName).getAbsolutePath();
                        
                        Log.d(TAG, "Decoding to file: " + outPath);
                        videoDecoder.decodeToFile(path, outPath);
                    } else {
                        Surface surface = binding.surfaceView.getHolder().getSurface();
                        if (surface != null && surface.isValid()) {
                            Log.d(TAG, "Decoding to surface (Buffer Mode): " + path);
                            videoDecoder.decodeToSurface(path, surface);
                        }
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
        isSurfaceReady = true;
        Log.d(TAG, "Surface Ready");
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        isSurfaceReady = false;
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
