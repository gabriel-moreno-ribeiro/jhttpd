package jhttpd;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * A multi-threaded HTTP/1.1 server built directly on sockets: an accept loop
 * hands each connection to a worker thread, which serves requests on it until
 * the client closes, asks for Connection: close, or stays idle too long.
 */
public final class Server {
    private final Handler handler;
    private final int port;
    private final int idleTimeoutMs;
    private final ExecutorService workers;
    private ServerSocket socket;
    private Thread acceptThread;
    private volatile boolean running;
    private Consumer<String> log = System.out::println;

    public Server(int port, Handler handler) {
        this(port, handler, 64, 15_000);
    }

    public Server(int port, Handler handler, int threads, int idleTimeoutMs) {
        this.port = port;
        this.handler = handler;
        this.idleTimeoutMs = idleTimeoutMs;
        this.workers = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "jhttpd-worker");
            t.setDaemon(true);
            return t;
        });
    }

    public Server logger(Consumer<String> log) {
        this.log = log;
        return this;
    }

    /** Port actually bound (useful when constructed with port 0). */
    public int port() {
        return socket == null ? port : socket.getLocalPort();
    }

    public synchronized void start() throws IOException {
        socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(port));
        running = true;
        acceptThread = new Thread(this::acceptLoop, "jhttpd-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        workers.shutdownNow();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Blocks the calling thread until the server is stopped. */
    public void join() throws InterruptedException {
        if (acceptThread != null) acceptThread.join();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = socket.accept();
                client.setTcpNoDelay(true);
                client.setSoTimeout(idleTimeoutMs);
                workers.execute(() -> serve(client));
            } catch (SocketException e) {
                if (running) log.accept("accept error: " + e.getMessage());
            } catch (IOException e) {
                log.accept("accept error: " + e.getMessage());
            }
        }
    }

    private void serve(Socket client) {
        String peer = client.getRemoteSocketAddress().toString();
        try (client;
             InputStream in = new BufferedInputStream(client.getInputStream());
             OutputStream out = new BufferedOutputStream(client.getOutputStream())) {
            boolean keepAlive = true;
            while (keepAlive && running) {
                Request req;
                try {
                    req = Request.parse(in);
                } catch (SocketTimeoutException e) {
                    break; // idle keep-alive connection
                } catch (HttpException e) {
                    Response res = new Response().status(e.status).text(e.status + " " + Response.reason(e.status) + ": " + e.getMessage());
                    res.write(out, false, false);
                    log.accept(peer + " - " + e.status + " (" + e.getMessage() + ")");
                    break;
                }
                if (req == null) break; // client closed
                keepAlive = req.keepAlive();
                Response res = dispatch(req);
                res.headOnly(req.method.equals("HEAD"));
                String enc = req.header("accept-encoding");
                boolean gzip = enc != null && enc.toLowerCase(Locale.ROOT).contains("gzip");
                res.write(out, keepAlive, gzip);
                log.accept(peer + " " + req.method + " " + req.target + " " + res.status());
            }
        } catch (IOException e) {
            // client went away; nothing to do
        }
    }

    private Response dispatch(Request req) {
        Response res = new Response();
        try {
            handler.handle(req, res);
        } catch (HttpException e) {
            res = new Response().status(e.status).text(e.status + " " + Response.reason(e.status) + ": " + e.getMessage());
        } catch (Exception e) {
            log.accept("handler error: " + e);
            res = new Response().status(500).text("500 Internal Server Error");
        }
        return res;
    }
}
