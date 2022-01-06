package jhttpd;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Serves files from a document root with directory indexes, MIME types,
 * conditional requests (ETag / Last-Modified) and byte-range requests.
 */
public final class StaticHandler implements Handler {
    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"), Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"), Map.entry("js", "application/javascript; charset=utf-8"),
            Map.entry("mjs", "application/javascript; charset=utf-8"), Map.entry("json", "application/json"),
            Map.entry("txt", "text/plain; charset=utf-8"), Map.entry("md", "text/markdown; charset=utf-8"),
            Map.entry("xml", "application/xml"), Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"), Map.entry("ico", "image/x-icon"),
            Map.entry("pdf", "application/pdf"), Map.entry("zip", "application/zip"),
            Map.entry("wasm", "application/wasm"), Map.entry("mp4", "video/mp4"), Map.entry("mp3", "audio/mpeg"),
            Map.entry("woff", "font/woff"), Map.entry("woff2", "font/woff2"));

    private final Path root;
    private final boolean listDirectories;

    public StaticHandler(Path root, boolean listDirectories) {
        this.root = root.toAbsolutePath().normalize();
        this.listDirectories = listDirectories;
    }

    public static String mimeType(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return MIME.getOrDefault(ext, "application/octet-stream");
    }

    @Override
    public void handle(Request req, Response res) throws Exception {
        if (!req.method.equals("GET") && !req.method.equals("HEAD")) {
            throw new HttpException(405, "method not allowed");
        }
        Path file = root.resolve(req.path.substring(1)).normalize();
        if (!file.startsWith(root)) throw new HttpException(403, "forbidden");
        if (!Files.exists(file)) throw new HttpException(404, "not found");

        if (Files.isDirectory(file)) {
            if (!req.path.endsWith("/")) {
                res.redirect(req.path + "/");
                res.status(301);
                return;
            }
            Path index = file.resolve("index.html");
            if (Files.isRegularFile(index)) {
                file = index;
            } else if (listDirectories) {
                res.html(listing(file, req.path));
                return;
            } else {
                throw new HttpException(403, "directory listing denied");
            }
        }
        if (!Files.isRegularFile(file) || !Files.isReadable(file)) throw new HttpException(403, "forbidden");

        long size = Files.size(file);
        Instant modified = Files.getLastModifiedTime(file).toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String etag = "\"" + Long.toHexString(size) + "-" + Long.toHexString(modified.getEpochSecond()) + "\"";
        res.header("ETag", etag);
        res.header("Last-Modified", ZonedDateTime.ofInstant(modified, ZoneOffset.UTC).format(Response.HTTP_DATE));
        res.header("Accept-Ranges", "bytes");
        res.header("Cache-Control", "public, max-age=0, must-revalidate");

        // conditional requests
        String ifNoneMatch = req.header("if-none-match");
        if (ifNoneMatch != null && (ifNoneMatch.equals("*") || ifNoneMatch.contains(etag))) {
            res.status(304);
            return;
        }
        String ifModifiedSince = req.header("if-modified-since");
        if (ifModifiedSince != null && ifNoneMatch == null) {
            try {
                Instant since = ZonedDateTime.parse(ifModifiedSince, Response.HTTP_DATE).toInstant();
                if (!modified.isAfter(since)) {
                    res.status(304);
                    return;
                }
            } catch (DateTimeParseException ignored) {
                // malformed date: ignore the header
            }
        }

        String type = mimeType(file.getFileName().toString());
        String range = req.header("range");
        if (range != null && range.startsWith("bytes=") && size > 0) {
            long[] r = parseRange(range.substring(6), size);
            if (r == null) {
                res.header("Content-Range", "bytes */" + size);
                throw new HttpException(416, "range not satisfiable");
            }
            long start = r[0], end = r[1];
            InputStream in = Files.newInputStream(file);
            in.skipNBytes(start);
            res.status(206);
            res.header("Content-Range", "bytes " + start + "-" + end + "/" + size);
            res.stream(in, end - start + 1, type);
            return;
        }
        res.stream(Files.newInputStream(file), size, type);
    }

    /** Parses "start-end", "start-" or "-suffix" into [start, end] or null. */
    static long[] parseRange(String spec, long size) {
        int dash = spec.indexOf('-');
        if (dash < 0 || spec.contains(",")) return null;
        try {
            String a = spec.substring(0, dash).trim();
            String b = spec.substring(dash + 1).trim();
            long start, end;
            if (a.isEmpty()) {
                long suffix = Long.parseLong(b);
                if (suffix <= 0) return null;
                start = Math.max(0, size - suffix);
                end = size - 1;
            } else {
                start = Long.parseLong(a);
                end = b.isEmpty() ? size - 1 : Math.min(Long.parseLong(b), size - 1);
            }
            if (start < 0 || start > end || start >= size) return null;
            return new long[] {start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String listing(Path dir, String urlPath) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"><title>Index of ").append(escape(urlPath))
          .append("</title><style>body{font-family:system-ui,sans-serif;margin:2rem}a{display:block;padding:.2rem 0}</style></head><body>")
          .append("<h1>Index of ").append(escape(urlPath)).append("</h1>");
        if (!urlPath.equals("/")) sb.append("<a href=\"../\">../</a>");
        try (Stream<Path> entries = Files.list(dir)) {
            entries.sorted((x, y) -> {
                boolean dx = Files.isDirectory(x), dy = Files.isDirectory(y);
                if (dx != dy) return dx ? -1 : 1;
                return x.getFileName().toString().compareToIgnoreCase(y.getFileName().toString());
            }).forEach(p -> {
                String name = p.getFileName().toString() + (Files.isDirectory(p) ? "/" : "");
                sb.append("<a href=\"").append(escape(name)).append("\">").append(escape(name)).append("</a>");
            });
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
