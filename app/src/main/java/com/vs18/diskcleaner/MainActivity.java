package com.vs18.diskcleaner;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.*;
import androidx.core.app.ActivityCompat;
import androidx.documentfile.provider.DocumentFile;
import com.vs18.diskcleaner.databinding.ActivityMainBinding;
import java.io.File;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private static final boolean DRY_RUN = false;
    private static final int MAX_DELETE_FILES = 300;
    private final Set<File> legacyDirs = new HashSet<>();
    private final List<File> legacyJunk = new ArrayList<>();
    private final Map<String, Uri> safDirs = new HashMap<>();
    private final List<DocumentFile> safJunk = new ArrayList<>();
    private long totalSize = 0;

    private final ActivityResultLauncher<Uri> directoryPicker =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;

                getContentResolver().takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                );

                DocumentFile tree = DocumentFile.fromTreeUri(this, uri);
                if (tree == null) return;

                String name = tree.getName() != null ? tree.getName() : uri.toString();
                addSafDirectory(name, uri);
            });

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        ActivityCompat.requestPermissions(
                this,
                new String[] {
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                },
                1
        );

        addDefaultLegacyDirs();

        binding.addFolderButton.setOnClickListener(v -> directoryPicker.launch(null));
        binding.scanButton.setOnClickListener(v -> scan());
        binding.cleanButton.setOnClickListener(v -> clean());
    }

    private void addDefaultLegacyDirs() {
        if (Build.VERSION.SDK_INT >= 29) return;

        List<File> defaults = Arrays.asList(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        );

        for (File f : defaults) {
            if (f.exists() && f.canRead() && f.canWrite()) {
                legacyDirs.add(f);
                addCheckbox("📂 " + f.getAbsolutePath());
            }
        }
    }

    private void addSafDirectory(String name, Uri uri) {
        if (safDirs.containsKey(name)) return;
        safDirs.put(name, uri);
        addCheckbox("🔐 " + name);
    }

    private void addCheckbox(String text) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        cb.setChecked(true);
        cb.setTextColor(0xFFD4D4D4);
        binding.dirList.addView(cb);
    }

    @SuppressLint("SetTextI18n")
    private void scan() {
        legacyJunk.clear();
        safJunk.clear();
        totalSize = 0;

        binding.reportText.setText("🔄 Сканування...");

        new Thread(() -> {
            if (Build.VERSION.SDK_INT < 29) {
                for (File dir : legacyDirs) scanLegacy(dir);
            } else {
                for (Uri uri : safDirs.values()) {
                    DocumentFile root = DocumentFile.fromTreeUri(this, uri);
                    if (root != null) scanSaf(root);
                }
            }

            runOnUiThread(() -> {
                binding.reportText.setText(
                        "✅ Файлів: " + (legacyJunk.size() + safJunk.size()) + "\n" +
                                "💾 Розмір: " + formatSize(totalSize)
                );
                binding.cleanButton.setEnabled(true);
            });
        }).start();
    }

    private void scanLegacy(File dir) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File f : files) {
            if (f.isDirectory()) {
                scanLegacy(f);
            } else if (isLegacyJunk(f)) {
                legacyJunk.add(f);
                totalSize += f.length();
            }
        }
    }

    private void scanSaf(DocumentFile dir) {
        for (DocumentFile f : dir.listFiles()) {
            if (f.isDirectory()) {
                scanSaf(f);
            } else if (isSafJunk(f)) {
                safJunk.add(f);
                totalSize += f.length();
            }
        }
    }

    private boolean isProtectedPath(String path) {
        path = path.toLowerCase(Locale.ROOT);
        return path.contains("/dcim/camera")
                || path.contains("/pictures/photos")
                || path.contains("/pictures/instagram")
                || path.contains("/pictures/whatsapp")
                || path.contains("/movies")
                || path.contains("/music");
    }

    private boolean isLegacyJunk(File file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        String path = file.getAbsolutePath().toLowerCase(Locale.ROOT);

        if (isProtectedPath(path)) return false;

        if (path.contains("screenshot") || path.contains("thumbnail")) return true;

        return name.endsWith(".tmp")
                || name.endsWith(".log")
                || name.endsWith(".cache")
                || name.endsWith(".bak")
                || name.endsWith(".csv")
                || name.contains("thumb");
    }

    private boolean isSafJunk(DocumentFile file) {
        String name = file.getName();
        if (name == null) return false;

        name = name.toLowerCase(Locale.ROOT);

        if (name.matches(".*\\.(jpg|jpeg|png|webp)$")) {
            return name.contains("screenshot")
                    || name.contains("thumb")
                    || name.contains("cache");
        }

        return name.endsWith(".tmp")
                || name.endsWith(".log")
                || name.endsWith(".bak");
    }

    private void clean() {
        String preview = buildPreView();

        new AlertDialog.Builder(this)
                .setTitle("Підтвердження")
                .setMessage("Буде видалено:\n\n" + preview)
                .setPositiveButton("Видалити", (d, w) -> new Thread(() -> {
                    if (DRY_RUN) {
                        runOnUiThread(() ->
                                Toast.makeText(this, "🧪 Dry-run: файли НЕ видалені", Toast.LENGTH_LONG).show());
                        return;
                    }

                    int deleted = 0;

                    if (Build.VERSION.SDK_INT < 29) {
                        for (File f : legacyJunk) {
                            if (deleted >= MAX_DELETE_FILES) break;
                            if (f.delete()) deleted++;
                        }
                    } else {
                        for (DocumentFile f : safJunk) {
                            if (deleted >= MAX_DELETE_FILES) break;
                            if (f.delete()) deleted++;
                        }
                    }

                    int result = deleted;
                    runOnUiThread(() ->
                            Toast.makeText(this, "🧹 Видалено: " + result, Toast.LENGTH_LONG).show()
                    );

                }).start())
                .setNegativeButton("Скасувати", null)
                .show();
    }

    private String buildPreView() {
        StringBuilder sb = new StringBuilder();
        int count = 0;

        for (File f : legacyJunk) {
            if (count++ >= 5) break;
            sb.append(f.getName()).append("\n");
        }

        for (DocumentFile f : safJunk) {
            if (count++ >= 5) break;
            sb.append(f.getName()).append("\n");
        }

        if ((legacyJunk.size() + safJunk.size()) > 5) sb.append("...");

        return sb.toString();
    }

    @SuppressLint("DefaultLocale")
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format("%.1f GB", gb);
    }
}