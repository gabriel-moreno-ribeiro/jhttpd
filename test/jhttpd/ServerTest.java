package jhttpd;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * End-to-end tests: start the server in-process on a random port, then talk
 * to it with java.net.http.HttpClient and with raw sockets. Run with:
 *   java -cp out jhttpd.ServerTest
 */
public final class ServerTest {
    private static int passed;
    private static final List<String> failures = new ArrayList<>();
    private static String base;
    private static int port;
    private static final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    static void check(String name, boolean ok) {
        if (ok) passed++;
        else failures.add(name);
    }

    static void eq(String name, Object got, Object want) {
        boolean ok = got == null ? want == null : got.equals(want);
        if (!ok) failures.add(name + " (got " + got + ", want " + want + ")");
        else passed++;
    }

    static HttpResponse<byte[]> send(HttpRequest req) throws Exception {
        return client.send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    static HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create(base + path));
    }

    static String text(HttpResponse<byte[]> r) {
        return new String(r.body(), StandardCharsets.UTF_8);
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("jhttpd");
        Files.writeString(root.resolve("index.html"), "<h1>home</h1>");
        Files.writeString(root.resolve("hello.txt"), "hello world");
        Files.writeString(root.resolve("data.json"), "{\"a\":1}");
        Files.createDirectories(root.resolve("sub dir"));
        Files.writeString(root.resolve("sub dir").resolve("page.html"), "<p>nested</p>");
        Files.createDirectories(root.resolve("listing"));
        Files.writeString(root.resolve("listing").resolve("b.txt"), "b");
        Files.writeString(root.resolve("listing").resolve("a.txt"), "a");
        byte[] big = new byte[300_000];
        for (int i = 0; i < big.length; i++) big[i] = (byte) ('a' + i % 26);
        Files.write(root.resolve("big.bin"), big);
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 2000; i++) longText.append("line ").append(i).append('\n');
        Files.writeString(root.resolve("long.txt"), longText.toString());

        Router router = new Router()
                .get("/api/hello", (rq, rs) -> rs.json("{\"hello\":\"" + rq.query.getOrDefault("name", "world") + "\"}"))
                .get("/api/users/:id/posts/:post", (rq, rs) -> rs.text(rq.params.get("id") + "/" + rq.params.get("post")))
                .get("/api/files/*", (rq, rs) -> rs.text("wild:" + rq.params.get("*")))
                .post("/api/echo", (rq, rs) -> rs.body(rq.body, rq.header("content-type")))
                .get("/api/boom", (rq, rs) -> { throw new IllegalStateException("kaboom"); })
                .get("/api/stream", (rq, rs) -> rs.stream(new ByteArrayInputStream("streamed".getBytes()), 8, "text/plain"))
                .fallback(new StaticHandler(root, true));
        List<String> log = new ArrayList<>();
        Server server = new Server(0, router, 8, 2000).logger(log::add);
        server.start();
        port = server.port();
        base = "http://localhost:" + port;

        try {
            testStaticFiles();
            testRoutes();
            testConditionalAndRange();
            testGzip();
            testRawProtocol();
            testConcurrency();
        } finally {
            server.stop();
        }

        check("access log written", log.stream().anyMatch(l -> l.contains("GET /hello.txt 200")));
        System.out.printf("%d passed, %d failed%n", passed, failures.size());
        for (String f : failures) System.out.println("FAIL: " + f);
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    static void testStaticFiles() throws Exception {
        HttpResponse<byte[]> r = send(req("/hello.txt").build());
        eq("static file status", r.statusCode(), 200);
        eq("static file body", text(r), "hello world");
        eq("static file type", r.headers().firstValue("content-type").orElse(""), "text/plain; charset=utf-8");
        eq("content length", r.headers().firstValue("content-length").orElse(""), "11");
        check("has date header", r.headers().firstValue("date").isPresent());
        check("has etag", r.headers().firstValue("etag").isPresent());

        r = send(req("/").build());
        eq("index.html served for /", text(r), "<h1>home</h1>");
        eq("html type", r.headers().firstValue("content-type").orElse(""), "text/html; charset=utf-8");

        r = send(req("/data.json").build());
        eq("json type", r.headers().firstValue("content-type").orElse(""), "application/json");

        r = send(req("/sub%20dir/page.html").build());
        eq("percent-decoded path", text(r), "<p>nested</p>");

        r = send(req("/missing.txt").build());
        eq("404 status", r.statusCode(), 404);

        r = send(req("/listing/").build());
        eq("directory listing status", r.statusCode(), 200);
        check("directory listing content", text(r).contains("a.txt") && text(r).contains("b.txt") && text(r).indexOf("a.txt") < text(r).indexOf("b.txt"));

        r = send(req("/listing").build());
        eq("directory redirect", r.statusCode(), 301);
        eq("directory redirect location", r.headers().firstValue("location").orElse(""), "/listing/");

        r = send(req("/hello.txt").method("HEAD", HttpRequest.BodyPublishers.noBody()).build());
        eq("HEAD status", r.statusCode(), 200);
        eq("HEAD content-length", r.headers().firstValue("content-length").orElse(""), "11");
        eq("HEAD empty body", r.body().length, 0);

        r = send(req("/hello.txt").method("DELETE", HttpRequest.BodyPublishers.noBody()).build());
        eq("static DELETE not allowed", r.statusCode(), 405);

        r = send(req("/big.bin").build());
        eq("large file length", r.body().length, 300_000);
        eq("large file content", r.body()[299_999], (byte) ('a' + 299_999 % 26));
    }

    static void testRoutes() throws Exception {
        HttpResponse<byte[]> r = send(req("/api/hello").build());
        eq("route json", text(r), "{\"hello\":\"world\"}");
        r = send(req("/api/hello?name=ana%20maria&x=1").build());
        eq("query params decoded", text(r), "{\"hello\":\"ana maria\"}");
        r = send(req("/api/users/42/posts/7").build());
        eq("path params", text(r), "42/7");
        r = send(req("/api/files/a/b/c.txt").build());
        eq("wildcard route", text(r), "wild:a/b/c.txt");
        r = send(req("/api/echo").POST(HttpRequest.BodyPublishers.ofString("ping pong")).header("Content-Type", "text/x-custom").build());
        eq("post body echoed", text(r), "ping pong");
        eq("post content type echoed", r.headers().firstValue("content-type").orElse(""), "text/x-custom");
        r = send(req("/api/echo").build());
        eq("wrong method on route", r.statusCode(), 405);
        r = send(req("/api/boom").build());
        eq("handler exception is a 500", r.statusCode(), 500);
        r = send(req("/api/stream").build());
        eq("streamed body", text(r), "streamed");
        r = send(req("/api/hello").method("HEAD", HttpRequest.BodyPublishers.noBody()).build());
        eq("HEAD on route uses GET handler", r.statusCode(), 200);
        eq("HEAD on route has no body", r.body().length, 0);
    }

    static void testConditionalAndRange() throws Exception {
        HttpResponse<byte[]> first = send(req("/hello.txt").build());
        String etag = first.headers().firstValue("etag").orElseThrow();
        String lastModified = first.headers().firstValue("last-modified").orElseThrow();

        HttpResponse<byte[]> r = send(req("/hello.txt").header("If-None-Match", etag).build());
        eq("etag match gives 304", r.statusCode(), 304);
        eq("304 has no body", r.body().length, 0);

        r = send(req("/hello.txt").header("If-Modified-Since", lastModified).build());
        eq("if-modified-since gives 304", r.statusCode(), 304);

        String old = ZonedDateTime.now(ZoneOffset.UTC).minusYears(1).format(Response.HTTP_DATE);
        r = send(req("/hello.txt").header("If-Modified-Since", old).build());
        eq("old if-modified-since gives 200", r.statusCode(), 200);

        r = send(req("/hello.txt").header("Range", "bytes=0-4").build());
        eq("range status", r.statusCode(), 206);
        eq("range body", text(r), "hello");
        eq("content-range", r.headers().firstValue("content-range").orElse(""), "bytes 0-4/11");

        r = send(req("/hello.txt").header("Range", "bytes=6-").build());
        eq("open range body", text(r), "world");
        r = send(req("/hello.txt").header("Range", "bytes=-5").build());
        eq("suffix range body", text(r), "world");
        r = send(req("/hello.txt").header("Range", "bytes=50-60").build());
        eq("unsatisfiable range", r.statusCode(), 416);
        r = send(req("/big.bin").header("Range", "bytes=100000-100025").build());
        eq("range in large file", text(r), "efghijklmnopqrstuvwxyzabcd"); // byte 100000 is 'a' + 100000 % 26 = 'e'
        eq("range in large file length", r.body().length, 26);
    }

    static void testGzip() throws Exception {
        HttpResponse<byte[]> r = send(req("/long.txt").header("Accept-Encoding", "gzip").build());
        eq("gzip status", r.statusCode(), 200);
        eq("gzip encoding header", r.headers().firstValue("content-encoding").orElse(""), "");
        // streamed files are not compressed (no whole-body in memory); routes are
        r = send(req("/api/hello?name=" + "x".repeat(400)).header("Accept-Encoding", "gzip, deflate").build());
        eq("route gzip encoding", r.headers().firstValue("content-encoding").orElse(""), "gzip");
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(r.body()))) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            eq("gzip body decompresses", body, "{\"hello\":\"" + "x".repeat(400) + "\"}");
        }
        r = send(req("/api/hello?name=" + "x".repeat(400)).build());
        eq("no gzip without accept-encoding", r.headers().firstValue("content-encoding").orElse(""), "");
    }

    /** Talks HTTP by hand to test keep-alive, HTTP/1.0, chunked bodies and errors. */
    static void testRawProtocol() throws Exception {
        try (Socket s = new Socket("localhost", port)) {
            OutputStream out = s.getOutputStream();
            InputStream in = s.getInputStream();
            out.write(("GET /hello.txt HTTP/1.1\r\nHost: localhost\r\n\r\n" +
                       "GET /data.json HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes());
            out.flush();
            String first = readResponse(in);
            String second = readResponse(in);
            check("keep-alive first response", first.startsWith("HTTP/1.1 200") && first.endsWith("hello world"));
            check("keep-alive second response on same socket", second.startsWith("HTTP/1.1 200") && second.endsWith("{\"a\":1}"));
            check("keep-alive header", first.contains("Connection: keep-alive"));

            out.write("POST /api/echo HTTP/1.1\r\nHost: localhost\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n".getBytes());
            out.flush();
            String chunked = readResponse(in);
            check("chunked request body decoded", chunked.endsWith("hello world"));

            out.write("GET /hello.txt HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes());
            out.flush();
            String last = readResponse(in);
            check("connection close honoured", last.contains("Connection: close"));
            eq("socket closed by server", in.read(), -1);
        }
        try (Socket s = new Socket("localhost", port)) {
            s.getOutputStream().write("GET /hello.txt HTTP/1.0\r\n\r\n".getBytes());
            s.getOutputStream().flush();
            String r = readResponse(s.getInputStream());
            check("http/1.0 without host works", r.startsWith("HTTP/1.1 200"));
            check("http/1.0 closes", r.contains("Connection: close"));
        }
        try (Socket s = new Socket("localhost", port)) {
            s.getOutputStream().write("GET /hello.txt HTTP/1.1\r\n\r\n".getBytes());
            s.getOutputStream().flush();
            check("http/1.1 without host is 400", readResponse(s.getInputStream()).startsWith("HTTP/1.1 400"));
        }
        try (Socket s = new Socket("localhost", port)) {
            s.getOutputStream().write("NOT A REQUEST\r\n\r\n".getBytes());
            s.getOutputStream().flush();
            check("garbage is 400", readResponse(s.getInputStream()).startsWith("HTTP/1.1 400"));
        }
        try (Socket s = new Socket("localhost", port)) {
            s.getOutputStream().write("GET /../../../etc/passwd HTTP/1.1\r\nHost: x\r\n\r\n".getBytes());
            s.getOutputStream().flush();
            String r = readResponse(s.getInputStream());
            check("path traversal is normalised away", r.startsWith("HTTP/1.1 404"));
        }
        try (Socket s = new Socket("localhost", port)) {
            s.getOutputStream().write("GET /%2e%2e/%2e%2e/etc/passwd HTTP/1.1\r\nHost: x\r\n\r\n".getBytes());
            s.getOutputStream().flush();
            check("encoded traversal is blocked", readResponse(s.getInputStream()).startsWith("HTTP/1.1 404"));
        }
        try (Socket s = new Socket("localhost", port)) {
            s.setSoTimeout(5000);
            long start = System.currentTimeMillis();
            eq("idle connection closed after timeout", s.getInputStream().read(), -1);
            check("idle timeout roughly 2s", System.currentTimeMillis() - start >= 1500);
        }
    }

    static void testConcurrency() throws Exception {
        List<Thread> threads = new ArrayList<>();
        int[] ok = new int[1];
        for (int i = 0; i < 20; i++) {
            Thread t = new Thread(() -> {
                try {
                    HttpClient c = HttpClient.newHttpClient();
                    for (int j = 0; j < 10; j++) {
                        HttpResponse<byte[]> r = c.send(req("/hello.txt").build(), HttpResponse.BodyHandlers.ofByteArray());
                        if (r.statusCode() == 200 && text(r).equals("hello world")) {
                            synchronized (ok) { ok[0]++; }
                        }
                    }
                } catch (Exception e) {
                    // counted as failures below
                }
            });
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join();
        eq("200 concurrent requests succeed", ok[0], 200);
    }

    /** Reads one response (headers + Content-Length body) and returns it as text. */
    static String readResponse(InputStream in) throws Exception {
        StringBuilder head = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            head.append((char) b);
            if (head.length() >= 4 && head.substring(head.length() - 4).equals("\r\n\r\n")) break;
        }
        String headers = head.toString();
        int len = 0;
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(line.substring(15).trim());
        }
        byte[] body = in.readNBytes(len);
        return headers + new String(body, StandardCharsets.UTF_8);
    }
}
