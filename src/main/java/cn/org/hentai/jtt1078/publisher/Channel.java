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
public class Channel {
    static Logger logger = LoggerFactory.getLogger(Channel.class);

    ConcurrentLinkedQueue<Subscriber> subscribers;
    RTMPPublisher rtmpPublisher;

    String tag;
    boolean publishing;
    ByteHolder buffer;
    AudioCodec audioCodec;
    FlvEncoder flvEncoder;
    private long firstTimestamp = -1;
    private long lastVideoPacketTime = -1;
    private long videoPacketCount = 0;
    private long lastNoSubscriberTime = System.currentTimeMillis();

    public Channel(String tag) {
        this.tag = tag;
        this.subscribers = new ConcurrentLinkedQueue<Subscriber>();
        this.flvEncoder = null; // created lazily on first video packet based on codec
        this.buffer = new ByteHolder(2048 * 100);

        if (StringUtils.isEmpty(Configs.get("rtmp.url")) == false) {
            rtmpPublisher = new RTMPPublisher(tag);
            rtmpPublisher.start();
        }
    }

    public boolean isPublishing() {
        return publishing;
    }

    public String getTag() {
        return tag;
    }

    /** True when video packets have arrived within the last 30 seconds. */
    public boolean isActivelyPublishing() {
        return publishing && lastVideoPacketTime > 0
                && (System.currentTimeMillis() - lastVideoPacketTime) < 30_000;
    }

    public String statusInfo() {
        long idleSec = lastVideoPacketTime < 0 ? -1 : (System.currentTimeMillis() - lastVideoPacketTime) / 1000;
        return String.format("tag=%s publishing=%b subscribers=%d bufferUsed=%d packets=%d idleSec=%d",
                tag, publishing, subscribers.size(), buffer.size(), videoPacketCount, idleSec);
    }

    public long getLastNoSubscriberTime() {
        return lastNoSubscriberTime;
    }

    public Subscriber subscribe(ChannelHandlerContext ctx) {
        logger.info("channel: {} -> {}, subscriber: {}", Long.toHexString(hashCode() & 0xffffffffL), tag,
                ctx.channel().remoteAddress().toString());

        this.lastNoSubscriberTime = -1;
        Subscriber subscriber = new VideoSubscriber(this.tag, ctx);
        this.subscribers.add(subscriber);
        return subscriber;
    }

    public void writeAudio(long timestamp, int pt, byte[] data) {
        if (audioCodec == null) {
            audioCodec = AudioCodec.getCodec(pt);
            logger.info("audio codec: {}", MediaEncoding.getEncoding(Media.Type.Audio, pt));
        }
        broadcastAudio(timestamp, audioCodec.toPCM(data));
    }

    public void writeVideo(long sequence, long timeoffset, int payloadType, byte[] h264) {
        if (firstTimestamp == -1) {
            firstTimestamp = timeoffset;
            MediaEncoding.Encoding enc = MediaEncoding.getEncoding(Media.Type.Video, payloadType);
            flvEncoder = (enc == MediaEncoding.Encoding.H265)
                    ? new FlvHevcEncoder(true, true)
                    : new FlvEncoder(true, true);
            logger.info("video stream started: tag={} codec={}", tag, enc);
        }
        if (flvEncoder == null)
            return;
        this.publishing = true;
        videoPacketCount++;
        lastVideoPacketTime = System.currentTimeMillis();
        this.buffer.write(h264);
        while (true) {
            byte[] nalu = readNalu();
            if (nalu == null)
                break;
            if (nalu.length < 4)
                continue;

            byte[] flvTag = this.flvEncoder.write(nalu, (int) (timeoffset - firstTimestamp));
            if (flvTag == null)
                continue;

            broadcastVideo(timeoffset, flvTag);
        }
    }

    public void broadcastVideo(long timeoffset, byte[] flvTag) {
        for (Subscriber subscriber : subscribers) {
            subscriber.onVideoData(timeoffset, flvTag, flvEncoder);
        }
    }

    public void broadcastAudio(long timeoffset, byte[] flvTag) {
        for (Subscriber subscriber : subscribers) {
            subscriber.onAudioData(timeoffset, flvTag, flvEncoder);
        }
    }

    public void unsubscribe(long watcherId) {
        for (Iterator<Subscriber> itr = subscribers.iterator(); itr.hasNext();) {
            Subscriber subscriber = itr.next();
            if (subscriber.getId() == watcherId) {
                itr.remove();
                subscriber.close();
                if (subscribers.isEmpty()) {
                    lastNoSubscriberTime = System.currentTimeMillis();
                }
                return;
            }
        }
    }

    public void close() {
        for (Iterator<Subscriber> itr = subscribers.iterator(); itr.hasNext();) {
            Subscriber subscriber = itr.next();
            subscriber.close();
            itr.remove();
        }
        if (rtmpPublisher != null)
            rtmpPublisher.close();
    }

    private byte[] readNalu() {
        for (int i = 0; i < buffer.size() - 3; i++) {
            int a = buffer.get(i + 0) & 0xff;
            int b = buffer.get(i + 1) & 0xff;
            int c = buffer.get(i + 2) & 0xff;
            int d = buffer.get(i + 3) & 0xff;
            if (a == 0x00 && b == 0x00 && c == 0x00 && d == 0x01) {
                if (i == 0)
                    continue;
                byte[] nalu = new byte[i];
                buffer.sliceInto(nalu, i);
                return nalu;
            }
        }
        return null;
    }
}
