package jhttpd;

/** Something that fills in a response for a request. */
@FunctionalInterface
public interface Handler {
    void handle(Request req, Response res) throws Exception;
}
