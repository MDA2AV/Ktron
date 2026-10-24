# Ktron

A Kotlin web framework to break benchmarks.

Ktron is an HTTP/1.1 framework on the Kotlin port of [ioxide](https://github.com/MDA2AV/ioxide), a shared-nothing
io_uring runtime: one reactor per core, each with its own ring and its share of the SO_REUSEPORT listeners, the
syscalls made straight through the JVM's Foreign Function & Memory API. Handlers run on the reactor thread that
took the request; a suspending one resumes there too. There is no thread pool.

```kotlin
fun main() {
    Ktron(KtronOptions(port = 8080))
        .use(Compression())
        .get("/hello/{name}") { ctx -> ctx.response.text("hello " + ctx.request.param("name")) }
        .get("/items/{id}") { ctx -> ctx.response.json(Item(ctx.request.paramInt("id"), "widget")) }
        .getAsync("/later") { ctx ->
            ctx.delay(10)   // an IORING_OP_TIMEOUT on the reactor's ring
            ctx.response.text("later")
        }
        .run()
}

@GhostSerialization
data class Item(val id: Int, val name: String)
```

## How it works

- **Parsing**: [Glyph11](https://github.com/dotnet-web-stack/Glyph11), the hardened HTTP/1.1 parser (request
  smuggling, limits, RFC 9112 strictness), from genhttp-kt's Kotlin port, rewritten to parse a byte array in one
  pass and hand back offsets: about 25 ns for a GET and 130 ns for a 12-header browser request, allocating nothing.
  That is 2 to 3 times faster than Glyph11's C core called through FFM, whose downcall costs only about 5 ns; the
  C parse itself is the slower part. See [glyph11/](glyph11/README.md).
- **Routing**: a trie over path segments - literals, `{param}` (or `:param`), `{*rest}` - with handlers by method,
  405 with an Allow line, HEAD from GET. A match allocates nothing: parameters are offsets into the request.
- **Middleware**: `before`/`after` hooks, in order, that can short-circuit. They are synchronous, so the
  sync path runs no coroutine at all; handlers between them may suspend.
- **Responses**: status, headers and body in one call (`text`, `bytes`, `json`), staged in the connection's
  io_uring write slab and sent with one flush per batch of pipelined requests.
- **JSON**: [Ghost](https://github.com/juanchurtado1991/ghost-serializer)'s KSP-generated serializers, writing into
  a buffer of the connection's own: no array per response.
- **Compression**: the `Compression` middleware, gzip on libdeflate and brotli on libbrotlienc, both over FFM.
  It defaults to gzip level 1: measured on dynamic JSON of a few KB against every gzip, zlib and brotli setting,
  it scores best under HttpArena's json-comp formula, where speed outweighs the bytes a slower level saves.
  Without libdeflate it falls back to `java.util.zip`.
- **TLS**: ioxide's, on BoringSSL with optional kernel TLS (`KtronTls`).

## Layout

- `ktron/` the framework
- `glyph11/` the parser
- `examples/arena/` HttpArena's HTTP/1.1 profiles on Ktron

The runtime is `kotlin/ioxide` of an ioxide checkout next to this one (`-PioxideKt=<dir>` points elsewhere).
Build with `./gradlew build`; JDK 25.

MIT.
