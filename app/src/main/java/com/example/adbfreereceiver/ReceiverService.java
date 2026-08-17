package com.example.adbfreereceiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ReceiverService extends Service {
    public static volatile boolean running = false;

    private static final int HTTP_PORT = 8765;
    private static final int DISCOVERY_PORT = 8766;
    private static final String CHANNEL = "receiver";
    private ServerSocket serverSocket;
    private DatagramSocket discoverySocket;
    private ExecutorService pool;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(1001, notification("Waiting for PC..."));
        pool = Executors.newCachedThreadPool();
        running = true;

        pool.execute(this::serverLoop);
        pool.execute(this::discoveryLoop);
    }

    private void serverLoop() {
        try {
            serverSocket = new ServerSocket(HTTP_PORT);
            while (running) {
                Socket s = serverSocket.accept();
                pool.execute(() -> handle(s));
            }
        } catch (Exception e) {
            if (running) Log.e("Receiver", "Server stopped", e);
        }
    }

    private void discoveryLoop() {
        try {
            discoverySocket = new DatagramSocket();
            discoverySocket.setBroadcast(true);

            while (running) {
                String msg = "ADB_FREE_RECEIVER|1|" + HTTP_PORT;
                byte[] data = msg.getBytes(StandardCharsets.UTF_8);
                DatagramPacket p = new DatagramPacket(
                        data, data.length,
                        InetAddress.getByName("255.255.255.255"),
                        DISCOVERY_PORT);
                discoverySocket.send(p);
                Thread.sleep(2000);
            }
        } catch (Exception e) {
            if (running) Log.e("Receiver", "Discovery stopped", e);
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket;
             BufferedInputStream in = new BufferedInputStream(s.getInputStream());
             OutputStream out = new BufferedOutputStream(s.getOutputStream())) {

            String request = readLine(in);
            if (request == null) return;

            String[] parts = request.split(" ");
            if (parts.length < 2) return;

            String method = parts[0];
            String target = parts[1];

            long length = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim();
                    String value = line.substring(colon + 1).trim();
                    if (name.equalsIgnoreCase("Content-Length")) {
                        length = Long.parseLong(value);
                    }
                }
            }

            if (method.equalsIgnoreCase("GET") && target.equals("/ping")) {
                writeResponse(out, 200, "text/plain", "ADB_FREE_RECEIVER_OK\n");
                return;
            }

            if (!method.equalsIgnoreCase("PUT") || !target.startsWith("/upload/")) {
                writeResponse(out, 404, "text/plain", "Not found\n");
                return;
            }

            if (length < 0 || length > 2L * 1024 * 1024 * 1024) {
                writeResponse(out, 413, "text/plain", "Invalid file size\n");
                return;
            }

            String encodedPath = target.substring("/upload/".length());
            String path = URLDecoder.decode(encodedPath, StandardCharsets.UTF_8.name());

            if (path.contains("..") || path.startsWith("/") || path.startsWith("\\")) {
                writeResponse(out, 400, "text/plain", "Invalid path\n");
                return;
            }

            String safe = path.replace('\\', '/');
            String[] pieces = safe.split("/");
            if (pieces.length == 0) {
                writeResponse(out, 400, "text/plain", "Empty filename\n");
                return;
            }

            String filename = pieces[pieces.length - 1];
            StringBuilder parent = new StringBuilder("Shared with PC/");
            for (int i = 0; i < pieces.length - 1; i++) {
                if (!pieces[i].isEmpty()) {
                    parent.append(cleanPart(pieces[i])).append("/");
                }
            }

            String relativePath = "Download/" + parent;
            Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;

            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, cleanPart(filename));
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri uri = getContentResolver().insert(collection, values);
            if (uri == null) {
                writeResponse(out, 500, "text/plain", "Could not create destination\n");
                return;
            }

            boolean success = false;
            try (OutputStream fileOut = getContentResolver().openOutputStream(uri)) {
                if (fileOut == null) throw new IOException("Could not open destination");

                byte[] buffer = new byte[64 * 1024];
                long remaining = length;
                while (remaining > 0) {
                    int want = (int) Math.min(buffer.length, remaining);
                    int n = in.read(buffer, 0, want);
                    if (n < 0) throw new EOFException("Connection ended early");
                    fileOut.write(buffer, 0, n);
                    remaining -= n;
                }
                fileOut.flush();
                success = true;
            } finally {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, success ? 0 : 1);
                getContentResolver().update(uri, done, null, null);
            }

            if (success) {
                writeResponse(out, 200, "text/plain", "OK\n");
                updateNotification("Receiving files on port " + HTTP_PORT);
            } else {
                writeResponse(out, 500, "text/plain", "Transfer failed\n");
            }

        } catch (Exception e) {
            Log.e("Receiver", "Transfer error", e);
        }
    }

    private String cleanPart(String s) {
        return s.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) throw new IOException("Header too large");
        }
        if (c == -1 && b.size() == 0) return null;
        return b.toString(StandardCharsets.ISO_8859_1.name());
    }

    private void writeResponse(OutputStream out, int code, String type, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String reason = code == 200 ? "OK" : code == 400 ? "Bad Request" :
                code == 404 ? "Not Found" : code == 413 ? "Payload Too Large" : "Internal Server Error";

        String h = "HTTP/1.1 " + code + " " + reason + "\r\n" +
                "Content-Type: " + type + "\r\n" +
                "Content-Length: " + data.length + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private Notification notification(String text) {
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("ADB-Free Receiver")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.notify(1001, notification(text));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(
                    CHANNEL, "ADB-Free Receiver", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        try { if (discoverySocket != null) discoverySocket.close(); } catch (Exception ignored) {}
        if (pool != null) pool.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
