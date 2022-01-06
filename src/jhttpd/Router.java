package jhttpd;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Matches requests to handlers by method and path pattern. Patterns may
 * contain named segments (/users/:id) and a trailing wildcard (/files/*).
 */
public final class Router implements Handler {

    private static final class Route {
        final String method;
        final String[] segments;
        final Handler handler;

        Route(String method, String pattern, Handler handler) {
            this.method = method;
            this.segments = pattern.equals("/") ? new String[0] : pattern.substring(1).split("/");
            this.handler = handler;
        }

        boolean matches(Request req) {
            String[] parts = req.path.equals("/") ? new String[0] : req.path.substring(1).split("/");
            for (int i = 0; i < segments.length; i++) {
                String seg = segments[i];
                if (seg.equals("*")) return true;
                if (i >= parts.length) return false;
                if (seg.startsWith(":")) continue;
                if (!seg.equals(parts[i])) return false;
            }
            return parts.length == segments.length;
        }

        void bind(Request req) {
            String[] parts = req.path.equals("/") ? new String[0] : req.path.substring(1).split("/");
            for (int i = 0; i < segments.length && i < parts.length; i++) {
                if (segments[i].startsWith(":")) req.params.put(segments[i].substring(1), parts[i]);
                if (segments[i].equals("*")) {
                    req.params.put("*", String.join("/", java.util.Arrays.copyOfRange(parts, i, parts.length)));
                    break;
                }
            }
        }
    }

    private final List<Route> routes = new ArrayList<>();
    private Handler fallback;

    public Router get(String pattern, Handler h) { return add("GET", pattern, h); }
    public Router post(String pattern, Handler h) { return add("POST", pattern, h); }
    public Router put(String pattern, Handler h) { return add("PUT", pattern, h); }
    public Router delete(String pattern, Handler h) { return add("DELETE", pattern, h); }

    public Router add(String method, String pattern, Handler h) {
        routes.add(new Route(method.toUpperCase(Locale.ROOT), pattern, h));
        return this;
    }

    /** Handler used when no route matches (typically a static file handler). */
    public Router fallback(Handler h) {
        this.fallback = h;
        return this;
    }

    @Override
    public void handle(Request req, Response res) throws Exception {
        boolean pathMatched = false;
        for (Route r : routes) {
            if (!r.matches(req)) continue;
            pathMatched = true;
            String method = req.method.equals("HEAD") ? "GET" : req.method;
            if (r.method.equals(method)) {
                r.bind(req);
                r.handler.handle(req, res);
                return;
            }
        }
        if (pathMatched) throw new HttpException(405, "method not allowed");
        if (fallback != null) {
            fallback.handle(req, res);
            return;
        }
        throw new HttpException(404, "not found");
    }
}
