# jhttpd

An HTTP/1.1 server written from scratch in Java on top of plain sockets. No
frameworks, no servlet containers, no build tool: `javac` and the standard
library. It serves static files properly (MIME types, directory indexes,
ETags, conditional requests, byte ranges) and has a small router for writing
JSON APIs.

```sh
bash build.sh                       # compiles src/ and test/ into out/
java -cp out jhttpd.Main 8080 www   # serve ./www on port 8080
java -cp out jhttpd.ServerTest      # run the test-suite
```

Then open http://localhost:8080/ or try:

```sh
curl 'http://localhost:8080/api/hello?name=ana'
curl -X PUT -d 'buy milk' http://localhost:8080/api/notes/1
curl -i -H 'Range: bytes=0-9' http://localhost:8080/files/readme.txt
curl -sI http://localhost:8080/ | grep ETag
```

## Features

- HTTP/1.1 request parsing: request line, headers, `Content-Length` and
  `chunked` bodies, percent-decoded paths, query strings
- Persistent connections with idle timeouts, `Connection: close`, HTTP/1.0
- Thread pool: one accept thread, connections served by worker threads
- Static files: MIME types, `index.html`, directory listings, directory
  redirects, `HEAD`, `ETag` / `Last-Modified` with `If-None-Match` /
  `If-Modified-Since` (304), `Range` requests (206 / 416), streaming of
  large files, path traversal protection
- Router with `:param` segments and `*` wildcards, per-method handlers,
  correct 404 / 405, exceptions mapped to status codes, 500 on bugs
- gzip compression of text responses when the client accepts it
- Access log

## How it works

- `Server` binds a `ServerSocket`, accepts connections and hands each one to
  a fixed thread pool. A worker loops on the connection: parse a request,
  dispatch it, write the response, and repeat while keep-alive is on.
- `Request.parse` reads the request line and headers byte by byte (CRLF
  terminated, size-limited), then the body according to `Content-Length` or
  the chunked encoding. Paths are percent-decoded and normalised so `..`
  cannot escape the document root.
- `Response` collects status, headers and a body (bytes or a stream of known
  length) and serialises them, adding `Date`, `Content-Length`, `Connection`
  and, when appropriate, gzip encoding.
- `Router` matches method and path pattern and binds path parameters; an
  optional fallback handler (the `StaticHandler`) takes unmatched paths.
- `StaticHandler` maps the URL onto the document root, validates it, then
  handles conditional and range requests before streaming the file.

## Tests

`test/jhttpd/ServerTest.java` starts the server in-process on a random port
and checks 65 behaviours with `java.net.http.HttpClient` and raw sockets:
static files and indexes, routes and parameters, POST bodies, conditional
requests, ranges, gzip, keep-alive on one socket, chunked request bodies,
HTTP/1.0, malformed requests, path traversal, idle timeouts and 200
concurrent requests.

## License

MIT
