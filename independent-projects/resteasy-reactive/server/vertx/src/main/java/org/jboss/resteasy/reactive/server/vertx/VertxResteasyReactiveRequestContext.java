package org.jboss.resteasy.reactive.server.vertx;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import org.jboss.resteasy.reactive.common.ResteasyReactiveConfig;
import org.jboss.resteasy.reactive.common.util.CaseInsensitiveMap;
import org.jboss.resteasy.reactive.server.core.Deployment;
import org.jboss.resteasy.reactive.server.core.LazyResponse;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;
import org.jboss.resteasy.reactive.server.core.multipart.FormData;
import org.jboss.resteasy.reactive.server.core.parameters.ParameterExtractor;
import org.jboss.resteasy.reactive.server.handlers.ParameterHandler;
import org.jboss.resteasy.reactive.server.spi.ServerHttpRequest;
import org.jboss.resteasy.reactive.server.spi.ServerHttpResponse;
import org.jboss.resteasy.reactive.server.spi.ServerRestHandler;
import org.jboss.resteasy.reactive.spi.ThreadSetupAction;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoop;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;
import io.netty.util.concurrent.ScheduledFuture;
import io.quarkus.vertx.utils.NoBoundChecksBuffer;
import io.quarkus.vertx.utils.VertxJavaIoContext;
import io.quarkus.vertx.utils.VertxOutputStream;
import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.buffer.impl.VertxByteBufAllocator;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.impl.Http1xServerResponse;
import io.vertx.core.net.impl.ConnectionBase;
import io.vertx.ext.web.RoutingContext;

public class VertxResteasyReactiveRequestContext extends ResteasyReactiveRequestContext
        implements ServerHttpRequest, ServerHttpResponse, Handler<Void> {

    public static final String CONTINUE = "100-continue";
    protected final RoutingContext context;
    protected final HttpServerRequest request;
    protected final HttpServerResponse response;
    private final Executor contextExecutor;
    private final ClassLoader devModeTccl;
    protected Consumer<ResteasyReactiveRequestContext> preCommitTask;
    ContinueState continueState = ContinueState.NONE;

    public VertxResteasyReactiveRequestContext(Deployment deployment,
            RoutingContext context,
            ThreadSetupAction requestContext, ServerRestHandler[] handlerChain, ServerRestHandler[] abortHandlerChain,
            ClassLoader devModeTccl) {
        super(deployment, requestContext, handlerChain, abortHandlerChain);
        this.context = context;
        this.request = context.request();
        this.response = context.response();
        this.devModeTccl = devModeTccl;
        context.addHeadersEndHandler(this);
        String expect = request.getHeader(HttpHeaderNames.EXPECT);
        Context current = Vertx.currentContext();
        if (expect != null && expect.equalsIgnoreCase(CONTINUE)) {
            continueState = ContinueState.REQUIRED;
        }
        this.contextExecutor = new Executor() {
            @Override
            public void execute(Runnable command) {
                current.runOnContext(new Handler<Void>() {
                    @Override
                    public void handle(Void unused) {
                        command.run();
                    }
                });
            }
        };
        request.pause();
    }

    @Override
    public ServerHttpResponse addCloseHandler(Runnable onClose) {
        this.response.closeHandler(new Handler<Void>() {
            @Override
            public void handle(Void v) {
                onClose.run();
            }
        });
        return this;
    }

    public RoutingContext getContext() {
        return context;
    }

    @Override
    public ServerHttpRequest serverRequest() {
        return this;
    }

    @Override
    public ServerHttpResponse serverResponse() {
        return this;
    }

    @Override
    protected void setQueryParamsFrom(String uri) {
        MultiMap map = context.queryParams();
        map.clear();
        Map<String, List<String>> decodedParams = new QueryStringDecoder(uri).parameters();
        for (Map.Entry<String, List<String>> entry : decodedParams.entrySet()) {
            map.add(entry.getKey(), entry.getValue());
        }
    }

    @Override
    protected EventLoop getEventLoop() {
        return ((ConnectionBase) context.request().connection()).channel().eventLoop();
    }

    public Executor getContextExecutor() {
        return contextExecutor;
    }

    @Override
    public Runnable registerTimer(long millis, Runnable task) {
        ScheduledFuture<?> handle = getEventLoop().schedule(task, millis, TimeUnit.MILLISECONDS);
        return new Runnable() {
            @Override
            public void run() {
                handle.cancel(false);
            }
        };
    }

    @Override
    public boolean resumeExternalProcessing() {
        context.next();
        return true;
    }

    @Override
    public String getRequestHeader(CharSequence name) {
        return request.headers().get(name);
    }

    @Override
    public Iterable<Map.Entry<String, String>> getAllRequestHeaders() {
        return request.headers();
    }

    @Override
    public List<String> getAllRequestHeaders(String name) {
        return request.headers().getAll(name);
    }

    @Override
    public boolean containsRequestHeader(CharSequence accept) {
        return request.headers().contains(accept);
    }

    @Override
    public String getRequestPath() {
        return request.path();
    }

    @Override
    public String getRequestMethod() {
        return request.method().name();
    }

    @Override
    public String getRequestNormalisedPath() {
        return context.normalizedPath();
    }

    @Override
    public String getRequestAbsoluteUri() {
        return request.absoluteURI();
    }

    @Override
    public String getRequestScheme() {
        return request.scheme();
    }

    @Override
    public String getRequestHost() {
        return request.authority().toString();
    }

    @Override
    public void closeConnection() {
        request.connection().close();
    }

    @Override
    public String getQueryParam(String name) {
        return context.queryParams().get(name);
    }

    @Override
    public List<String> getAllQueryParams(String name) {
        return context.queryParam(name);
    }

    /**
     * Retrieves the parameters from the current HTTP request as a
     * {@link Map<String, List<String>>}, where the keys are parameter names
     * and the values are lists of parameter values. This allows parameters
     * to be extracted from the URL without knowing their names in advance.
     *
     * The method is used by {@link ParameterExtractor}, which works with characteristics
     * such as parameter name, single/multiple values, and encoding. Since it's
     * not always possible to distinguish between {@link Map} and {@link Multimap},
     * the method returns a unified {@link Map<String, List<String>>} for handling
     * both cases downstream by {@link ParameterHandler}.
     *
     * @return a {@link Map<String, List<String>>} containing the parameters and
     *         their corresponding values.
     */
    @Override
    public Map<String, List<String>> getQueryParamsMap() {
        MultiMap entries = context.request().params();
        final MultivaluedHashMap<String, String> result = new MultivaluedHashMap<>();
        if (!entries.isEmpty()) {
            for (Map.Entry<String, String> entry : entries) {
                result.add(entry.getKey(), entry.getValue());
            }

        }
        return new HashMap<>(result);
    }

    @Override
    public String query() {
        return request.query();
    }

    @Override
    public Collection<String> queryParamNames() {
        return context.queryParams().names();
    }

    @Override
    public boolean isRequestEnded() {
        return request.isEnded();
    }

    @Override
    public InputStream createInputStream(ByteBuffer existingData) {
        if (existingData == null) {
            return createInputStream();
        }
        return new VertxInputStream(context, getDeployment().getRuntimeConfiguration().readTimeout().toMillis(),
                Unpooled.wrappedBuffer(existingData), this);
    }

    @Override
    public InputStream createInputStream() {
        if (context.getBody() != null) {
            byte[] data = new byte[context.getBody().length()];
            context.getBody().getBytes(data);
            return new ByteArrayInputStream(data);
        }
        return new VertxInputStream(context, getDeployment().getRuntimeConfiguration().readTimeout().toMillis(), this);
    }

    @Override
    public ServerHttpResponse pauseRequestInput() {
        request.pause();
        return this;
    }

    @Override
    public ServerHttpResponse resumeRequestInput() {
        if (continueState == ContinueState.REQUIRED) {
            continueState = ContinueState.SENT;
            response.writeContinue();
        }
        request.resume();
        return this;
    }

    @Override
    public ServerHttpResponse setReadListener(ReadCallback callback) {
        if (context.getBody() != null) {
            callback.data(context.getBody().getByteBuf().nioBuffer());
            callback.done();
            return this;
        }
        request.pause();
        if (continueState == ContinueState.REQUIRED) {
            continueState = ContinueState.SENT;
            response.writeContinue();
        }
        request.handler(new Handler<Buffer>() {
            @Override
            public void handle(Buffer event) {
                if (devModeTccl != null) {
                    Thread.currentThread().setContextClassLoader(devModeTccl);
                }
                callback.data(ByteBuffer.wrap(event.getBytes()));
            }
        });
        request.endHandler(new Handler<Void>() {
            @Override
            public void handle(Void event) {
                if (devModeTccl != null) {
                    Thread.currentThread().setContextClassLoader(devModeTccl);
                }
                callback.done();
            }
        });
        request.resume();
        return this;
    }

    @Override
    public FormData getExistingParsedForm() {
        if (context.fileUploads().isEmpty() && request.formAttributes().isEmpty()) {
            return null;
        }
        FormData ret = new FormData(Integer.MAX_VALUE);
        for (var i : context.fileUploads()) {
            CaseInsensitiveMap<String> headers = new CaseInsensitiveMap<>();
            if (i.contentType() != null) {
                headers.add(HttpHeaders.CONTENT_TYPE, i.contentType());
            }
            ret.add(i.name(), Paths.get(i.uploadedFileName()), i.fileName(), headers);
        }
        for (var i : request.formAttributes()) {
            ret.add(i.getKey(), i.getValue());
        }
        return ret;
    }

    @Override
    public boolean isOnIoThread() {
        return ((ConnectionBase) request.connection()).channel().eventLoop().inEventLoop();
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T unwrap(Class<T> theType) {
        if (theType == RoutingContext.class) {
            return (T) context;
        } else if (theType == HttpServerRequest.class) {
            return (T) request;
        } else if (theType == HttpServerResponse.class) {
            return (T) response;
        } else if (theType == ResteasyReactiveRequestContext.class) {
            return (T) this;
        }
        return null;
    }

    @Override
    public ServerHttpResponse setStatusCode(int code) {
        if (!response.headWritten()) {
            response.setStatusCode(code);
        }
        return this;
    }

    @Override
    public ServerHttpResponse end() {
        if (!response.ended()) {
            if (response instanceof Http1xServerResponse) {
                // Http1xServerResponse correctly handles a null handler
                response.end((Handler<AsyncResult<Void>>) null);
            } else {
                // we don't know if other instances handle a null handler so just use the future form
                response.end();
            }
        }
        return this;
    }

    @Override
    public boolean headWritten() {
        return response.headWritten();
    }

    @Override
    public ServerHttpResponse end(byte[] data) {
        var buffer = VertxByteBufAllocator.POOLED_ALLOCATOR.directBuffer(data.length);
        buffer.writeBytes(data);
        response.end(new NoBoundChecksBuffer(buffer), null);
        return this;
    }

    @Override
    public ServerHttpResponse end(String data) {
        var buffer = VertxByteBufAllocator.POOLED_ALLOCATOR.directBuffer(ByteBufUtil.utf8MaxBytes(data.length()));
        buffer.writeCharSequence(data, CharsetUtil.UTF_8);
        response.end(new NoBoundChecksBuffer(buffer), null);
        return this;
    }

    @Override
    public ServerHttpResponse addResponseHeader(CharSequence name, CharSequence value) {
        response.headers().add(name, value);
        return this;
    }

    @Override
    public ServerHttpResponse setResponseHeader(CharSequence name, CharSequence value) {
        response.headers().set(name, value);
        return this;
    }

    @Override
    public ServerHttpResponse setResponseHeader(CharSequence name, Iterable<CharSequence> values) {
        response.headers().set(name, values);
        return this;
    }

    @Override
    public Iterable<Map.Entry<String, String>> getAllResponseHeaders() {
        return response.headers();
    }

    @Override
    public String getResponseHeader(String name) {
        return response.headers().get(name);
    }

    @Override
    public void removeResponseHeader(String name) {
        response.headers().remove(name);
    }

    @Override
    public boolean closed() {
        return response.ended() || response.closed();
    }

    @Override
    public ServerHttpResponse setChunked(boolean chunked) {
        response.setChunked(chunked);
        return this;
    }

    @Override
    public ServerHttpResponse write(byte[] data, Consumer<Throwable> asyncResultHandler) {
        response.write(Buffer.buffer(data), new Handler<AsyncResult<Void>>() {
            @Override
            public void handle(AsyncResult<Void> event) {
                if (event.failed()) {
                    asyncResultHandler.accept(event.cause());
                } else {
                    asyncResultHandler.accept(null);
                }
            }
        });
        return this;
    }

    @Override
    public CompletionStage<Void> write(byte[] data) {
        CompletableFuture<Void> ret = new CompletableFuture<>();
        response.write(Buffer.buffer(data), new Handler<AsyncResult<Void>>() {
            @Override
            public void handle(AsyncResult<Void> event) {
                if (event.failed()) {
                    ret.completeExceptionally(event.cause());
                } else {
                    ret.complete(null);
                }
            }
        });
        return ret;
    }

    @Override
    public ServerHttpResponse sendFile(String path, long offset, long length) {
        response.sendFile(path, offset, length);
        return this;
    }

    @Override
    public OutputStream createResponseOutputStream() {
        final ResteasyReactiveConfig config = getDeployment().getResteasyReactiveConfig();
        return new VertxOutputStream(
                new ResteasyVertxJavaIoContext(
                        context,
                        config.getMinChunkSize(),
                        config.getOutputBufferSize()));
    }

    @Override
    public void setPreCommitListener(Consumer<ResteasyReactiveRequestContext> task) {
        preCommitTask = task;
    }

    @Override
    public void handle(Void event) {
        if (preCommitTask != null) {
            preCommitTask.accept(this);
        }
    }

    @Override
    public ServerHttpResponse addDrainHandler(Runnable onDrain) {
        response.drainHandler(new Handler<Void>() {
            @Override
            public void handle(Void event) {
                onDrain.run();
            }
        });
        return this;
    }

    @Override
    public boolean isWriteQueueFull() {
        return response.writeQueueFull();
    }

    public HttpServerRequest vertxServerRequest() {
        return request;
    }

    public HttpServerResponse vertxServerResponse() {
        return response;
    }

    enum ContinueState {
        NONE,
        REQUIRED,
        SENT;
    }

    final class ResteasyVertxJavaIoContext extends VertxJavaIoContext {

        public ResteasyVertxJavaIoContext(RoutingContext context, int minChunkSize, int outputBufferSize) {
            super(context, minChunkSize, outputBufferSize);
        }

        @Override
        public Optional<String> getContentLength() {
            if (getRoutingContext().request().response().headers().contains(HttpHeaderNames.CONTENT_LENGTH)) {
                return Optional.empty();
            }
            final LazyResponse lazyResponse = VertxResteasyReactiveRequestContext.this.getResponse();
            if (!lazyResponse.isCreated()) {
                return Optional.empty();
            }
            MultivaluedMap<String, Object> responseHeaders = lazyResponse.get().getHeaders();
            if (responseHeaders != null) {
                // we need to make sure the content-length header is copied to Vert.x headers
                // otherwise we could run into a race condition: see https://github.com/quarkusio/quarkus/issues/26599
                Object contentLength = responseHeaders.getFirst(HttpHeaders.CONTENT_LENGTH);
                if (contentLength != null) {
                    return Optional.of(contentLength.toString());
                }
            }
            return Optional.empty();
        }

    }
}
