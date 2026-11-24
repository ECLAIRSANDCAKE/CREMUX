package com.termux.app.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Zips a folder with a chosen compression level and, optionally, cuts the archive into parts of a fixed size.
 *
 * <p>The zip is streamed straight into the parts, so no second full-size copy is ever written. Parts are
 * named {@code name.zip.001}, {@code name.zip.002}, … and are plain byte slices of one ordinary zip:
 * 7-Zip, ZArchiver and WinRAR open the {@code .001} file directly, and {@code cat name.zip.* > name.zip}
 * joins them on any system. If everything fits in one part it is simply {@code name.zip}.
 */
public final class ZipSplitter {

    public interface Progress {
        /** @param doneBytes uncompressed bytes read so far, @param totalBytes uncompressed total. */
        void onProgress(long doneBytes, long totalBytes, @NonNull String currentFile);
    }

    public static final class Result {
        @NonNull public final List<File> parts;
        public final long inputBytes;
        public final long outputBytes;
        public final int fileCount;

        Result(@NonNull List<File> parts, long inputBytes, long outputBytes, int fileCount) {
            this.parts = parts;
            this.inputBytes = inputBytes;
            this.outputBytes = outputBytes;
            this.fileCount = fileCount;
        }
    }

    public static final class CancelledException extends IOException {
        public CancelledException() {
            super("Cancelled");
        }
    }

    private static final int MAX_ENTRIES = 65535;
    private static final long MAX_INPUT_BYTES = 4L * 1024 * 1024 * 1024 - 1;
    private static final int MAX_PARTS = 999;

    private ZipSplitter() { }

    /** Decides which directories are left out of the archive (e.g. build output). */
    public interface DirFilter {
        boolean skip(@NonNull File dir);
    }

    /**
     * @param level     0 (store) … 9 (maximum compression)
     * @param partBytes maximum size of each part in bytes; 0 or less means a single file
     */
    @NonNull
    public static Result zipFolder(@NonNull File sourceDir, @NonNull File outDir, @NonNull String baseName, int level,
                                   long partBytes, @Nullable Progress progress, @NonNull AtomicBoolean cancelled)
        throws IOException {
        return zipFolder(sourceDir, outDir, baseName, level, partBytes, progress, cancelled, null);
    }

    @NonNull
    public static Result zipFolder(@NonNull File sourceDir, @NonNull File outDir, @NonNull String baseName, int level,
                                   long partBytes, @Nullable Progress progress, @NonNull AtomicBoolean cancelled,
                                   @Nullable DirFilter filter)
        throws IOException {
        if (!sourceDir.isDirectory()) throw new IOException("Not a folder: " + sourceDir);
        if (!outDir.isDirectory() && !outDir.mkdirs()) throw new IOException("Cannot create " + outDir);

        List<File> files = new ArrayList<>();
        long[] total = {0};
        final File root = sourceDir.getCanonicalFile();
        collect(root, files, total, filter);
        if (files.isEmpty()) throw new IOException("The folder is empty, nothing to zip.");
        // Android's zip writer has no ZIP64 support, so beyond these limits the archive could come out corrupt.
        if (files.size() > MAX_ENTRIES) {
            throw new IOException("Too many files for one zip (" + files.size() + ", the limit is " + MAX_ENTRIES
                + "). Zip a single tool's output instead, or use tar from the terminal.");
        }
        if (total[0] > MAX_INPUT_BYTES) {
            throw new IOException("The result is larger than 4 GB, which a zip file cannot hold safely here.");
        }

        // Clear leftovers from an earlier run with the same name so stale parts are never mixed in.
        deleteExisting(outDir, baseName);

        SplitOutputStream split = new SplitOutputStream(outDir, baseName + ".zip", partBytes);
        boolean ok = false;
        try {
            ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(split, 64 * 1024));
            zip.setLevel(Math.max(0, Math.min(9, level)));
            zip.setMethod(ZipOutputStream.DEFLATED);

            String rootPrefix = root.getPath() + File.separator;
            long done = 0;
            byte[] buf = new byte[64 * 1024];
            for (File f : files) {
                if (cancelled.get()) throw new CancelledException();
                String name = f.getPath().substring(rootPrefix.length()).replace(File.separatorChar, '/');
                ZipEntry e = new ZipEntry(name);
                e.setTime(f.lastModified());
                zip.putNextEntry(e);
                InputStream in = new FileInputStream(f);
                try {
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        zip.write(buf, 0, n);
                        done += n;
                        if (cancelled.get()) throw new CancelledException();
                        if (progress != null) progress.onProgress(done, total[0], name);
                    }
                } finally {
                    in.close();
                }
                zip.closeEntry();
            }
            zip.close(); // writes the central directory and closes the split stream
            ok = true;
            List<File> parts = split.parts();
            if (parts.size() == 1) {
                File single = new File(outDir, baseName + ".zip");
                if (!parts.get(0).equals(single)) {
                    if (single.exists() && !single.delete()) throw new IOException("Cannot replace " + single);
                    if (!parts.get(0).renameTo(single)) throw new IOException("Cannot rename " + parts.get(0));
                    parts = Collections.singletonList(single);
                }
            }
            long out = 0;
            for (File p : parts) out += p.length();
            return new Result(parts, total[0], out, files.size());
        } finally {
            if (!ok) {
                try {
                    split.close();
                } catch (IOException ignored) {
                    // Already failing; cleaning up below.
                }
                for (File p : split.parts()) p.delete();
            }
        }
    }

    private static void collect(File dir, List<File> out, long[] total, @Nullable DirFilter filter) throws IOException {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        Arrays.sort(kids);
        // dir is already canonical (the caller passes a canonical root and only descends into real folders), so the
        // parent is resolved once per folder instead of once per file.
        for (File k : kids) {
            // Skip symbolic links: following them could loop forever or escape the folder.
            if (!k.getCanonicalPath().equals(k.getPath())) continue;
            if (k.isDirectory()) {
                if (filter != null && filter.skip(k)) continue;
                collect(k, out, total, filter);
            } else if (k.isFile()) {
                out.add(k);
                total[0] += k.length();
            }
        }
    }

    private static void deleteExisting(File outDir, String baseName) {
        File[] old = outDir.listFiles();
        if (old == null) return;
        String zip = baseName + ".zip";
        for (File f : old) {
            String n = f.getName();
            if (n.equals(zip) || (n.startsWith(zip + ".") && n.substring(zip.length() + 1).matches("\\d{3,}"))) f.delete();
        }
    }

    /** Writes into numbered part files, starting the next one exactly when the current one is full. */
    private static final class SplitOutputStream extends OutputStream {
        private final File dir;
        private final String name;
        private final long limit;
        private final List<File> parts = new ArrayList<>();
        private OutputStream cur;
        private long curBytes;

        SplitOutputStream(File dir, String name, long limit) {
            this.dir = dir;
            this.name = name;
            this.limit = limit;
        }

        List<File> parts() {
            return new ArrayList<>(parts);
        }

        private void open() throws IOException {
            if (limit > 0 && parts.size() >= MAX_PARTS) {
                throw new IOException("This would need more than " + MAX_PARTS + " parts. Choose a larger part size.");
            }
            File f = new File(dir, limit > 0 ? String.format(java.util.Locale.US, "%s.%03d", name, parts.size() + 1) : name);
            parts.add(f);
            cur = new FileOutputStream(f);
            curBytes = 0;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                if (cur == null) open();
                int n = limit > 0 ? (int) Math.min(len, limit - curBytes) : len;
                cur.write(b, off, n);
                curBytes += n;
                off += n;
                len -= n;
                if (limit > 0 && curBytes >= limit) {
                    cur.close();
                    cur = null; // the next part is opened lazily, so there is never an empty trailing part
                }
            }
        }

        @Override
        public void flush() throws IOException {
            if (cur != null) cur.flush();
        }

        @Override
        public void close() throws IOException {
            if (cur != null) {
                cur.close();
                cur = null;
            }
        }
    }

    /** Level names for the UI. */
    @NonNull
    public static String levelLabel(int level) {
        if (level >= Deflater.BEST_COMPRESSION) return "Maximum";
        if (level <= Deflater.NO_COMPRESSION) return "Store";
        return level <= 1 ? "Fast" : "Normal";
    }
}
