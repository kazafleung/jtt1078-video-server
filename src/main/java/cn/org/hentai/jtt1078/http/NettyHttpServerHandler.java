package cn.org.hentai.jtt1078.http;

import cn.org.hentai.jtt1078.entity.Media;
import cn.org.hentai.jtt1078.publisher.PublishManager;
import cn.org.hentai.jtt1078.server.Session;
import cn.org.hentai.jtt1078.util.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * Created by matrixy on 2019/8/13.
 */
public class NettyHttpServerHandler extends ChannelInboundHandlerAdapter
{
    static Logger logger = LoggerFactory.getLogger(NettyHttpServerHandler.class);
    static final byte[] HTTP_403_DATA = "<h1>403 Forbidden</h1><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding--><!--padding-->".getBytes();
    static final String HEADER_ENCODING = "ISO-8859-1";

    private static final AttributeKey<Session> SESSION_KEY = AttributeKey.valueOf("session");
    private static final AttributeKey<Consumer<String>> MONITOR_LISTENER_KEY =
            AttributeKey.valueOf("monitor-listener");

    @Override
    public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception
    {
        try {
            FullHttpRequest fhr = (FullHttpRequest) msg;
            String uri = new QueryStringDecoder(fhr.uri()).path();
            Packet resp = Packet.create(1024);
            // uri的第二段，就是通道标签
            if (uri.startsWith("/video/"))
            {
                String tag = uri.substring("/video/".length());

                resp.addBytes("HTTP/1.1 200 OK\r\n".getBytes(HEADER_ENCODING));
                resp.addBytes("Connection: keep-alive\r\n".getBytes(HEADER_ENCODING));
                resp.addBytes("Content-Type: video/x-flv\r\n".getBytes(HEADER_ENCODING));
                resp.addBytes("Transfer-Encoding: chunked\r\n".getBytes(HEADER_ENCODING));
                resp.addBytes("Cache-Control: no-cache\r\n".getBytes(HEADER_ENCODING));
                resp.addBytes("Access-Control-Allow-Origin: *\r\n".getBytes(HEADER_ENCODING));
                resp.addBytes("Access-Control-Allow-Credentials: true\r\n".getBytes(HEADER_ENCODING));
                resp.addBytes("\r\n".getBytes(HEADER_ENCODING));

                ctx.writeAndFlush(resp.getBytes()).await();

                // 订阅视频数据
                long wid = PublishManager.getInstance().subscribe(tag, Media.Type.Video, ctx).getId();
                setSession(ctx, new Session().set("subscriber-id", wid).set("tag", tag));
            }
            else if ((uri.equals("/monitor") || uri.equals("/monitor/")) && isMonitorEnabled())
            {
                responseHTMLFile("/monitor.html", ctx);
            }
            else if (uri.equals("/monitor/events") && isMonitorEnabled())
            {
                startMonitorEventStream(ctx);
            }
            else if (uri.equals("/test/multimedia"))
            {
                responseHTMLFile("/multimedia.html", ctx);
            }
            else
            {
                ByteBuf body = Unpooled.buffer(HTTP_403_DATA.length);
                body.writeBytes(HTTP_403_DATA);
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN, body);
                response.headers().add(HttpHeaderNames.CONTENT_LENGTH, HTTP_403_DATA.length);
                ctx.writeAndFlush(response).await();
            }
        }
        finally {
            ReferenceCountUtil.release(msg);
        }
    }

    private void startMonitorEventStream(final ChannelHandlerContext ctx)
    {
        HttpResponse response = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream; charset=utf-8");
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache, no-transform");
        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
        response.headers().set("X-Accel-Buffering", "no");
        ctx.write(response);
        ctx.writeAndFlush(new DefaultHttpContent(
                Unpooled.copiedBuffer("retry: 3000\n\n", StandardCharsets.UTF_8)));

        final Consumer<String> listener = snapshot -> {
            if (!ctx.channel().isActive())
                return;
            ctx.executor().execute(() -> writeMonitorEvent(ctx, snapshot));
        };
        ctx.channel().attr(MONITOR_LISTENER_KEY).set(listener);
        String latestSnapshot = PublishManager.getInstance().addMonitorListener(listener);
        writeMonitorEvent(ctx, latestSnapshot);
    }

    private static void writeMonitorEvent(ChannelHandlerContext ctx, String snapshot)
    {
        if (!ctx.channel().isActive())
            return;
        String event = "event: channels\ndata: " + snapshot + "\n\n";
        ctx.writeAndFlush(new DefaultHttpContent(
                Unpooled.copiedBuffer(event, StandardCharsets.UTF_8)));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception
    {
        super.channelInactive(ctx);
        Session session = getSession(ctx);
        if (session != null && session.has("subscriber-id") && session.has("tag"))
        {
            String tag = session.get("tag");
            Long wid = session.get("subscriber-id");
            PublishManager.getInstance().unsubscribe(tag, wid);
        }
        Consumer<String> listener = ctx.channel().attr(MONITOR_LISTENER_KEY).getAndSet(null);
        PublishManager.getInstance().removeMonitorListener(listener);
    }

    // 响应静态文件内容
    private void responseHTMLFile(String htmlFilePath, ChannelHandlerContext ctx)
    {
        byte[] fileData = FileUtils.read(NettyHttpServerHandler.class.getResourceAsStream(htmlFilePath));
        ByteBuf body = Unpooled.buffer(fileData.length);
        body.writeBytes(fileData);
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(200), body);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, fileData.length);
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store");
        ctx.writeAndFlush(response);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception
    {
        ctx.flush();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception
    {
        ctx.close();
        logger.warn("HTTP connection failed: {}", ctx.channel().remoteAddress(), cause);
    }

    public final void setSession(ChannelHandlerContext context, Session session)
    {
        context.channel().attr(SESSION_KEY).set(session);
    }

    public final Session getSession(ChannelHandlerContext context)
    {
        Attribute<Session> attr = context.channel().attr(SESSION_KEY);
        if (null == attr) return null;
        else return attr.get();
    }

    private static boolean isMonitorEnabled()
    {
        return !"off".equalsIgnoreCase(Configs.get("monitor.enabled"));
    }
}
