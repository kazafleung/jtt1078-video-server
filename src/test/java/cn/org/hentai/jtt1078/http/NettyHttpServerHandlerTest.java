package cn.org.hentai.jtt1078.http;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NettyHttpServerHandlerTest
{
    @Test
    public void servesMonitorPage()
    {
        EmbeddedChannel channel = new EmbeddedChannel(new NettyHttpServerHandler());
        try
        {
            channel.writeInbound(new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.GET, "/monitor"));

            FullHttpResponse response = channel.readOutbound();
            try
            {
                assertEquals("text/html; charset=utf-8",
                        response.headers().get(HttpHeaderNames.CONTENT_TYPE));
                assertTrue(response.content().toString(CharsetUtil.UTF_8)
                        .contains("JT/T 1078 CHANNEL MONITOR"));
            }
            finally
            {
                ReferenceCountUtil.release(response);
            }
        }
        finally
        {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void startsMonitorServerSentEventStreamWithLatestSnapshot()
    {
        EmbeddedChannel channel = new EmbeddedChannel(new NettyHttpServerHandler());
        try
        {
            channel.writeInbound(new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.GET, "/monitor/events"));

            HttpResponse response = channel.readOutbound();
            HttpContent retry = channel.readOutbound();
            HttpContent snapshot = channel.readOutbound();
            try
            {
                assertEquals("text/event-stream; charset=utf-8",
                        response.headers().get(HttpHeaderNames.CONTENT_TYPE));
                assertEquals("retry: 3000\n\n", retry.content().toString(CharsetUtil.UTF_8));
                String event = snapshot.content().toString(CharsetUtil.UTF_8);
                assertTrue(event.startsWith("event: channels\ndata: {\"generatedAt\":"));
                assertTrue(event.endsWith("\"channels\":[]}\n\n"));
            }
            finally
            {
                ReferenceCountUtil.release(response);
                ReferenceCountUtil.release(retry);
                ReferenceCountUtil.release(snapshot);
            }
        }
        finally
        {
            channel.finishAndReleaseAll();
        }
    }
}
