package jhttpd;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** A parsed HTTP/1.x request: request line, headers and body. */
public final class Request {
    private static final int MAX_LINE = 8192;
    private static final int MAX_HEADERS = 100;
    private static final long MAX_BODY = 16L * 1024 * 1024;

    public final String method;
    public final String target;      // raw request target, e.g. /a/b?x=1
    public final String path;        // decoded path without the query string
    public final String version;     // HTTP/1.0 or HTTP/1.1
    public final Map<String, String> headers; // lower-cased names
    public final Map<String, String> query;
    public final byte[] body;
    /** Route parameters filled in by the router (for /users/:id style paths). */
    public final Map<String, String> params = new LinkedHashMap<>();

    private Request(String method, String target, String version, Map<String, String> headers, byte[] body) {
        this.method = method;
        this.target = target;
        this.version = version;
        this.headers = Collections.unmodifiableMap(headers);
        this.body = body;
        int q = target.indexOf('?');
        String rawPath = q >= 0 ? target.substring(0, q) : target;
        this.path = normalizePath(percentDecode(rawPath));
        this.query = q >= 0 ? parseQuery(target.substring(q + 1)) : Map.of();
    }

    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }

    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /** True when the client wants the connection kept open after this request. */
    public boolean keepAlive() {
        String c = header("connection");
        if (c != null) {
            c = c.toLowerCase(Locale.ROOT);
            if (c.contains("close")) return false;
            if (c.contains("keep-alive")) return true;
        }
        return "HTTP/1.1".equals(version);
    }

    /**
     * Reads one request from the stream. Returns null on a clean EOF before
     * any byte of a new request (the client closed a keep-alive connection).
     */
    public static Request parse(InputStream in) throws IOException, HttpException {
        String requestLine = readLine(in);
        if (requestLine == null) return null;
        // tolerate stray CRLF between requests
        while (requestLine.isEmpty()) {
            requestLine = readLine(in);
            if (requestLine == null) return null;
        }
        String[] parts = requestLine.split(" ");
        if (parts.length != 3 || !parts[2].startsWith("HTTP/1.")) {
            throw new HttpException(400, "malformed request line");
        }
        String method = parts[0].toUpperCase(Locale.ROOT);
        String target = parts[1];
        String version = parts[2];

        Map<String, String> headers = new TreeMap<>();
        String line;
        int count = 0;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            if (++count > MAX_HEADERS) throw new HttpException(431, "too many headers");
            int colon = line.indexOf(':');
            if (colon <= 0) throw new HttpException(400, "malformed header");
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            headers.merge(name, value, (a, b) -> a + ", " + b);
        }
        if (line == null) throw new HttpException(400, "unexpected end of headers");
        if ("HTTP/1.1".equals(version) && !headers.containsKey("host")) {
            throw new HttpException(400, "Host header is required");
        }

        byte[] body = new byte[0];
        String te = headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            body = readChunked(in);
        } else if (headers.containsKey("content-length")) {
            long len;
            try {
                len = Long.parseLong(headers.get("content-length"));
            } catch (NumberFormatException e) {
                throw new HttpException(400, "bad Content-Length");
            }
            if (len < 0 || len > MAX_BODY) throw new HttpException(413, "body too large");
            body = in.readNBytes((int) len);
            if (body.length != len) throw new HttpException(400, "truncated body");
        }
        return new Request(method, target, version, headers, body);
    }

    /** Reads a CRLF (or LF) terminated line as ISO-8859-1; null at EOF with no data. */
    static String readLine(InputStream in) throws IOException, HttpException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(80);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                byte[] bytes = buf.toByteArray();
                int len = bytes.length;
                if (len > 0 && bytes[len - 1] == '\r') len--;
                return new String(bytes, 0, len, StandardCharsets.ISO_8859_1);
            }
            buf.write(b);
            if (buf.size() > MAX_LINE) throw new HttpException(431, "line too long");
        }
        if (buf.size() == 0) return null;
        return buf.toString(StandardCharsets.ISO_8859_1);
    }

    private static byte[] readChunked(InputStream in) throws IOException, HttpException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            if (sizeLine == null) throw new HttpException(400, "truncated chunked body");
            int semi = sizeLine.indexOf(';');
            String hex = (semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim();
            int size;
            try {
                size = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new HttpException(400, "bad chunk size");
            }
            if (size == 0) {
                // trailers until blank line
                String t;
                while ((t = readLine(in)) != null && !t.isEmpty()) { /* ignore trailers */ }
                return body.toByteArray();
            }
            if (body.size() + size > MAX_BODY) throw new HttpException(413, "body too large");
            byte[] chunk = in.readNBytes(size);
            if (chunk.length != size) throw new HttpException(400, "truncated chunk");
            body.write(chunk);
            readLine(in); // CRLF after chunk data
        }
    }

    static Map<String, String> parseQuery(String qs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : qs.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            out.putIfAbsent(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return Collections.unmodifiableMap(out);
    }

    static String percentDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length() + 0 && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16));
                i += 2;
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                out.write(bytes, 0, bytes.length);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** Collapses "." and ".." segments so the path cannot escape the root. */
    static String normalizePath(String path) {
        if (!path.startsWith("/")) path = "/" + path;
        java.util.ArrayDeque<String> segments = new java.util.ArrayDeque<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!segments.isEmpty()) segments.removeLast();
            } else {
                segments.addLast(seg);
            }
        }
        String joined = "/" + String.join("/", segments);
        if (path.endsWith("/") && joined.length() > 1) joined += "/";
        return joined;
    }
}
