package jhttpd;

/** An error that maps directly to an HTTP status code. */
public final class HttpException extends Exception {
    private static final long serialVersionUID = 1L;
    public final int status;

    public HttpException(int status, String message) {
        super(message);
        this.status = status;
    }
}
