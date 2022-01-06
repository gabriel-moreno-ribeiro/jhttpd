package jhttpd;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Command line entry point: serves a directory and a small JSON API.
 *
 *   java -cp out jhttpd.Main [port] [document-root]
 */
public final class Main {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        Path root = Path.of(args.length > 1 ? args[1] : ".");

        AtomicInteger hits = new AtomicInteger();
        ConcurrentHashMap<String, String> notes = new ConcurrentHashMap<>();

        Router router = new Router()
                .get("/api/hello", (req, res) -> res.json("{\"hello\":\"" + req.query.getOrDefault("name", "world") + "\"}"))
                .get("/api/hits", (req, res) -> res.json("{\"hits\":" + hits.incrementAndGet() + "}"))
                .get("/api/notes/:id", (req, res) -> {
                    String note = notes.get(req.params.get("id"));
                    if (note == null) throw new HttpException(404, "no such note");
                    res.text(note);
                })
                .put("/api/notes/:id", (req, res) -> {
                    notes.put(req.params.get("id"), req.bodyText());
                    res.status(201).text("stored");
                })
                .delete("/api/notes/:id", (req, res) -> {
                    if (notes.remove(req.params.get("id")) == null) throw new HttpException(404, "no such note");
                    res.status(204);
                })
                .post("/api/echo", (req, res) -> res.body(req.body, req.header("content-type") != null ? req.header("content-type") : "application/octet-stream"))
                .fallback(new StaticHandler(root, true));

        Server server = new Server(port, router);
        server.start();
        System.out.println("jhttpd listening on http://localhost:" + server.port() + " serving " + root.toAbsolutePath());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.join();
    }
}
