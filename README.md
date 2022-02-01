# jhttpd

> 🇺🇸 [English version below](#english)

Um servidor HTTP/1.1 em Java em cima de socket puro. Sem framework, sem servlet container, sem Maven: `javac` e a biblioteca padrão. Serve arquivos direito (MIME, index, ETag, requisições condicionais, byte ranges) e tem um roteador pequeno pra APIs JSON.

Eu programava em Java na faculdade usando Spring sem saber o que era uma requisição HTTP de verdade. Este repo é a resposta que eu devia ter buscado na época.

```sh
bash build.sh                       # compila src/ e test/ em out/
java -cp out jhttpd.Main 8080 www   # serve ./www na 8080
java -cp out jhttpd.ServerTest      # roda os testes
```

```sh
curl 'http://localhost:8080/api/hello?name=ana'
curl -X PUT -d 'buy milk' http://localhost:8080/api/notes/1
curl -i -H 'Range: bytes=0-9' http://localhost:8080/files/readme.txt
curl -sI http://localhost:8080/ | grep ETag
```

## O que tem

- Parsing de HTTP/1.1: request line, headers, corpo por `Content-Length` ou `chunked`, path com percent-decoding, query string
- Conexões persistentes com timeout de ociosidade, `Connection: close`, HTTP/1.0
- Thread pool: uma thread aceitando, workers servindo
- Arquivos estáticos: MIME, `index.html`, listagem de diretório, redirect de diretório, `HEAD`, `ETag`/`Last-Modified` com `If-None-Match`/`If-Modified-Since` (304), `Range` (206/416), streaming de arquivos grandes, proteção contra `..`
- Roteador com `:param` e `*`, handlers por método, 404/405 corretos, exceções viram status, 500 em bug
- gzip nas respostas de texto quando o cliente aceita
- Log de acesso

## Como se encaixa

`Server` abre o `ServerSocket` e entrega cada conexão pro pool; um worker fica em loop: parseia uma requisição, despacha, escreve a resposta, repete enquanto o keep-alive estiver ligado. `Request.parse` lê a request line e os headers byte a byte (terminados em CRLF, com limite de tamanho) e depois o corpo. `Response` junta status, headers e corpo (bytes ou um stream de tamanho conhecido) e serializa, acrescentando `Date`, `Content-Length`, `Connection` e gzip quando cabe. `Router` casa método e padrão de path; o `StaticHandler` pega o que sobrar.

A lição mais concreta: byte range e requisição condicional são o que separa "serve arquivo" de "serve vídeo pro navegador sem travar". Antes de implementar `Range`, o `<video>` do Chrome simplesmente não fazia seek.

Testes: `test/jhttpd/ServerTest.java` sobe o servidor numa porta aleatória e confere 65 comportamentos com `HttpClient` e sockets crus, incluindo keep-alive no mesmo socket, corpo chunked, HTTP/1.0, requisições malformadas, path traversal, timeout e 200 requisições concorrentes.

---

## English

An HTTP/1.1 server in Java on top of a raw socket. No framework, no servlet container, no Maven: `javac` and the standard library. Serves files properly (MIME, index, ETag, conditional requests, byte ranges) and has a small router for JSON APIs.

I used to program in Java at college using Spring without knowing what a real HTTP request was. This repo is the answer I should have looked for back then.

```sh
bash build.sh                       # compiles src/ and test/ into out/
java -cp out jhttpd.Main 8080 www   # serves ./www on 8080
java -cp out jhttpd.ServerTest      # runs the tests
```

```sh
curl 'http://localhost:8080/api/hello?name=ana'
curl -X PUT -d 'buy milk' http://localhost:8080/api/notes/1
curl -i -H 'Range: bytes=0-9' http://localhost:8080/files/readme.txt
curl -sI http://localhost:8080/ | grep ETag
```

## What's in it

- HTTP/1.1 parsing: request line, headers, body by `Content-Length` or `chunked`, percent-decoded path, query string
- Persistent connections with idle timeout, `Connection: close`, HTTP/1.0
- Thread pool: one thread accepting, workers serving
- Static files: MIME, `index.html`, directory listing, directory redirect, `HEAD`, `ETag`/`Last-Modified` with `If-None-Match`/`If-Modified-Since` (304), `Range` (206/416), streaming of large files, protection against `..`
- Router with `:param` and `*`, per-method handlers, correct 404/405, exceptions become statuses, 500 on a bug
- gzip on text responses when the client accepts it
- Access log

## How it fits together

`Server` opens the `ServerSocket` and hands each connection to the pool; a worker loops: parses one request, dispatches, writes the response, repeats while keep-alive is on. `Request.parse` reads the request line and the headers byte by byte (CRLF-terminated, with a size limit) and then the body. `Response` gathers status, headers and body (bytes or a stream of known size) and serializes, adding `Date`, `Content-Length`, `Connection` and gzip when it applies. `Router` matches method and path pattern; `StaticHandler` takes whatever is left.

The most concrete lesson: byte ranges and conditional requests are what separates "serves a file" from "serves video to the browser without choking". Before implementing `Range`, Chrome's `<video>` simply wouldn't seek.

Tests: `test/jhttpd/ServerTest.java` starts the server on a random port and checks 65 behaviours with `HttpClient` and raw sockets, including keep-alive on the same socket, chunked body, HTTP/1.0, malformed requests, path traversal, timeout and 200 concurrent requests.

MIT.
