package cn.org.hentai.jtt1078.publisher;

import cn.org.hentai.jtt1078.codec.AudioCodec;
import cn.org.hentai.jtt1078.entity.Media;
import cn.org.hentai.jtt1078.entity.MediaEncoding;
import cn.org.hentai.jtt1078.flv.FlvEncoder;
import cn.org.hentai.jtt1078.flv.FlvHevcEncoder;
import cn.org.hentai.jtt1078.subscriber.RTMPPublisher;
import cn.org.hentai.jtt1078.subscriber.Subscriber;
import cn.org.hentai.jtt1078.subscriber.VideoSubscriber;
import cn.org.hentai.jtt1078.util.ByteHolder;
import cn.org.hentai.jtt1078.util.Configs;
import io.netty.channel.ChannelHandlerContext;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Created by matrixy on 2020/1/11.
 */
public class Channel
{
    static Logger logger = LoggerFactory.getLogger(Channel.class);

    private static final byte[] NALU_START_CODE = {0, 0, 0, 1};

    ConcurrentLinkedQueue<Subscriber> subscribers;
    RTMPPublisher rtmpPublisher;

    String tag;
    boolean publishing;
    ByteHolder buffer;
    AudioCodec audioCodec;
    FlvEncoder flvEncoder;
    private long firstTimestamp = -1;

    public Channel(String tag)
    {
        this.tag = tag;
        this.subscribers = new ConcurrentLinkedQueue<Subscriber>();
        this.flvEncoder = null;  // created lazily on first video packet based on codec
        this.buffer = new ByteHolder(1024 * 1024);

        if (StringUtils.isEmpty(Configs.get("rtmp.url")) == false)
        {
            rtmpPublisher = new RTMPPublisher(tag);
            rtmpPublisher.start();
        }
    }

    public boolean isPublishing()
    {
        return publishing;
    }

    public Subscriber subscribe(ChannelHandlerContext ctx)
    {
        logger.info("channel: {} -> {}, subscriber: {}", Long.toHexString(hashCode() & 0xffffffffL), tag, ctx.channel().remoteAddress().toString());

        Subscriber subscriber = new VideoSubscriber(this.tag, ctx);
        this.subscribers.add(subscriber);
        return subscriber;
    }

    public void writeAudio(long timestamp, int pt, byte[] data)
    {
        if (audioCodec == null)
        {
            audioCodec = AudioCodec.getCodec(pt);
            logger.info("audio codec: {}", MediaEncoding.getEncoding(Media.Type.Audio, pt));
        }
        broadcastAudio(timestamp, audioCodec.toPCM(data));
    }

    public void writeVideo(long sequence, long timeoffset, int payloadType, byte[] h264)
    {
        if (firstTimestamp == -1)
        {
            firstTimestamp = timeoffset;
            MediaEncoding.Encoding enc = MediaEncoding.getEncoding(Media.Type.Video, payloadType);
            flvEncoder = (enc == MediaEncoding.Encoding.H265)
                    ? new FlvHevcEncoder(true, true)
                    : new FlvEncoder(true, true);
            logger.info("video stream started: tag={} codec={}", tag, enc);
        }
        if (flvEncoder == null) return;
        this.publishing = true;
        // Normalize to 4-byte start code. JTT1078 devices may send raw NAL units
        // without any start code, or with a 3-byte start code. readNalu() uses
        // 4-byte start codes as NALU delimiters, and FlvEncoder expects nalu[4]
        // to be the NAL header byte (requires a preceding 4-byte start code).
        int scLen = 0;
        if (h264.length >= 4 && h264[0] == 0 && h264[1] == 0 && h264[2] == 0 && h264[3] == 1) {
            scLen = 4;
        } else if (h264.length >= 3 && h264[0] == 0 && h264[1] == 0 && h264[2] == 1) {
            scLen = 3;
        }
        this.buffer.write(NALU_START_CODE);
        this.buffer.write(h264, scLen, h264.length - scLen);
        while (true)
        {
            byte[] nalu = readNalu();
            if (nalu == null) break;
            if (nalu.length < 4) continue;

            byte[] flvTag = this.flvEncoder.write(nalu, (int) (timeoffset - firstTimestamp));
            if (flvTag == null) continue;

            broadcastVideo(timeoffset, flvTag);
        }
    }

    public void broadcastVideo(long timeoffset, byte[] flvTag)
    {
        for (Subscriber subscriber : subscribers)
        {
            subscriber.onVideoData(timeoffset, flvTag, flvEncoder);
        }
    }

    public void broadcastAudio(long timeoffset, byte[] flvTag)
    {
        for (Subscriber subscriber : subscribers)
        {
            subscriber.onAudioData(timeoffset, flvTag, flvEncoder);
        }
    }

    public void unsubscribe(long watcherId)
    {
        for (Iterator<Subscriber> itr = subscribers.iterator(); itr.hasNext(); )
        {
            Subscriber subscriber = itr.next();
            if (subscriber.getId() == watcherId)
            {
                itr.remove();
                subscriber.close();
                return;
            }
        }
    }

    public void close()
    {
        for (Iterator<Subscriber> itr = subscribers.iterator(); itr.hasNext(); )
        {
            Subscriber subscriber = itr.next();
            subscriber.close();
            itr.remove();
        }
        if (rtmpPublisher != null) rtmpPublisher.close();
    }

    private byte[] readNalu()
    {
        for (int i = 0; i < buffer.size() - 3; i++)
        {
            int a = buffer.get(i + 0) & 0xff;
            int b = buffer.get(i + 1) & 0xff;
            int c = buffer.get(i + 2) & 0xff;
            int d = buffer.get(i + 3) & 0xff;
            if (a == 0x00 && b == 0x00 && c == 0x00 && d == 0x01)
            {
                if (i == 0) continue;
                byte[] nalu = new byte[i];
                buffer.sliceInto(nalu, i);
                return nalu;
            }
        }
        return null;
    }
}
