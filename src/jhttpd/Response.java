package jhttpd;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** An HTTP response under construction; written by the server. */
public final class Response {
    public static final DateTimeFormatter HTTP_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

    private int status = 200;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private byte[] body = new byte[0];
    private InputStream stream;   // streamed body (files), used instead of body when set
    private long streamLength = -1;
    private boolean headOnly;

    public Response status(int status) {
        this.status = status;
        return this;
    }

    public int status() {
        return status;
    }

    public Response header(String name, String value) {
        headers.put(name, value);
        return this;
    }

    public String header(String name) {
        for (Map.Entry<String, String> e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return null;
    }

    public Response body(byte[] bytes, String contentType) {
        this.body = bytes;
        this.stream = null;
        header("Content-Type", contentType);
        return this;
    }

    public Response text(String text) {
        return body(text.getBytes(StandardCharsets.UTF_8), "text/plain; charset=utf-8");
    }

    public Response html(String html) {
        return body(html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8");
    }

    public Response json(String json) {
        return body(json.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    /** Streams a body of known length from an input stream (closed after writing). */
    public Response stream(InputStream in, long length, String contentType) {
        this.stream = in;
        this.streamLength = length;
        header("Content-Type", contentType);
        return this;
    }

    public Response redirect(String location) {
        status(302);
        header("Location", location);
        return text("redirecting to " + location);
    }

    void headOnly(boolean headOnly) {
        this.headOnly = headOnly;
    }

    byte[] body() {
        return body;
    }

    static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 408: return "Request Timeout";
            case 411: return "Length Required";
            case 413: return "Payload Too Large";
            case 416: return "Range Not Satisfiable";
            case 431: return "Request Header Fields Too Large";
            case 500: return "Internal Server Error";
            case 501: return "Not Implemented";
            default: return "Status " + status;
        }
    }

    /**
     * Serialises the response. Compresses in-memory text bodies with gzip
     * when the client accepts it, and omits the body for HEAD requests.
     */
    void write(OutputStream out, boolean keepAlive, boolean gzipAccepted) throws IOException {
        byte[] payload = body;
        if (stream == null && gzipAccepted && payload.length >= 256 && isCompressible(header("Content-Type"))
                && header("Content-Encoding") == null) {
            ByteArrayOutputStream gz = new ByteArrayOutputStream();
            try (GZIPOutputStream g = new GZIPOutputStream(gz)) {
                g.write(payload);
            }
            payload = gz.toByteArray();
            header("Content-Encoding", "gzip");
            header("Vary", "Accept-Encoding");
        }
        long length = stream != null ? streamLength : payload.length;

        StringBuilder head = new StringBuilder(256);
        head.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        headers.putIfAbsent("Date", ZonedDateTime.now(java.time.ZoneOffset.UTC).format(HTTP_DATE));
        headers.putIfAbsent("Server", "jhttpd/1.0");
        if (status != 304 && status != 204) headers.put("Content-Length", Long.toString(length));
        headers.put("Connection", keepAlive ? "keep-alive" : "close");
        for (Map.Entry<String, String> e : headers.entrySet()) {
            head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        }
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));

        if (!headOnly && status != 304 && status != 204) {
            if (stream != null) {
                try (InputStream in = stream) {
                    byte[] buf = new byte[64 * 1024];
                    long remaining = streamLength;
                    int n;
                    while (remaining > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, remaining))) != -1) {
                        out.write(buf, 0, n);
                        remaining -= n;
                    }
                }
            } else {
                out.write(payload);
            }
        } else if (stream != null) {
            stream.close();
        }
        out.flush();
    }

    private static boolean isCompressible(String contentType) {
        if (contentType == null) return false;
        String t = contentType.toLowerCase();
        return t.startsWith("text/") || t.contains("json") || t.contains("javascript") || t.contains("xml") || t.contains("svg");
    }
}
