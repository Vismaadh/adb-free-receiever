package com.example.adbfreereceiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.PendingIntent;
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
import java.io.FileOutputStream;
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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

public class ReceiverService extends Service {
    public static final int PORT = 8765;
    private static final String TAG = "ADBFreeReceiver";
    private ServerSocket serverSocket;
    private final Set<Socket> activeSockets = Collections.synchronizedSet(new HashSet<Socket>());
    public static volatile boolean running;
    private static final String ACTION_STOP = "com.example.adbfreereceiver.STOP_RECEIVER";
    private static final String PREFS = "receiver_state";
    private static final String PREF_STOPPED = "stopped_by_user";

    private static final String CHANNEL_ID = "adb_free_receiver";
    private static final int NOTIFICATION_ID = 1001;

    @Override
    public void onCreate() {
        super.onCreate();

        // If the user explicitly stopped the receiver, do not resurrect it.
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_STOPPED, false)) {
            stopSelf();
            return;
        }

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

        Intent stopIntent = new Intent(this, ReceiverService.class);
        stopIntent.setAction(ACTION_STOP);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 1002, stopIntent, piFlags);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setContentTitle("ADB-Free Receiver")
                .setContentText("Receiver running on port " + PORT)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setOngoing(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            builder.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel, "STOP", stopPendingIntent).build());
        }
        startForeground(NOTIFICATION_ID, builder.build());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_STOPPED, true).apply();
            stopReceiver();
            return START_NOT_STICKY;
        }

        // A normal start explicitly clears the user-stopped state.
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_STOPPED, false).apply();
        return START_NOT_STICKY;
    }

    private void stopReceiver() {
        // First make every server/worker loop observe the stop request.
        running = false;

        ServerSocket ss = serverSocket;
        serverSocket = null;
        try { if (ss != null) ss.close(); } catch (IOException ignored) {}

        // Force every client handler out of blocking read/accept calls.
        synchronized (activeSockets) {
            for (Socket s : activeSockets) {
                try { s.shutdownInput(); } catch (IOException ignored) {}
                try { s.shutdownOutput(); } catch (IOException ignored) {}
                try { s.close(); } catch (IOException ignored) {}
            }
            activeSockets.clear();
        }

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        stopForeground(true);
        stopSelf();
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
        activeSockets.add(socket);
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

            // Browse the virtual Android storage roots and their contents.
            // Empty path returns Internal Storage plus any mounted secondary volumes.
            if ("GET".equals(method) && target.equals("/browse")) {
                sendBrowse(out, "");
                return;
            }

            if ("GET".equals(method) && target.startsWith("/browse?")) {
                String path = getQueryParameter(target, "path");
                sendBrowse(out, path == null ? "" : path);
                return;
            }

            // Arbitrary read-only file access used by the PC Pull Any picker.

            // -----------------------------------------------------------------
            // WebDAV bridge for Windows File Explorer.
            // These endpoints are isolated from the existing transfer endpoints.
            // -----------------------------------------------------------------
            if ("OPTIONS".equals(method)) {
                sendWebDavOptions(out);
                return;
            }

            if ("PROPFIND".equals(method)) {
                handleWebDavPropFind(out, target, lines);
                return;
            }

            if ("GET".equals(method) && isWebDavPath(target)) {
                File file = resolveWebDavFile(target);
                if (file == null || !file.isFile()) { sendStatus(out, 404, "Not Found"); return; }
                sendWebDavFile(out, file, getHeader(lines, "Range"));
                return;
            }

            if ("HEAD".equals(method) && isWebDavPath(target)) {
                File file = resolveWebDavFile(target);
                if (file == null || !file.isFile()) { sendStatus(out, 404, "Not Found"); return; }
                sendHead(out, file);
                return;
            }

            if ("PUT".equals(method) && isWebDavPath(target)) {
                File file = resolveWebDavFile(target);
                if (file == null || file.isDirectory()) { sendStatus(out, 400, "Invalid destination"); return; }

                // Windows WebClient may use Expect: 100-continue for Explorer uploads.
                // Send the interim response before reading the request body.
                String expect = getHeader(lines, "Expect");
                if (expect != null && expect.toLowerCase(Locale.US).contains("100-continue")) {
                    sendContinue(out);
                }

                receiveWebDavPut(out, in, lines, contentLength, file);
                return;
            }

            if ("MKCOL".equals(method) && isWebDavPath(target)) {
                File dir = resolveWebDavFile(target);
                if (dir == null) { sendStatus(out, 400, "Invalid path"); return; }
                if (dir.exists()) { sendStatus(out, 405, "Already exists"); return; }
                if (!dir.mkdirs() && !dir.isDirectory()) { sendStatus(out, 500, "Could not create directory"); return; }
                sendStatus(out, 201, "Created");
                return;
            }

            if ("DELETE".equals(method) && isWebDavPath(target)) {
                File file = resolveWebDavFile(target);
                if (file == null) { sendStatus(out, 400, "Invalid path"); return; }
                if (!file.exists()) { sendStatus(out, 404, "Not Found"); return; }
                if (!deleteRecursively(file)) { sendStatus(out, 500, "Could not delete"); return; }
                sendStatus(out, 204, "No Content");
                return;
            }

            if (("MOVE".equals(method) || "COPY".equals(method)) && isWebDavPath(target)) {
                File source = resolveWebDavFile(target);
                String destination = getHeader(lines, "Destination");
                if (source == null || destination == null) { sendStatus(out, 400, "Invalid path"); return; }
                File dest = resolveWebDavDestination(destination);
                if (dest == null) { sendStatus(out, 400, "Invalid destination"); return; }
                boolean overwrite = "T".equalsIgnoreCase(getHeader(lines, "Overwrite"));
                if (dest.exists()) {
                    if (!overwrite) { sendStatus(out, 412, "Destination exists"); return; }
                    if (!deleteRecursively(dest)) { sendStatus(out, 500, "Could not replace destination"); return; }
                }
                File parent = dest.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                    sendStatus(out, 500, "Could not create destination directory"); return;
                }
                boolean ok;
                if ("MOVE".equals(method)) {
                    ok = source.renameTo(dest);
                } else {
                    ok = copyRecursively(source, dest);
                }
                if (!ok) { sendStatus(out, 500, "Operation failed"); return; }
                sendStatus(out, source.exists() ? 200 : 201, source.exists() ? "OK" : "Created");
                return;
            }

            // Windows Explorer sends PROPPATCH after LOCK/PUT as part of its
            // normal WebDAV file-copy sequence. We do not need to persist the
            // WebDAV properties, but we MUST accept the request and return a
            // proper 207 response. Returning 404 here makes Explorer treat the
            // upload as failed and it subsequently deletes the file and retries.
            if ("PROPPATCH".equals(method) && isWebDavPath(target)) {
                drainRequestBody(in, lines, contentLength);
                sendWebDavPropPatch(out, target);
                return;
            }

            if ("LOCK".equals(method)) {
                sendWebDavLock(out);
                return;
            }
            if ("UNLOCK".equals(method)) {
                sendStatus(out, 204, "No Content");
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

            // Arbitrary destination access for "Push Anywhere to Anywhere".
            // The virtual path is rooted at @internal or @external:<volume>.
            if ("GET".equals(method) && target.startsWith("/dest-exists/")) {
                String encoded = target.substring("/dest-exists/".length());
                int q = encoded.indexOf('?');
                if (q >= 0) encoded = encoded.substring(0, q);
                String virtualPath = URLDecoder.decode(encoded, "UTF-8");
                File file = resolveVirtualFile(virtualPath);
                if (file == null) { sendText(out, 400, "Invalid path"); return; }
                if (file.isFile()) sendText(out, 200, "EXISTS|" + file.length());
                else sendText(out, 200, "NOT_EXISTS");
                return;
            }

            if ("GET".equals(method) && target.startsWith("/dest-upload-status/")) {
                String encoded = target.substring("/dest-upload-status/".length());
                int q = encoded.indexOf('?');
                if (q >= 0) encoded = encoded.substring(0, q);
                String virtualPath = URLDecoder.decode(encoded, "UTF-8");
                File file = resolveVirtualFile(virtualPath);
                if (file == null) { sendText(out, 400, "Invalid path"); return; }
                File part = new File(file.getPath() + ".part");
                StringBuilder status = new StringBuilder();
                if (file.isFile()) status.append("FINAL|").append(file.length()).append("\n");
                if (part.isFile()) status.append("PART|").append(part.length()).append("\n");
                if (status.length() == 0) status.append("NONE\n");
                sendText(out, 200, status.toString());
                return;
            }

            if ("GET".equals(method) && target.startsWith("/dest-hash/")) {
                String encoded = target.substring("/dest-hash/".length());
                int q = encoded.indexOf('?');
                if (q >= 0) encoded = encoded.substring(0, q);
                String virtualPath = URLDecoder.decode(encoded, "UTF-8");
                File file = resolveVirtualFile(virtualPath);
                if (file == null || !file.isFile()) { sendText(out, 404, "Not Found"); return; }
                sendText(out, 200, "SHA256|" + sha256Hex(file));
                return;
            }

            if ("PUT".equals(method) && target.startsWith("/dest-upload/")) {
                String encoded = target.substring("/dest-upload/".length());
                int q = encoded.indexOf('?');
                if (q >= 0) encoded = encoded.substring(0, q);
                String virtualPath = URLDecoder.decode(encoded, "UTF-8");
                File file = resolveVirtualFile(virtualPath);
                if (file == null || file.isDirectory()) { sendText(out, 400, "Invalid destination"); return; }
                File parent = file.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
                    sendText(out, 500, "Could not create destination directory"); return;
                }
                receiveUpload(out, in, lines, contentLength, file);
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
            if (running) Log.e(TAG, "Client error", e);
        } finally {
            activeSockets.remove(socket);
        }
    }

    private void receiveUpload(OutputStream out, InputStream in, String[] lines,
                               long contentLength, File file) throws IOException {
        long startOffset=0L, totalLength=-1L;
        String sh=getHeader(lines,"X-Start-Offset");
        String th=getHeader(lines,"X-Total-Length");
        try { if(sh!=null&&!sh.isEmpty()) startOffset=Long.parseLong(sh); }
        catch(Exception e){sendText(out,400,"Invalid start offset");return;}
        try { if(th!=null&&!th.isEmpty()) totalLength=Long.parseLong(th); }
        catch(Exception e){sendText(out,400,"Invalid total length");return;}
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
        if(newSize < totalLength){ sendText(out,200,"PARTIAL|"+newSize); return; }
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
        applyMetadataFromHeaders(file, lines);
        if(verify) sendText(out,200,"OK|SHA256|"+sha); else sendText(out,200,"OK");
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

    private static String getQueryParameter(String target, String wanted) {
        int q = target.indexOf('?');
        if (q < 0 || q + 1 >= target.length()) return null;
        String query = target.substring(q + 1);
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String name = part.substring(0, eq);
            if (wanted.equals(name)) {
                try { return URLDecoder.decode(part.substring(eq + 1), "UTF-8"); }
                catch (Exception ignored) { return null; }
            }
        }
        return null;
    }

    private List<File> getExternalStorageRoots() {
        List<File> roots = new ArrayList<>();
        File internal = Environment.getExternalStorageDirectory();
        String internalPath = null;
        try { internalPath = internal.getCanonicalPath(); } catch (IOException ignored) {}

        // Discover secondary volumes through the app-specific external-files
        // directories as well as /storage. This catches SD cards on devices
        // where the volume root itself does not report canRead().
        try {
            File[] appDirs = getExternalFilesDirs(null);
            if (appDirs != null) {
                for (File appDir : appDirs) {
                    if (appDir == null) continue;
                    try {
                        String p = appDir.getCanonicalPath().replace('\\', '/');
                        if (!p.startsWith("/storage/")) continue;
                        String rest = p.substring("/storage/".length());
                        int slash = rest.indexOf('/');
                        if (slash <= 0) continue;
                        String volumeName = rest.substring(0, slash);
                        if ("emulated".equalsIgnoreCase(volumeName) ||
                                "self".equalsIgnoreCase(volumeName)) continue;
                        File root = new File("/storage/" + volumeName);
                        String canonical = root.getCanonicalPath();
                        if (internalPath != null && canonical.equals(internalPath)) continue;
                        if (!containsStorageRoot(roots, canonical) && root.isDirectory()) {
                            roots.add(root);
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}

        // Fallback/additional discovery from /storage itself.
        File storage = new File("/storage");
        File[] children = storage.listFiles();
        if (children != null) {
            for (File f : children) {
                try {
                    if (!f.isDirectory()) continue;
                    String path = f.getCanonicalPath();
                    if (internalPath != null && path.equals(internalPath)) continue;
                    if (path.equals("/storage/self") || path.equals("/storage/emulated")) continue;
                    if (internalPath != null && path.startsWith(internalPath + File.separator)) continue;
                    if (!containsStorageRoot(roots, path)) roots.add(f);
                } catch (IOException ignored) {}
            }
        }

        Collections.sort(roots, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return roots;
    }

    private boolean containsStorageRoot(List<File> roots, String canonicalPath) {
        for (File existing : roots) {
            try {
                if (existing.getCanonicalPath().equals(canonicalPath)) return true;
            } catch (IOException ignored) {}
        }
        return false;
    }

    private String storageRootLabel(File root) {
        String name = root.getName();
        if ("emulated".equalsIgnoreCase(name)) return "Internal Storage";
        if (name.matches("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) return "SD Card (" + name + ")";
        return "External Storage (" + name + ")";
    }

    private File resolveVirtualRoot(String token) throws IOException {
        if ("@internal".equals(token)) return Environment.getExternalStorageDirectory().getCanonicalFile();
        if (token != null && token.startsWith("@external:")) {
            String wanted = token.substring("@external:".length());
            for (File root : getExternalStorageRoots()) {
                if (root.getName().equals(wanted)) return root.getCanonicalFile();
            }
        }
        return null;
    }

    private File resolveVirtualFile(String virtualPath) throws IOException {
        if (virtualPath == null || virtualPath.isEmpty()) return null;
        virtualPath = virtualPath.replace('\\', '/');
        while (virtualPath.startsWith("/")) virtualPath = virtualPath.substring(1);

        int slash = virtualPath.indexOf('/');
        String token = slash < 0 ? virtualPath : virtualPath.substring(0, slash);
        String relative = slash < 0 ? "" : virtualPath.substring(slash + 1);
        File root = resolveVirtualRoot(token);
        if (root == null) return null;
        if (relative.isEmpty()) return root;

        File file = new File(root, relative).getCanonicalFile();
        String rootPath = root.getCanonicalPath();
        if (!file.getPath().equals(rootPath) &&
                !file.getPath().startsWith(rootPath + File.separator)) return null;
        return file;
    }

    private void sendBrowse(OutputStream out, String virtualPath) throws IOException {
        if (virtualPath == null) virtualPath = "";
        virtualPath = virtualPath.replace('\\', '/');
        while (virtualPath.startsWith("/")) virtualPath = virtualPath.substring(1);

        StringBuilder body = new StringBuilder();

        // Virtual root: expose each storage volume as a separate selectable root.
        if (virtualPath.isEmpty()) {
            appendBrowseLine(body, "DIR", 0L, "@internal", "Internal Storage");
            for (File root : getExternalStorageRoots()) {
                appendBrowseLine(body, "DIR", 0L,
                        "@external:" + root.getName(), storageRootLabel(root));
            }
            sendBytes(out, 200, "text/plain; charset=utf-8", body.toString().getBytes(StandardCharsets.UTF_8));
            return;
        }

        File dir = resolveVirtualFile(virtualPath);
        if (dir == null || !dir.isDirectory()) {
            sendText(out, 404, "Not Found");
            return;
        }

        File[] children = dir.listFiles();
        if (children == null) {
            sendText(out, 200, "");
            return;
        }

        List<File> sorted = new ArrayList<>();
        Collections.addAll(sorted, children);
        Collections.sort(sorted, (a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });

        for (File child : sorted) {
            if (!child.canRead()) continue;
            String childRelative = virtualPath + "/" + child.getName();
            String type = child.isDirectory() ? "DIR" : "FILE";
            appendBrowseLine(body, type, child.isFile() ? child.length() : 0L,
                    childRelative, child.getName());
        }

        sendBytes(out, 200, "text/plain; charset=utf-8", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void appendBrowseLine(StringBuilder body, String type, long size,
                                  String relative, String name) throws IOException {
        body.append(type).append('|')
                .append(size).append('|')
                .append(URLEncoder.encode(relative, "UTF-8")).append('|')
                .append(URLEncoder.encode(name, "UTF-8"))
                .append('\n');
    }


    private boolean isWebDavPath(String target) {
        return target != null && target.startsWith("/") &&
                !target.startsWith("/browse") && !target.startsWith("/download") &&
                !target.startsWith("/hash") && !target.startsWith("/upload") &&
                !target.startsWith("/exists") && !target.startsWith("/dest-") &&
                !target.equals("/ping") && !target.equals("/info") && !target.equals("/list");
    }

    private File resolveWebDavFile(String target) throws IOException {
        String p = target;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        while (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty()) return null; // root is handled by PROPFIND only
        String decoded = URLDecoder.decode(p, "UTF-8").replace('\\', '/');
        int slash = decoded.indexOf('/');
        String rootName = slash < 0 ? decoded : decoded.substring(0, slash);
        String relative = slash < 0 ? "" : decoded.substring(slash + 1);

        File root = null;
        if ("Internal Storage".equals(rootName)) {
            root = Environment.getExternalStorageDirectory().getCanonicalFile();
        } else {
            for (File r : getExternalStorageRoots()) {
                if (storageRootLabel(r).equals(rootName)) { root = r.getCanonicalFile(); break; }
            }
        }
        if (root == null) return null;
        if (relative.isEmpty()) return root;
        return safeFile(root, relative);
    }

    private File resolveWebDavDestination(String destination) throws IOException {
        if (destination == null) return null;
        try {
            java.net.URI u = new java.net.URI(destination);
            return resolveWebDavFile(u.getRawPath());
        } catch (Exception ignored) {
            int slash = destination.indexOf('/', destination.indexOf("//") + 2);
            return slash >= 0 ? resolveWebDavFile(destination.substring(slash)) : null;
        }
    }

    private void handleWebDavPropFind(OutputStream out, String target, String[] lines) throws IOException {
        int depth = 1;
        String d = getHeader(lines, "Depth");
        if ("0".equals(d)) depth = 0;
        String path = target;
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
           .append("<D:multistatus xmlns:D=\"DAV:\">");

        if (path.equals("/") || path.isEmpty()) {
            appendDavEntry(xml, "/", "Android Storage", true, 0L, System.currentTimeMillis());
            if (depth > 0) {
                appendDavEntry(xml, "/Internal%20Storage/", "Internal Storage", true, 0L,
                        Environment.getExternalStorageDirectory().lastModified());
                for (File r : getExternalStorageRoots()) {
                    String n = encodePathSegment(storageRootLabel(r));
                    appendDavEntry(xml, "/" + n + "/", storageRootLabel(r), true, 0L, r.lastModified());
                }
            }
        } else {
            File dir = resolveWebDavFile(path);
            if (dir == null || !dir.exists()) { sendStatus(out, 404, "Not Found"); return; }
            boolean collection = dir.isDirectory();
            appendDavEntry(xml, normalizedDavHref(path, collection), dir.getName(), collection,
                    collection ? 0L : dir.length(), dir.lastModified());
            if (collection && depth > 0) {
                File[] children = dir.listFiles();
                if (children != null) {
                    Arrays.sort(children, (a,b) -> {
                        if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                        return a.getName().compareToIgnoreCase(b.getName());
                    });
                    for (File child : children) {
                        String childHref = normalizedDavHref(path + "/" + encodePathSegment(child.getName()), child.isDirectory());
                        appendDavEntry(xml, childHref, child.getName(), child.isDirectory(),
                                child.isDirectory() ? 0L : child.length(), child.lastModified());
                    }
                }
            }
        }
        xml.append("</D:multistatus>");
        sendBytes(out, 207, "application/xml; charset=utf-8", xml.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void appendDavEntry(StringBuilder xml, String href, String name, boolean directory,
                                long size, long modified) {
        xml.append("<D:response><D:href>").append(xmlEscape(href)).append("</D:href>")
           .append("<D:propstat><D:prop>")
           .append("<D:displayname>").append(xmlEscape(name)).append("</D:displayname>")
           .append("<D:resourcetype>");
        if (directory) xml.append("<D:collection/>");
        xml.append("</D:resourcetype>")
           .append("<D:getcontentlength>").append(size).append("</D:getcontentlength>")
           .append("<D:getlastmodified>").append(xmlEscape(httpDate(modified))).append("</D:getlastmodified>")
           .append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>");
    }

    private String normalizedDavHref(String path, boolean directory) {
        if (path == null || path.isEmpty()) return "/";
        String p = path.startsWith("/") ? path : "/" + path;
        return directory && !p.endsWith("/") ? p + "/" : p;
    }

    private String encodePathSegment(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return value; }
    }

    private String xmlEscape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private String httpDate(long millis) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        return f.format(new java.util.Date(millis));
    }

    private void receiveWebDavPut(OutputStream out, InputStream in, String[] lines, long contentLength, File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory()) {
            sendStatus(out, 500, "Could not create destination directory"); return;
        }

        boolean chunked = "chunked".equalsIgnoreCase(getHeader(lines, "Transfer-Encoding"));
        File temp = new File(file.getPath() + ".webdav.part");
        try (FileOutputStream fos = new FileOutputStream(temp, false)) {
            byte[] buffer = new byte[64 * 1024];
            if (chunked) {
                readChunkedBody(in, fos, buffer);
            } else {
                long remaining = contentLength;
                while (remaining > 0) {
                    int want = (int)Math.min(buffer.length, remaining);
                    int n = in.read(buffer, 0, want);
                    if (n < 0) throw new IOException("Unexpected end of PUT");
                    fos.write(buffer, 0, n);
                    remaining -= n;
                }
            }
            fos.flush();
        }
        if (file.exists() && !deleteRecursively(file)) throw new IOException("Could not replace destination");
        if (!temp.renameTo(file)) throw new IOException("Could not finalize file");
        applyMetadataFromHeaders(file, lines);
        sendStatus(out, 201, "Created");
    }

    private void readChunkedBody(InputStream in, OutputStream out, byte[] buffer) throws IOException {
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) throw new IOException("Unexpected end of chunked PUT");
            int semi = sizeLine.indexOf(';');
            if (semi >= 0) sizeLine = sizeLine.substring(0, semi);
            long size;
            try { size = Long.parseLong(sizeLine.trim(), 16); }
            catch (NumberFormatException e) { throw new IOException("Invalid chunk size", e); }
            if (size == 0) {
                // Consume trailing headers, if any.
                while (true) {
                    String trailer = readLine(in);
                    if (trailer == null || trailer.isEmpty()) break;
                }
                return;
            }
            long remaining = size;
            while (remaining > 0) {
                int want = (int)Math.min(buffer.length, remaining);
                int n = in.read(buffer, 0, want);
                if (n < 0) throw new IOException("Unexpected end of chunk");
                out.write(buffer, 0, n);
                remaining -= n;
            }
            String crlf = readLine(in);
            if (crlf == null) throw new IOException("Unexpected end of chunk terminator");
        }
    }

    private String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int prev = -1;
        while (b.size() < 8192) {
            int c = in.read();
            if (c < 0) return b.size() == 0 ? null : new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
            if (prev == '\r' && c == '\n') {
                byte[] a = b.toByteArray();
                return new String(a, 0, a.length - 1, StandardCharsets.ISO_8859_1);
            }
            b.write(c);
            prev = c;
        }
        throw new IOException("WebDAV line too long");
    }

    private void sendContinue(OutputStream out) throws IOException {
        out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) if (!deleteRecursively(child)) return false;
        }
        return !file.exists() || file.delete();
    }

    private boolean copyRecursively(File source, File dest) {
        try {
            if (source.isDirectory()) {
                if (!dest.mkdirs() && !dest.isDirectory()) return false;
                File[] children = source.listFiles();
                if (children == null) return true;
                for (File child : children) {
                    if (!copyRecursively(child, new File(dest, child.getName()))) return false;
                }
                return true;
            }
            try (InputStream in = new BufferedInputStream(new FileInputStream(source));
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(dest))) {
                byte[] buffer = new byte[64 * 1024]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
            }
            return true;
        } catch (IOException e) { return false; }
    }

    private void sendWebDavOptions(OutputStream out) throws IOException {
        String h = "HTTP/1.1 200 OK\r\nAllow: OPTIONS, PROPFIND, GET, HEAD, PUT, DELETE, MKCOL, MOVE, COPY, LOCK, UNLOCK\r\nDAV: 1,2\r\nMS-Author-Via: DAV\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1)); out.flush();
    }

    private void drainRequestBody(InputStream in, String[] lines, long contentLength) throws IOException {
        boolean chunked = "chunked".equalsIgnoreCase(getHeader(lines, "Transfer-Encoding"));
        byte[] buffer = new byte[16 * 1024];
        if (chunked) {
            // PROPPATCH bodies from Windows are normally fixed-length, but
            // support chunked requests as well so the connection is clean.
            readChunkedBody(in, new OutputStream() {
                @Override public void write(int b) {}
                @Override public void write(byte[] b, int off, int len) {}
            }, buffer);
            return;
        }
        long remaining = Math.max(0L, contentLength);
        while (remaining > 0) {
            int want = (int)Math.min(buffer.length, remaining);
            int n = in.read(buffer, 0, want);
            if (n < 0) throw new IOException("Unexpected end of request body");
            remaining -= n;
        }
    }

    private void sendWebDavPropPatch(OutputStream out, String target) throws IOException {
        String path = target == null ? "/" : target;
        String href = xmlEscape(normalizedDavHref(path, false));
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<D:multistatus xmlns:D=\"DAV:\">"
                + "<D:response><D:href>" + href + "</D:href>"
                + "<D:propstat><D:prop></D:prop>"
                + "<D:status>HTTP/1.1 200 OK</D:status></D:propstat>"
                + "</D:response></D:multistatus>";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String h = "HTTP/1.1 207 Multi-Status\r\n"
                + "Content-Type: application/xml; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private void sendWebDavLock(OutputStream out) throws IOException {
        String token = "opaquelocktoken:android-file-transfer";
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock><D:locktoken><D:href>" + token + "</D:href></D:locktoken></D:activelock></D:lockdiscovery></D:prop>";
        String h = "HTTP/1.1 200 OK\r\nLock-Token: <"+token+">\r\nContent-Type: application/xml; charset=utf-8\r\nContent-Length: "+body.getBytes(StandardCharsets.UTF_8).length+"\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1)); out.write(body.getBytes(StandardCharsets.UTF_8)); out.flush();
    }

    private void sendStatus(OutputStream out, int code, String reason) throws IOException {
        String h = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1)); out.flush();
    }

    private void sendHead(OutputStream out, File file) throws IOException {
        String h = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + file.length() + "\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1)); out.flush();
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
        long modified = file.lastModified();
        long created = 0L;
        long accessed = 0L;
        try {
            BasicFileAttributes a = Files.readAttributes(file.toPath(), BasicFileAttributes.class);
            modified = a.lastModifiedTime().toMillis();
            created = a.creationTime().toMillis();
            accessed = a.lastAccessTime().toMillis();
        } catch (Exception ignored) {
            // Basic metadata is not available on every Android filesystem.
            // At minimum, continue serving the file with its modification time.
        }
        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: " + file.length() + "\r\n" +
                "X-File-Creation-Time: " + created + "\r\n" +
                "X-File-Last-Modified-Time: " + modified + "\r\n" +
                "X-File-Last-Access-Time: " + accessed + "\r\n" +
                "Connection: close\r\n\r\n";
        out.write(header.getBytes(StandardCharsets.ISO_8859_1));
        try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = fis.read(buffer)) != -1) out.write(buffer, 0, n);
        }
        out.flush();
    }

    /**
     * WebDAV file response with optional HTTP byte-range support.
     *
     * This is intentionally used ONLY by the WebDAV GET path. The existing
     * transfer endpoints continue to use sendFile() unchanged.
     */
    private void sendWebDavFile(OutputStream out, File file, String rangeHeader) throws IOException {
        long length = file.length();

        // No Range header: preserve the existing full-file WebDAV behavior.
        if (rangeHeader == null || rangeHeader.trim().isEmpty()) {
            sendFile(out, file);
            return;
        }

        String range = rangeHeader.trim();
        if (!range.startsWith("bytes=")) {
            sendStatus(out, 416, "Range Not Satisfiable");
            return;
        }

        String spec = range.substring("bytes=".length()).trim();

        // Handle a single byte range. Explorer/media players normally request
        // one range; multiple ranges are deliberately not generated here.
        int comma = spec.indexOf(',');
        if (comma >= 0) spec = spec.substring(0, comma).trim();

        long start;
        long end;

        try {
            int dash = spec.indexOf('-');
            if (dash < 0) {
                sendStatus(out, 416, "Range Not Satisfiable");
                return;
            }

            String left = spec.substring(0, dash).trim();
            String right = spec.substring(dash + 1).trim();

            if (left.isEmpty()) {
                // Suffix range: bytes=-N
                long suffix = Long.parseLong(right);
                if (suffix <= 0 || length <= 0) {
                    sendStatus(out, 416, "Range Not Satisfiable");
                    return;
                }
                if (suffix > length) suffix = length;
                start = length - suffix;
                end = length - 1;
            } else {
                start = Long.parseLong(left);
                if (start < 0 || start >= length) {
                    sendStatus(out, 416, "Range Not Satisfiable");
                    return;
                }

                if (right.isEmpty()) {
                    end = length - 1;
                } else {
                    end = Long.parseLong(right);
                    if (end < start) {
                        sendStatus(out, 416, "Range Not Satisfiable");
                        return;
                    }
                    if (end >= length) end = length - 1;
                }
            }
        } catch (NumberFormatException e) {
            sendStatus(out, 416, "Range Not Satisfiable");
            return;
        }

        long contentLength = end - start + 1;

        String header =
                "HTTP/1.1 206 Partial Content\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Length: " + contentLength + "\r\n" +
                "Content-Range: bytes " + start + "-" + end + "/" + length + "\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Connection: close\r\n\r\n";

        out.write(header.getBytes(StandardCharsets.ISO_8859_1));

        try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
            long skipped = 0;
            while (skipped < start) {
                long n = fis.skip(start - skipped);
                if (n <= 0) {
                    if (fis.read() == -1) throw new IOException("Could not seek to requested range");
                    n = 1;
                }
                skipped += n;
            }

            byte[] buffer = new byte[64 * 1024];
            long remaining = contentLength;
            while (remaining > 0) {
                int want = (int) Math.min(buffer.length, remaining);
                int n = fis.read(buffer, 0, want);
                if (n < 0) throw new IOException("Unexpected end of file");
                out.write(buffer, 0, n);
                remaining -= n;
            }
        }

        out.flush();
    }

    private void applyMetadataFromHeaders(File file, String[] lines) {
        try {
            long modified = parseLongHeader(lines, "X-File-Last-Modified-Time");
            if (modified >= 0) file.setLastModified(modified);
        } catch (Exception ignored) {}
        try {
            long created = parseLongHeader(lines, "X-File-Creation-Time");
            long accessed = parseLongHeader(lines, "X-File-Last-Access-Time");
            Path path = file.toPath();
            if (created >= 0) Files.setAttribute(path, "basic:creationTime", FileTime.fromMillis(created));
            if (accessed >= 0) Files.setAttribute(path, "basic:lastAccessTime", FileTime.fromMillis(accessed));
        } catch (Exception ignored) {}
    }

    private long parseLongHeader(String[] lines, String name) {
        String v = getHeader(lines, name);
        if (v == null || v.trim().isEmpty()) return -1L;
        try { return Long.parseLong(v.trim()); } catch (Exception e) { return -1L; }
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
        ServerSocket ss = serverSocket;
        serverSocket = null;
        try { if (ss != null) ss.close(); } catch (IOException ignored) {}
        synchronized (activeSockets) {
            for (Socket s : activeSockets) {
                try { s.close(); } catch (IOException ignored) {}
            }
            activeSockets.clear();
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFICATION_ID);
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
