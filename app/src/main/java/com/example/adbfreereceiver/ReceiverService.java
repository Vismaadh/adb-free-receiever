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
import java.security.MessageDigest;
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
            s.setSoTimeout(60000); // inactivity timeout: reset by every successful socket read
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

            if ("GET".equals(method) && target.equals("/info")) {
                String model = Build.MANUFACTURER + " " + Build.MODEL;
                sendText(out, 200, model);
                return;
            }

            if ("GET".equals(method) && target.equals("/ping")) {
                sendText(out, 200, "ADB_FREE_RECEIVER_OK");
                return;
            }

            if ("GET".equals(method) && target.equals("/list")) {
                sendManifest(out);
                return;
            }

            // Browse the phone storage from the PC. The empty path is a virtual
            // root containing Internal Storage plus any mounted secondary
            // volumes (SD card / USB OTG / other removable storage).
            if ("GET".equals(method) && target.equals("/browse")) {
                sendBrowse(out, "");
                return;
            }

            if ("GET".equals(method) && target.startsWith("/browse?")) {
                String path = getQueryParam(target, "path");
                sendBrowse(out, path == null ? "" : path);
                return;
            }

            if ("GET".equals(method) && target.startsWith("/download-any/")) {
                String encoded = target.substring("/download-any/".length());
                int q = encoded.indexOf('?');
                if (q >= 0) encoded = encoded.substring(0, q);
                String virtualPath = URLDecoder.decode(encoded, "UTF-8");
                File file = resolveVirtualFile(virtualPath);
                if (file == null || !file.isFile()) {
                    sendText(out, 404, "Not Found");
                    return;
                }
                sendFile(out, file);
                return;
            }

            if ("GET".equals(method) && target.startsWith("/hash-any/")) {
                String encoded = target.substring("/hash-any/".length());
                int q = encoded.indexOf('?');
                if (q >= 0) encoded = encoded.substring(0, q);
                String virtualPath = URLDecoder.decode(encoded, "UTF-8");
                File file = resolveVirtualFile(virtualPath);
                if (file == null || !file.isFile()) {
                    sendText(out, 404, "Not Found");
                    return;
                }
                sendText(out, 200, "SHA256|" + sha256Hex(file));
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

            if ("GET".equals(method) && target.startsWith("/hash/")) {
                String encoded = target.substring("/hash/".length());
                int q = encoded.indexOf('?');
                if (q >= 0) encoded = encoded.substring(0, q);
                String relative = URLDecoder.decode(encoded, "UTF-8");
                File file = safeFile(getPullRoot(), relative);
                if (file == null || !file.isFile()) {
                    sendText(out, 404, "Not Found");
                    return;
                }
                sendText(out, 200, "SHA256|" + sha256Hex(file));
                return;
            }

            if ("GET".equals(method) && target.startsWith("/upload-status/")) {
                String encoded = target.substring("/upload-status/".length());
                int q = encoded.indexOf('?');
                if (q >= 0) encoded = encoded.substring(0, q);

                String relative = URLDecoder.decode(encoded, "UTF-8");
                File file = safeFile(getPushRoot(), relative);
                if (file == null) {
                    sendText(out, 400, "Invalid path");
                    return;
                }

                File part = new File(file.getPath() + ".part");
                StringBuilder status = new StringBuilder();

                if (file.isFile()) {
                    status.append("FINAL|").append(file.length()).append("\n");
                }
                if (part.isFile()) {
                    status.append("PART|").append(part.length()).append("\n");
                }

                if (status.length() == 0) status.append("NONE\n");

                sendText(out, 200, status.toString());
                return;
            }

            if ("PUT".equals(method) && target.startsWith("/upload/")) {
                String encoded = target.substring("/upload/".length());
                int q = encoded.indexOf('?');
                if (q >= 0) encoded = encoded.substring(0, q);
                String relative = URLDecoder.decode(encoded, "UTF-8");
                File file = safeFile(getPushRoot(), relative);
                if (file == null) { sendText(out, 400, "Invalid path"); return; }
                File parent=file.getParentFile(); if(parent!=null&&!parent.exists()) parent.mkdirs();

                long startOffset=0L, totalLength=-1L;
                String sh=getHeader(lines,"X-Start-Offset");
                String th=getHeader(lines,"X-Total-Length");
                try { if(sh!=null&&!sh.isEmpty()) startOffset=Long.parseLong(sh); } catch(Exception e){sendText(out,400,"Invalid start offset");return;}
                try { if(th!=null&&!th.isEmpty()) totalLength=Long.parseLong(th); } catch(Exception e){sendText(out,400,"Invalid total length");return;}
                if(startOffset<0 || totalLength<0 || startOffset>totalLength){sendText(out,400,"Invalid transfer range");return;}

                File tempFile=new File(file.getPath()+".part");
                long existingPart=tempFile.isFile()?tempFile.length():0L;
                if(startOffset==0L){
                    if(tempFile.exists() && !tempFile.delete()){sendText(out,500,"Could not reset partial file");return;}
                    existingPart=0L;
                } else if(existingPart!=startOffset){
                    sendText(out,409,"PART_OFFSET_MISMATCH|"+existingPart); return;
                }

                try(FileOutputStreamCompat fos=new FileOutputStreamCompat(tempFile,startOffset>0)){
                    byte[] buffer=new byte[64*1024]; long remaining=contentLength;
                    while(remaining>0){
                        int want=(int)Math.min(buffer.length,remaining);
                        int n=in.read(buffer,0,want);
                        if(n<0) throw new IOException("Unexpected end of upload");
                        fos.write(buffer,0,n); remaining-=n;
                    }
                    fos.flush();
                }

                long newSize=tempFile.length();
                // IMPORTANT: an interrupted request must never become the final file.
                if(newSize < totalLength){
                    sendText(out,200,"PARTIAL|"+newSize);
                    return;
                }
                if(newSize > totalLength){ throw new IOException("Received more bytes than expected"); }

                boolean verify="true".equalsIgnoreCase(getHeader(lines,"X-Verify-SHA256"));
                String sha=null;
                if(verify){
                    sha=sha256Hex(tempFile);
                    String expected=getHeader(lines,"X-Expected-SHA256");
                    if(expected!=null&&!expected.trim().isEmpty()&&!sha.equalsIgnoreCase(expected.trim())){
                        tempFile.delete();
                        sendText(out,409,"SHA256_MISMATCH|"+sha);
                        return;
                    }
                }

                if(file.exists() && !file.delete()) throw new IOException("Could not replace existing destination");
                if(!tempFile.renameTo(file)) throw new IOException("Could not finalize uploaded file");
                if(verify) sendText(out,200,"OK|SHA256|"+sha); else sendText(out,200,"OK");
                return;
            }

            sendText(out, 404, "Not Found");
        } catch (Exception e) {
            Log.e(TAG, "Client error", e);
        }
    }

    /** Returns the primary shared storage root used by the existing PUSH/PULL paths. */
    private File getPrimaryStorageRoot() {
        return Environment.getExternalStorageDirectory();
    }

    /**
     * Find mounted secondary storage directories. Android normally exposes an
     * inserted SD card or USB storage under /storage/<volume-id>.
     * The primary emulated volume and the /storage/self aliases are excluded.
     */
    private List<File> getSecondaryStorageRoots() {
        List<File> roots = new ArrayList<>();
        File storage = new File("/storage");
        File primary;
        try { primary = getPrimaryStorageRoot().getCanonicalFile(); }
        catch (IOException e) { primary = getPrimaryStorageRoot(); }

        File[] children = storage.listFiles();
        if (children == null) return roots;

        for (File child : children) {
            if (!child.isDirectory()) continue;
            String name = child.getName();
            if ("emulated".equalsIgnoreCase(name) || "self".equalsIgnoreCase(name)) continue;
            try {
                File canonical = child.getCanonicalFile();
                if (canonical.equals(primary)) continue;
                if (canonical.canRead()) roots.add(canonical);
            } catch (IOException ignored) {}
        }

        return roots;
    }

    /**
     * Virtual paths used by the PC picker:
     *   @internal/<relative path>
     *   @external:<index>/<relative path>
     */
    private File resolveVirtualFile(String virtualPath) throws IOException {
        if (virtualPath == null) return null;
        virtualPath = virtualPath.replace('\\', '/');
        while (virtualPath.startsWith("/")) virtualPath = virtualPath.substring(1);

        if ("@internal".equals(virtualPath) || virtualPath.startsWith("@internal/")) {
            String relative = "@internal".equals(virtualPath)
                    ? "" : virtualPath.substring("@internal/".length());
            return safeFile(getPrimaryStorageRoot(), relative);
        }

        if (virtualPath.startsWith("@external:")) {
            int slash = virtualPath.indexOf('/');
            String idText = slash < 0
                    ? virtualPath.substring("@external:".length())
                    : virtualPath.substring("@external:".length(), slash);
            int index;
            try { index = Integer.parseInt(idText); }
            catch (NumberFormatException e) { return null; }

            List<File> roots = getSecondaryStorageRoots();
            if (index < 0 || index >= roots.size()) return null;
            String relative = slash < 0 ? "" : virtualPath.substring(slash + 1);
            return safeFile(roots.get(index), relative);
        }

        return null;
    }

    private String getQueryParam(String target, String wanted) throws IOException {
        int q = target.indexOf('?');
        if (q < 0 || q + 1 >= target.length()) return null;
        String query = target.substring(q + 1);
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String name = URLDecoder.decode(part.substring(0, eq), "UTF-8");
            if (wanted.equals(name)) {
                return URLDecoder.decode(part.substring(eq + 1), "UTF-8");
            }
        }
        return null;
    }

    private String displayNameForStorage(File root, int index) {
        String name = root.getName();
        if (name == null || name.isEmpty()) name = "External Storage " + (index + 1);
        return "SD Card (" + name + ")";
    }

    private void sendBrowse(OutputStream out, String virtualPath) throws IOException {
        String path = virtualPath == null ? "" : virtualPath;
        path = path.replace('\\', '/');
        while (path.startsWith("/")) path = path.substring(1);

        StringBuilder body = new StringBuilder();

        // Virtual top level: expose the storage roots themselves.
        if (path.isEmpty()) {
            appendBrowseDirectory(body, "@internal", "Internal Storage");
            List<File> external = getSecondaryStorageRoots();
            for (int i = 0; i < external.size(); i++) {
                appendBrowseDirectory(body, "@external:" + i,
                        displayNameForStorage(external.get(i), i));
            }
            sendBytes(out, 200, "text/plain; charset=utf-8",
                    body.toString().getBytes(StandardCharsets.UTF_8));
            return;
        }

        File dir = resolveVirtualFile(path);
        if (dir == null || !dir.isDirectory()) {
            sendText(out, 404, "Not Found");
            return;
        }

        File[] children = dir.listFiles();
        if (children == null) {
            sendText(out, 403, "Storage not readable");
            return;
        }

        for (File child : children) {
            if (!child.isDirectory() && !child.isFile()) continue;
            String childName = child.getName();
            String childVirtual = path.endsWith("/") ? path + childName : path + "/" + childName;
            // Relative field is deliberately encoded once. The PC decodes it.
            String type = child.isDirectory() ? "DIR" : "FILE";
            body.append(type).append('|')
                    .append(child.isFile() ? child.length() : 0L).append('|')
                    .append(URLEncoder.encode(childVirtual, "UTF-8")).append('|')
                    .append(URLEncoder.encode(childName, "UTF-8")).append('\n');
        }

        sendBytes(out, 200, "text/plain; charset=utf-8",
                body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void appendBrowseDirectory(StringBuilder body, String virtualPath, String name) throws IOException {
        body.append("DIR|0|")
                .append(URLEncoder.encode(virtualPath, "UTF-8"))
                .append('|')
                .append(URLEncoder.encode(name, "UTF-8"))
                .append('\n');
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

    private static String getHeader(String[] lines, String wanted) {
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon > 0 && wanted.equalsIgnoreCase(line.substring(0, colon).trim())) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static MessageDigest newSha256() throws IOException {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception e) { throw new IOException("SHA-256 unavailable", e); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    private static String sha256Hex(File file) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = fis.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
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
            this(file, false);
        }
        FileOutputStreamCompat(File file, boolean append) throws IOException {
            out = new java.io.FileOutputStream(file, append);
        }
        void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
        void flush() throws IOException { out.flush(); }
        @Override public void close() throws IOException { out.close(); }
    }
}
