# jhttpd

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

**EN:** an HTTP/1.1 server on raw Java sockets (no frameworks, no build tool): request parsing with chunked bodies, keep-alive with idle timeouts, a thread pool, a static file handler with MIME types, directory indexes, ETag/Last-Modified conditionals, byte ranges and path-traversal protection, a small router for JSON APIs, gzip and access logging. 65 in-process tests. MIT.
