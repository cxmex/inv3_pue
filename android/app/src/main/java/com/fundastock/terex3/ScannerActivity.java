package com.fundastock.terex3;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Full-screen barcode scanner: CameraX preview + on-device ML Kit detection. */
public class ScannerActivity extends AppCompatActivity {

    public static final String EXTRA_BARCODE = "barcode";

    private PreviewView previewView;
    private TextView tvStatus;
    private ExecutorService cameraExecutor;
    private BarcodeScanner scanner;
    private final AtomicBoolean handled = new AtomicBoolean(false);

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "Se requiere permiso de cámara", Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_scanner);

        previewView = findViewById(R.id.previewView);
        tvStatus = findViewById(R.id.tvScanStatus);
        findViewById(R.id.btnCloseScanner).setOnClickListener(v -> finish());

        cameraExecutor = Executors.newSingleThreadExecutor();

        BarcodeScannerOptions options = new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                        Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8,
                        Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                        Barcode.FORMAT_CODE_128, Barcode.FORMAT_CODE_39,
                        Barcode.FORMAT_QR_CODE)
                .build();
        scanner = BarcodeScanning.getClient(options);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void startCamera() {
        var cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                CameraSelector selector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, selector, preview, imageAnalysis);
            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "Error de cámara: " + e.getMessage(), Toast.LENGTH_LONG).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeFrame(@NonNull ImageProxy imageProxy) {
        if (handled.get() || imageProxy.getImage() == null) {
            imageProxy.close();
            return;
        }
        InputImage image = InputImage.fromMediaImage(imageProxy.getImage(), imageProxy.getImageInfo().getRotationDegrees());
        Task<List<Barcode>> task = scanner.process(image);
        task.addOnSuccessListener(barcodes -> {
            if (!barcodes.isEmpty() && handled.compareAndSet(false, true)) {
                String value = barcodes.get(0).getRawValue();
                if (value != null && !value.isEmpty()) {
                    runOnUiThread(() -> tvStatus.setText("Encontrado: " + value));
                    Intent result = new Intent();
                    result.putExtra(EXTRA_BARCODE, value);
                    setResult(RESULT_OK, result);
                    finish();
                } else {
                    handled.set(false);
                }
            }
        }).addOnFailureListener(e -> {
            // Transient — keep scanning
        }).addOnCompleteListener(t -> imageProxy.close());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
        scanner.close();
    }
}
