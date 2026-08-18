package com.example.adbfreereceiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ReceiverService extends Service {
    public static final int PORT = 8765;
    private static final String TAG = "ADBFreeReceiver";
    private ServerSocket serverSocket;
    public static volatile boolean running;

    private static final String CHANNEL_ID = "adb_free_receiver";
    private static final int NOTIFICATION_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();

        // Android requires a foreground service to call startForeground()
        // shortly after startForegroundService(). Do this BEFORE starting
        // the HTTP server or any other work.
        startForegroundImmediately();

        startServer();
    }

    private void startForegroundImmediately() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "ADB-Free Receiver",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps the file receiver running");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }

        Notification notification;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("ADB-Free Receiver")
                    .setContentText("Receiver running on port " + PORT)
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setOngoing(true)
                    .build();
        } else {
            notification = new Notification.Builder(this)
                    .setContentTitle("ADB-Free Receiver")
                    .setContentText("Receiver running on port " + PORT)
                    .setSmallIcon(android.R.drawable.stat_sys_upload)
                    .setOngoing(true)
                    .build();
        }

        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private File getSharedRoot() {
        return new File(
                Environment.getExternalStorageDirectory(),
                "Shared with PC");
    }

    private File getPushRoot() {
        File root = new File(getSharedRoot(), "Pushed by PC");
        if (!root.exists()) root.mkdirs();
        return root;
    }

    private File getPullRoot() {
        File root = new File(getSharedRoot(), "To Be Pulled by PC");
        if (!root.exists()) root.mkdirs();
        return root;
    }

    private void startServer() {
        running = true;
        Thread t = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                Log.i(TAG, "Receiver running on port " + PORT);
                while (running) {
                    Socket socket = serverSocket.accept();
                    Thread worker = new Thread(() -> handle(socket), "receiver-client");
                    worker.start();
                }
            } catch (IOException e) {
                if (running) Log.e(TAG, "Server stopped", e);
            }
        }, "receiver-server");
        t.start();
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(300000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream());

            byte[] headerBytes = readHeaders(in);
            if (headerBytes == null) return;

            String headerText = new String(headerBytes, StandardCharsets.ISO_8859_1);
            String[] lines = headerText.split("\r\n");
            if (lines.length == 0) return;

            String[] request = lines[0].split(" ");
            if (request.length < 2) {
                sendText(out, 400, "Bad Request");
                return;
            }

            String method = request[0].toUpperCase(Locale.US);
            String target = request[1];

            long contentLength = 0;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    String name = lines[i].substring(0, colon).trim();
                    String value = lines[i].substring(colon + 1).trim();
                    if ("Content-Length".equalsIgnoreCase(name)) {
                        try { contentLength = Long.parseLong(value); } catch (Exception ignored) {}
                    }
                }
            }

            if ("GET".equals(method) && target.equals("/ping")) {
                sendText(out, 200, "ADB_FREE_RECEIVER_OK");
                return;
            }

            if ("GET".equals(method) && target.equals("/list")) {
                sendManifest(out);
                return;
            }

            if ("GET".equals(method) && target.startsWith("/download/")) {
                String encoded = target.substring("/download/".length());
                int q = encoded.indexOf('?');
                if (q >= 0) encoded = encoded.substring(0, q);
                String relative = URLDecoder.decode(encoded, "UTF-8");
                File file = safeFile(getPullRoot(), relative);
                if (file == null || !file.isFile()) {
                    sendText(out, 404, "Not Found");
                    return;
                }
                sendFile(out, file);
                return;
            }

            // Check whether a file already exists in the Android
            // "Pushed by PC" area. This is used by the PC client to provide
            // two-way conflict handling before uploading a file.
            if ("GET".equals(method) && target.startsWith("/exists/")) {
                String encoded = target.substring("/exists/".length());
                int q = encoded.indexOf('?');
                if (q >= 0) encoded = encoded.substring(0, q);

                String relative = URLDecoder.decode(encoded, "UTF-8");
                File file = safeFile(getPushRoot(), relative);

                if (file == null) {
                    sendText(out, 400, "Invalid path");
                    return;
                }

                if (file.isFile()) {
                    sendText(out, 200, "EXISTS|" + file.length());
                } else {
                    sendText(out, 200, "NOT_EXISTS");
                }
                return;
            }

            if ("PUT".equals(method) && target.startsWith("/upload/")) {
                String encoded = target.substring("/upload/".length());
                int q = encoded.indexOf('?');
                if (q >= 0) encoded = encoded.substring(0, q);
                String relative = URLDecoder.decode(encoded, "UTF-8");
                File file = safeFile(getPushRoot(), relative);
                if (file == null) {
                    sendText(out, 400, "Invalid path");
                    return;
                }
                File parent = file.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();

                try (FileOutputStreamCompat fos = new FileOutputStreamCompat(file)) {
                    byte[] buffer = new byte[64 * 1024];
                    long remaining = contentLength;
                    while (remaining > 0) {
                        int want = (int)Math.min(buffer.length, remaining);
                        int n = in.read(buffer, 0, want);
                        if (n < 0) throw new IOException("Unexpected end of upload");
                        fos.write(buffer, 0, n);
                        remaining -= n;
                    }
                    fos.flush();
                }
                sendText(out, 200, "OK");
                return;
            }

            sendText(out, 404, "Not Found");
        } catch (Exception e) {
            Log.e(TAG, "Client error", e);
        }
    }

    private byte[] readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0;
        while (b.size() < 65536) {
            int c = in.read();
            if (c < 0) return null;
            b.write(c);
            if (state == 0 && c == '\r') state = 1;
            else if (state == 1 && c == '\n') state = 2;
            else if (state == 2 && c == '\r') state = 3;
            else if (state == 3 && c == '\n') return b.toByteArray();
            else state = (c == '\r') ? 1 : 0;
        }
        throw new IOException("Headers too large");
    }

    private File safeFile(File baseRoot, String relative) throws IOException {
        if (relative == null) return null;
        relative = relative.replace('\\', '/');
        while (relative.startsWith("/")) relative = relative.substring(1);

        File root = baseRoot.getCanonicalFile();
        File file = new File(root, relative).getCanonicalFile();

        String rootPath = root.getPath();
        if (!file.getPath().equals(rootPath) &&
                !file.getPath().startsWith(rootPath + File.separator)) {
            return null;
        }
        return file;
    }

    private void sendManifest(OutputStream out) throws IOException {
        StringBuilder body = new StringBuilder();
        List<File> files = new ArrayList<>();
        collectFiles(getPullRoot(), files);
        File root = getPullRoot().getCanonicalFile();

        for (File f : files) {
            String rel = root.toURI().relativize(f.getCanonicalFile().toURI()).getPath();
            String encoded = URLEncoder.encode(rel, "UTF-8");
            body.append("FILE|").append(f.length()).append("|").append(encoded).append("\n");
        }

        sendBytes(out, 200, "text/plain; charset=utf-8",
                body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void collectFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File f : children) {
            if (f.isDirectory()) collectFiles(f, out);
            else if (f.isFile()) out.add(f);
        }
    }

    private void sendFile(OutputStream out, File file) throws IOException {
        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: " + file.length() + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.ISO_8859_1));

        try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = fis.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        out.flush();
    }

    private void sendText(OutputStream out, int code, String text) throws IOException {
        sendBytes(out, code, "text/plain; charset=utf-8",
                text.getBytes(StandardCharsets.UTF_8));
    }

    private void sendBytes(OutputStream out, int code, String type, byte[] body) throws IOException {
        String reason = code == 200 ? "OK" : code == 400 ? "Bad Request" :
                code == 404 ? "Not Found" : "Error";
        String header = "HTTP/1.1 " + code + " " + reason + "\r\n" +
                "Content-Type: " + type + "\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (IOException ignored) {}
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private static class FileOutputStreamCompat implements AutoCloseable {
        private final java.io.FileOutputStream out;
        FileOutputStreamCompat(File file) throws IOException {
            out = new java.io.FileOutputStream(file);
        }
        void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
        void flush() throws IOException { out.flush(); }
        @Override public void close() throws IOException { out.close(); }
    }
}
