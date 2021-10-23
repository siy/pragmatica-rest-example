package org.pfj.http.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.epoll.Epoll;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueServerSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.logging.Log4J2LoggerFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.pfj.lang.Causes;
import org.pfj.lang.Promise;
import org.pfj.lang.Result;

import static org.pfj.http.server.Utils.normalize;

public class WebServer {
    static {
        InternalLoggerFactory.setDefaultFactory(Log4J2LoggerFactory.INSTANCE);
    }

    private static final Logger log = LogManager.getLogger(WebServer.class);
    private static final int DEFAULT_PORT = 8000;

    private final EndpointTable endpointTable;
    private final int port;
    private CauseMapper causeMapper = CauseMapper::defaultConverter;
    private ObjectMapper objectMapper = new ObjectMapper();

    private WebServer(int port, EndpointTable endpointTable) {
        this.port = port;
        this.endpointTable = endpointTable;
    }

    public static WebServer create(int port, RouteSource... routes) {
        return new WebServer(port, EndpointTable.with(routes));
    }

    public static WebServer create(RouteSource... routes) {
        return create(DEFAULT_PORT, routes);
    }

    public WebServer withCauseMapper(CauseMapper causeMapper) {
        this.causeMapper = causeMapper;
        return this;
    }

    public WebServer withObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        return this;
    }

    public Promise<Void> start() throws InterruptedException {
        log.info("Starting WebServer...");

        EventLoopGroup bossGroup;
        EventLoopGroup workerGroup;
        Class<? extends ServerChannel> serverChannelClass;

        if (Epoll.isAvailable()) {
            bossGroup = new EpollEventLoopGroup(1);
            workerGroup = new EpollEventLoopGroup();
            serverChannelClass = EpollServerSocketChannel.class;
            log.info("Using epoll native transport");
        } else if (KQueue.isAvailable()) {
            bossGroup = new KQueueEventLoopGroup(1);
            workerGroup = new KQueueEventLoopGroup();
            serverChannelClass = KQueueServerSocketChannel.class;
            log.info("Using kqueue native transport");
        } else {
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();
            serverChannelClass = NioServerSocketChannel.class;
            log.info("Using NIO transport");
        }

        endpointTable.print();

        var promise = Promise.<Void>promise()
            .onResultDo(() -> {
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
            });

        try {
            new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(serverChannelClass)
                .childOption(ChannelOption.SO_SNDBUF, 1024 * 1024)
                .childOption(ChannelOption.SO_RCVBUF, 32 * 1024)
                .handler(new LoggingHandler(LogLevel.DEBUG))
                .childHandler(new WebServerInitializer())
                .bind(port)
                .sync()
                .channel()
                .closeFuture()
                .addListener(future -> decode(promise, future));
        } catch (InterruptedException e) {
            //In rare cases when .sync() will be interrupted, fail with error
            promise.resolve(WebError.SERVICE_UNAVAILABLE.result());
        }

        return promise;
    }

    protected CauseMapper causeMapper() {
        return causeMapper;
    }

    protected ObjectMapper objectMapper() {
        return objectMapper;
    }

    private void decode(Promise<Void> promise, Future<? super Void> future) {
        promise.resolve(future.isSuccess()
            ? Result.success(null)
            : Causes.fromThrowable(future.cause()).result());
    }

    private class WebServerInitializer extends ChannelInitializer<SocketChannel> {
        public static final int MAX_CONTENT_LENGTH = 10 * 1024 * 1024;

        @Override
        public void initChannel(SocketChannel ch) {
            ch.pipeline()
                .addLast("decoder", new HttpRequestDecoder())
                .addLast("aggregator", new HttpObjectAggregator(MAX_CONTENT_LENGTH))
                .addLast("encoder", new HttpResponseEncoder())
                .addLast("handler", new WebServerHandler());
        }
    }

    private class WebServerHandler extends SimpleChannelInboundHandler<Object> {
        /**
         * Handles a new message.
         *
         * @param ctx The channel context.
         * @param msg The HTTP request message.
         */
        @Override
        public void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof FullHttpRequest request)) {
                return;
            }

            if (HttpUtil.is100ContinueExpected(request)) {
                ctx.write(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE));
            }

            var context = RequestContext.from(ctx, request, WebServer.this);

            endpointTable.findRoute(request.method(), normalize(request.uri()))
                .whenEmpty(() -> context.sendFailure(WebError.NOT_FOUND))
                .whenPresent(route -> context.setRoute(route).invokeAndRespond());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            ctx.flush();
        }
    }
}
