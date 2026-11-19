package fr.freestudio.apphikepont;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal WebSocket server (RFC 6455) on 127.0.0.1, without any library: the channel between the App Hike page in Chrome
 * and the bridge. One client at a time (a new page replaces the previous one). Only pages whose origin is App Hike
 * (apphike.*.workers.dev) or local test pages (localhost) are accepted. Pure Java: no Android dependency.
 */
public class WsServer {
    public interface Handler {
        void onOpen(String origin);
        void onText(String text);
        void onClose();
        void onLog(String s);
    }

    public static final int PORT = 47826;
    final Handler handler;
    ServerSocket server;
    volatile boolean running;
    volatile Client current;
    final ExecutorService writer = Executors.newSingleThreadExecutor();

    public WsServer(Handler h) { handler = h; }

    public static boolean originAllowed(String origin) {
        if (origin == null) return false;
        try {
            URI u = new URI(origin);
            String host = u.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            if ("https".equals(u.getScheme()) && host.matches("apphike\\.[a-z0-9-]+\\.workers\\.dev")) return true;
            return host.equals("localhost") || host.equals("127.0.0.1");
        } catch (Exception e) { return false; }
    }

    public void start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
        running = true;
        Thread t = new Thread(this::acceptLoop, "ws-accept");
        t.setDaemon(true);
        t.start();
        handler.onLog("canal local ouvert sur 127.0.0.1:" + PORT);
    }

    public void stop() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) { }
        Client c = current;
        current = null;
        if (c != null) c.close();
        writer.shutdownNow();
    }

    public boolean connected() { return current != null; }

    void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> serve(s), "ws-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) handler.onLog("canal : " + e.getMessage());
            }
        }
    }

    /** Text message to the connected page (sent on a dedicated thread: no network on the Android main thread). */
    public void send(String text) {
        Client c = current;
        if (c == null) return;
        byte[] p = text.getBytes(StandardCharsets.UTF_8);
        writer.execute(() -> c.write(0x1, p));
    }

    class Client {
        final Socket s;
        final OutputStream out;
        boolean closed;
        Client(Socket s, OutputStream o) { this.s = s; out = o; }

        synchronized void write(int op, byte[] p) {
            if (closed) return;
            try {
                ByteArrayOutputStream f = new ByteArrayOutputStream(p.length + 10);
                f.write(0x80 | op);
                if (p.length < 126) f.write(p.length);
                else if (p.length < 65536) { f.write(126); f.write(p.length >> 8); f.write(p.length & 0xff); }
                else { f.write(127); for (int i = 7; i >= 0; i--) f.write((int) (((long) p.length >> (8 * i)) & 0xff)); }
                f.write(p);
                out.write(f.toByteArray());
                out.flush();
            } catch (IOException e) { close(); }
        }

        void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    static String readHeaders(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, n = 0;
        while ((c = in.read()) != -1) {
            b.write(c);
            n = (c == '\r' || c == '\n') ? n + 1 : 0;
            if (n == 4) break;
            if (b.size() > 16384) throw new IOException("en-tête trop long");
        }
        return b.toString("ISO-8859-1");
    }

    static void readFully(InputStream in, byte[] b) throws IOException {
        int o = 0;
        while (o < b.length) { int r = in.read(b, o, b.length - o); if (r < 0) throw new IOException("fin du flux"); o += r; }
    }

    void serve(Socket s) {
        Client me = null;
        try {
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String req = readHeaders(in);
            Map<String, String> h = new HashMap<>();
            for (String line : req.split("\r\n")) {
                int i = line.indexOf(':');
                if (i > 0) h.put(line.substring(0, i).trim().toLowerCase(Locale.ROOT), line.substring(i + 1).trim());
            }
            String key = h.get("sec-websocket-key"), origin = h.get("origin");
            if (key == null || !"websocket".equalsIgnoreCase(h.get("upgrade"))) {
                out.write("HTTP/1.1 426 Upgrade Required\r\nContent-Type: text/plain\r\nContent-Length: 30\r\n\r\nApp Hike Connexion : active.\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush(); s.close(); return;
            }
            if (!originAllowed(origin)) {
                handler.onLog("canal : connexion refusée depuis « " + origin + " »");
                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush(); s.close(); return;
            }
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.ISO_8859_1)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            me = new Client(s, out);
            Client old = current;
            current = me;
            if (old != null) { handler.onLog("canal : nouvelle page, l'ancienne est fermée"); old.close(); }
            handler.onOpen(origin);

            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            int msgOp = 0;
            byte[] hd = new byte[2];
            while (true) {
                readFully(in, hd);
                boolean fin = (hd[0] & 0x80) != 0;
                int op = hd[0] & 0x0f;
                boolean masked = (hd[1] & 0x80) != 0;
                long len = hd[1] & 0x7f;
                if (len == 126) { byte[] e = new byte[2]; readFully(in, e); len = ((e[0] & 0xff) << 8) | (e[1] & 0xff); }
                else if (len == 127) { byte[] e = new byte[8]; readFully(in, e); len = 0; for (byte x : e) len = (len << 8) | (x & 0xff); }
                if (len > 4_000_000) throw new IOException("message trop long");
                byte[] mk = new byte[4];
                if (masked) readFully(in, mk);
                byte[] p = new byte[(int) len];
                readFully(in, p);
                if (masked) for (int i = 0; i < p.length; i++) p[i] ^= mk[i & 3];
                if (op == 0x8) { me.write(0x8, new byte[0]); break; }
                if (op == 0x9) { me.write(0xA, p); continue; }
                if (op == 0xA) continue;
                if (op == 0x1 || op == 0x2) { msg.reset(); msgOp = op; }
                msg.write(p);
                if (fin) {
                    if (msgOp == 0x1) handler.onText(msg.toString("UTF-8"));
                    msg.reset();
                }
            }
        } catch (Exception e) {
            // closed page, network error: handled below
        } finally {
            if (me != null) {
                me.close();
                if (current == me) { current = null; handler.onClose(); }
            } else {
                try { s.close(); } catch (IOException ignored) { }
            }
        }
    }
}
