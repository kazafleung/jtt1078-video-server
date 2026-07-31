package cn.org.hentai.jtt1078.publisher;

import cn.org.hentai.jtt1078.codec.AudioCodec;
import cn.org.hentai.jtt1078.entity.Media;
import cn.org.hentai.jtt1078.entity.MediaEncoding;
import cn.org.hentai.jtt1078.flv.FlvEncoder;
import cn.org.hentai.jtt1078.flv.FlvHevcEncoder;
import cn.org.hentai.jtt1078.subscriber.RTMPPublisher;
import cn.org.hentai.jtt1078.subscriber.Subscriber;
import cn.org.hentai.jtt1078.subscriber.VideoSubscriber;
import cn.org.hentai.jtt1078.util.Configs;
import io.netty.channel.ChannelHandlerContext;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by matrixy on 2020/1/11.
 */
public class Channel {
    static Logger logger = LoggerFactory.getLogger(Channel.class);

    private static final int DEFAULT_MAX_VIDEO_FRAME_SIZE = 4 * 1024 * 1024;
    private static final byte[] FOUR_BYTE_START_CODE = { 0x00, 0x00, 0x00, 0x01 };

    private static final int PACKET_TYPE_ATOMIC = 0;
    private static final int PACKET_TYPE_FIRST = 1;
    private static final int PACKET_TYPE_LAST = 2;
    private static final int PACKET_TYPE_MIDDLE = 3;

    ConcurrentLinkedQueue<Subscriber> subscribers;
    RTMPPublisher rtmpPublisher;

    String tag;
    volatile boolean publishing;
    private final ByteArrayOutputStream videoFrameBuffer;
    private final int maxVideoFrameSize;
    private boolean assemblingVideoFrame;
    private int expectedVideoPacketSequence = -1;
    private long assemblingTimestamp = -1;
    private int assemblingPayloadType = -1;
    AudioCodec audioCodec;
    FlvEncoder flvEncoder;
    private long firstTimestamp = -1;
    private volatile MediaEncoding.Encoding videoEncoding = MediaEncoding.Encoding.UNKNOWN;
    private volatile int videoPayloadType = -1;
    private volatile long streamStartedAtMillis = -1;
    private volatile long lastVideoPacketTime = -1;
    private volatile long lastKeyframeTime = -1;
    private volatile long lastNoSubscriberTime = System.currentTimeMillis();

    private final AtomicLong videoPacketsTotal = new AtomicLong(0);
    private final AtomicLong videoPacketsInterval = new AtomicLong(0);
    private final AtomicLong audioPacketsTotal = new AtomicLong(0);
    private final AtomicLong audioPacketsInterval = new AtomicLong(0);
    private final AtomicLong completedFramesTotal = new AtomicLong(0);
    private final AtomicLong completedFramesInterval = new AtomicLong(0);

    private final AtomicLong droppedFramesTotal = new AtomicLong(0);
    private final AtomicLong droppedFramesInterval = new AtomicLong(0);
    private final AtomicLong droppedSequenceInterval = new AtomicLong(0);
    private final AtomicLong droppedOversizeInterval = new AtomicLong(0);
    private final AtomicLong droppedMalformedInterval = new AtomicLong(0);
    private final AtomicLong droppedUnsupportedInterval = new AtomicLong(0);
    private final AtomicLong droppedIncompleteInterval = new AtomicLong(0);

    private final AtomicLong uploadedVideoBytes = new AtomicLong(0);
    private final AtomicLong uploadedAudioBytes = new AtomicLong(0);
    private final AtomicInteger peakVideoBufferInterval = new AtomicInteger(0);
    private long lastUploadRateSampleNanos = System.nanoTime();

    public Channel(String tag) {
        this(tag, configuredMaxVideoFrameSize());
    }

    Channel(String tag, int maxVideoFrameSize) {
        this.tag = tag;
        this.subscribers = new ConcurrentLinkedQueue<Subscriber>();
        this.flvEncoder = null; // created lazily on first video packet based on codec
        this.maxVideoFrameSize = maxVideoFrameSize > 0
                ? maxVideoFrameSize
                : DEFAULT_MAX_VIDEO_FRAME_SIZE;
        this.videoFrameBuffer = new ByteArrayOutputStream(
                Math.min(this.maxVideoFrameSize, 2048 * 100));

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
        long nowNanos = System.nanoTime();
        long elapsedNanos = nowNanos - lastUploadRateSampleNanos;
        UploadRateSnapshot uploadRates = calculateUploadRates(elapsedNanos);
        lastUploadRateSampleNanos = nowNanos;

        long nowMillis = System.currentTimeMillis();
        long packetAgeMillis = lastVideoPacketTime < 0 ? -1 : Math.max(0, nowMillis - lastVideoPacketTime);
        long keyframeAgeSec = lastKeyframeTime < 0 ? -1 : Math.max(0, nowMillis - lastKeyframeTime) / 1000;
        long uptimeSec = streamStartedAtMillis < 0 ? 0 : Math.max(0, nowMillis - streamStartedAtMillis) / 1000;
        String state = !publishing ? "IDLE" : packetAgeMillis >= 0 && packetAgeMillis < 30_000
                ? "STREAMING" : "STALE";

        long videoPackets = videoPacketsInterval.getAndSet(0);
        long audioPackets = audioPacketsInterval.getAndSet(0);
        long frames = completedFramesInterval.getAndSet(0);
        long drops = droppedFramesInterval.getAndSet(0);
        long dropSequence = droppedSequenceInterval.getAndSet(0);
        long dropOversize = droppedOversizeInterval.getAndSet(0);
        long dropMalformed = droppedMalformedInterval.getAndSet(0);
        long dropUnsupported = droppedUnsupportedInterval.getAndSet(0);
        long dropIncomplete = droppedIncompleteInterval.getAndSet(0);
        double fps = elapsedNanos <= 0 ? 0.0 : frames * 1_000_000_000.0 / elapsedNanos;

        int currentBuffer = videoFrameBuffer.size();
        int peakBuffer = peakVideoBufferInterval.getAndSet(currentBuffer);
        int currentSubscriberQueue = 0;
        int peakSubscriberQueue = 0;
        for (Subscriber subscriber : subscribers) {
            currentSubscriberQueue = Math.max(currentSubscriberQueue, subscriber.getQueuedMessageCount());
            peakSubscriberQueue = Math.max(peakSubscriberQueue, subscriber.resetAndGetPeakQueuedMessageCount());
        }

        return String.format(Locale.ROOT,
                "tag=%s state=%s publishing=%b codec=%s pt=%d uptimeSec=%d "
                        + "subscribers=%d subscriberQueue=%d peakSubscriberQueue=%d "
                        + "packetAgeMs=%d keyframeAgeSec=%d "
                        + "videoPackets10s=%d audioPackets10s=%d packetsTotal=%d "
                        + "frames10s=%d framesTotal=%d fps=%.1f "
                        + "videoKbps=%d audioKbps=%d uploadKbps=%d "
                        + "drops10s=%d dropsTotal=%d dropSeq=%d dropOversize=%d "
                        + "dropMalformed=%d dropUnsupported=%d dropIncomplete=%d "
                        + "buffer=%d peakBuffer=%d maxBuffer=%d",
                tag, state, publishing, videoEncoding, videoPayloadType, uptimeSec,
                subscribers.size(), currentSubscriberQueue, peakSubscriberQueue,
                packetAgeMillis, keyframeAgeSec,
                videoPackets, audioPackets, videoPacketsTotal.get() + audioPacketsTotal.get(),
                frames, completedFramesTotal.get(), fps,
                toKbps(uploadRates.videoBitsPerSecond), toKbps(uploadRates.audioBitsPerSecond),
                toKbps(uploadRates.totalBitsPerSecond()),
                drops, droppedFramesTotal.get(), dropSequence, dropOversize,
                dropMalformed, dropUnsupported, dropIncomplete,
                currentBuffer, peakBuffer, maxVideoFrameSize);
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
        if (data != null)
            uploadedAudioBytes.addAndGet(data.length);
        audioPacketsTotal.incrementAndGet();
        audioPacketsInterval.incrementAndGet();
        if (audioCodec == null) {
            audioCodec = AudioCodec.getCodec(pt);
            logger.info("audio codec: {}", MediaEncoding.getEncoding(Media.Type.Audio, pt));
        }
        broadcastAudio(timestamp, audioCodec.toPCM(data));
    }

    public void writeVideo(long sequence, long timeoffset, int payloadType, int packetType, byte[] videoData) {
        if (videoData != null)
            uploadedVideoBytes.addAndGet(videoData.length);
        videoPacketsTotal.incrementAndGet();
        videoPacketsInterval.incrementAndGet();
        if (firstTimestamp == -1) {
            firstTimestamp = timeoffset;
            MediaEncoding.Encoding enc = MediaEncoding.getEncoding(Media.Type.Video, payloadType);
            videoEncoding = enc;
            videoPayloadType = payloadType;
            streamStartedAtMillis = System.currentTimeMillis();
            // Audio is optional and may arrive after the first video packet. Advertising an
            // audio track before one exists makes mpegts.js wait forever for audio metadata on
            // video-only devices. Start with a video-only FLV header; mpegts.js promotes the
            // stream to audio+video automatically when the first real audio tag arrives.
            flvEncoder = (enc == MediaEncoding.Encoding.H265)
                    ? new FlvHevcEncoder(true, false)
                    : new FlvEncoder(true, false);
            logger.info("video stream started: tag={} codec={}", tag, enc);
        }
        if (flvEncoder == null)
            return;
        this.publishing = true;
        lastVideoPacketTime = System.currentTimeMillis();

        if (videoData == null || videoData.length == 0) {
            recordDrop(droppedMalformedInterval);
            logger.warn("dropping empty video packet: tag={} sequence={} packetType={} pt={}",
                    tag, sequence, packetType, payloadType);
            return;
        }

        int normalizedSequence = (int) sequence & 0xffff;
        boolean sequenceContinuous = observePacketSequence(normalizedSequence);
        if (!sequenceContinuous
                && (packetType == PACKET_TYPE_MIDDLE || packetType == PACKET_TYPE_LAST))
            return;
        switch (packetType) {
            case PACKET_TYPE_ATOMIC:
                if (assemblingVideoFrame) {
                    recordDrop(droppedIncompleteInterval);
                    logger.warn("discarding incomplete frame before atomic packet: tag={} buffered={} sequence={}",
                            tag, videoFrameBuffer.size(), normalizedSequence);
                    resetVideoFrameAssembly();
                }
                processCompleteFrame(videoData, timeoffset, payloadType, packetType);
                break;

            case PACKET_TYPE_FIRST:
                if (assemblingVideoFrame) {
                    recordDrop(droppedIncompleteInterval);
                    logger.warn("discarding incomplete frame before new first packet: tag={} buffered={} sequence={}",
                            tag, videoFrameBuffer.size(), normalizedSequence);
                }
                resetVideoFrameAssembly();
                assemblingVideoFrame = true;
                assemblingTimestamp = timeoffset;
                assemblingPayloadType = payloadType;
                expectedVideoPacketSequence = nextSequence(normalizedSequence);
                appendVideoFragment(videoData, normalizedSequence, packetType, payloadType);
                break;

            case PACKET_TYPE_MIDDLE:
            case PACKET_TYPE_LAST:
                if (!validateContinuation(normalizedSequence, timeoffset, payloadType, packetType))
                    return;
                if (!appendVideoFragment(videoData, normalizedSequence, packetType, payloadType))
                    return;
                expectedVideoPacketSequence = nextSequence(normalizedSequence);
                if (packetType == PACKET_TYPE_LAST) {
                    byte[] frame = videoFrameBuffer.toByteArray();
                    resetVideoFrameAssembly();
                    processCompleteFrame(frame, timeoffset, payloadType, packetType);
                }
                break;

            default:
                recordDrop(droppedMalformedInterval);
                logger.warn("dropping video packet with unknown packet type: tag={} sequence={} packetType={} pt={}",
                        tag, normalizedSequence, packetType, payloadType);
                resetVideoFrameAssembly();
                break;
        }
    }

    /**
     * Backwards-compatible entry point for callers that already provide one complete frame.
     */
    public void writeVideo(long sequence, long timeoffset, int payloadType, byte[] videoData) {
        writeVideo(sequence, timeoffset, payloadType, PACKET_TYPE_ATOMIC, videoData);
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
        resetVideoFrameAssembly();
        for (Iterator<Subscriber> itr = subscribers.iterator(); itr.hasNext();) {
            Subscriber subscriber = itr.next();
            subscriber.close();
            itr.remove();
        }
        if (rtmpPublisher != null)
            rtmpPublisher.close();
    }

    private boolean appendVideoFragment(byte[] data, int sequence, int packetType, int payloadType) {
        if (data.length > maxVideoFrameSize - videoFrameBuffer.size()) {
            recordDrop(droppedOversizeInterval);
            logger.warn(
                    "dropping oversized video frame: tag={} used={} incoming={} max={} sequence={} packetType={} pt={}",
                    tag, videoFrameBuffer.size(), data.length, maxVideoFrameSize,
                    sequence, packetType, payloadType);
            resetVideoFrameAssembly();
            return false;
        }
        videoFrameBuffer.write(data, 0, data.length);
        peakVideoBufferInterval.accumulateAndGet(videoFrameBuffer.size(), Math::max);
        return true;
    }

    private boolean validateContinuation(int sequence, long timestamp, int payloadType, int packetType) {
        if (!assemblingVideoFrame) {
            recordDrop(droppedMalformedInterval);
            logger.warn("dropping video continuation without first packet: tag={} sequence={} packetType={} pt={}",
                    tag, sequence, packetType, payloadType);
            return false;
        }
        if (timestamp != assemblingTimestamp || payloadType != assemblingPayloadType) {
            recordDrop(droppedIncompleteInterval);
            logger.warn(
                    "dropping discontinuous video frame: tag={} sequence={} packetType={} timestamp={}/{} pt={}/{} buffered={}",
                    tag, sequence, packetType, assemblingTimestamp, timestamp,
                    assemblingPayloadType, payloadType, videoFrameBuffer.size());
            resetVideoFrameAssembly();
            return false;
        }
        return true;
    }

    boolean observePacketSequence(long sequence) {
        if (!assemblingVideoFrame)
            return true;

        int normalizedSequence = (int) sequence & 0xffff;
        if (normalizedSequence != expectedVideoPacketSequence) {
            recordDrop(droppedSequenceInterval);
            logger.warn(
                    "dropping video frame with missing or out-of-order packet: tag={} expectedSequence={} actualSequence={} buffered={}",
                    tag, expectedVideoPacketSequence, normalizedSequence, videoFrameBuffer.size());
            resetVideoFrameAssembly();
            return false;
        }
        expectedVideoPacketSequence = nextSequence(normalizedSequence);
        return true;
    }

    private void processCompleteFrame(byte[] frame, long timestamp, int payloadType, int packetType) {
        if (frame.length > maxVideoFrameSize) {
            recordDrop(droppedOversizeInterval);
            logger.warn(
                    "dropping oversized atomic video frame: tag={} length={} max={} packetType={} pt={}",
                    tag, frame.length, maxVideoFrameSize, packetType, payloadType);
            return;
        }

        MediaEncoding.Encoding encoding = MediaEncoding.getEncoding(Media.Type.Video, payloadType);
        List<byte[]> nalus = extractNalus(frame, encoding);
        if (nalus.isEmpty()) {
            recordDrop(droppedUnsupportedInterval);
            logger.warn("dropping video frame with unsupported NAL framing: tag={} length={} packetType={} pt={}",
                    tag, frame.length, packetType, payloadType);
            return;
        }

        completedFramesTotal.incrementAndGet();
        completedFramesInterval.incrementAndGet();

        for (byte[] nalu : nalus) {
            if (isKeyframe(nalu, encoding))
                lastKeyframeTime = System.currentTimeMillis();
            try {
                byte[] flvTag = this.flvEncoder.write(nalu, (int) (timestamp - firstTimestamp));
                if (flvTag != null)
                    broadcastVideo(timestamp, flvTag);
            }
            catch (RuntimeException ex) {
                recordDrop(droppedMalformedInterval);
                logger.warn("dropping invalid video NAL and resetting encoder: tag={} naluLength={} pt={}",
                        tag, nalu.length, payloadType, ex);
                resetFlvEncoder(encoding);
                break;
            }
        }
    }

    static List<byte[]> extractNalus(byte[] frame, MediaEncoding.Encoding encoding) {
        List<byte[]> annexBNalus = extractAnnexBNalus(frame, encoding);
        if (!annexBNalus.isEmpty())
            return annexBNalus;

        List<byte[]> lengthPrefixedNalus = extractLengthPrefixedNalus(frame, encoding);
        if (!lengthPrefixedNalus.isEmpty())
            return lengthPrefixedNalus;

        List<byte[]> rawNalu = new ArrayList<byte[]>(1);
        if (isValidNalHeader(frame, 0, frame.length, encoding))
            rawNalu.add(withFourByteStartCode(frame, 0, frame.length));
        return rawNalu;
    }

    private static List<byte[]> extractAnnexBNalus(byte[] frame, MediaEncoding.Encoding encoding) {
        List<byte[]> nalus = new ArrayList<byte[]>();
        StartCode current = findStartCode(frame, 0);
        if (current == null)
            return nalus;

        while (current != null) {
            int naluOffset = current.offset + current.length;
            StartCode next = findStartCode(frame, naluOffset);
            int naluEnd = next == null ? frame.length : next.offset;
            while (naluEnd > naluOffset && frame[naluEnd - 1] == 0x00)
                naluEnd--;

            int naluLength = naluEnd - naluOffset;
            if (isValidNalHeader(frame, naluOffset, naluLength, encoding))
                nalus.add(withFourByteStartCode(frame, naluOffset, naluLength));
            current = next;
        }
        return nalus;
    }

    private static List<byte[]> extractLengthPrefixedNalus(byte[] frame, MediaEncoding.Encoding encoding) {
        List<byte[]> nalus = new ArrayList<byte[]>();
        int offset = 0;
        while (offset + 4 <= frame.length) {
            long naluLength = ((long) (frame[offset] & 0xff) << 24)
                    | ((long) (frame[offset + 1] & 0xff) << 16)
                    | ((long) (frame[offset + 2] & 0xff) << 8)
                    | (long) (frame[offset + 3] & 0xff);
            offset += 4;
            if (naluLength <= 0 || naluLength > frame.length - offset)
                return new ArrayList<byte[]>();
            int length = (int) naluLength;
            if (!isValidNalHeader(frame, offset, length, encoding))
                return new ArrayList<byte[]>();
            nalus.add(withFourByteStartCode(frame, offset, length));
            offset += length;
        }
        if (offset != frame.length)
            return new ArrayList<byte[]>();
        return nalus;
    }

    private static boolean isValidNalHeader(byte[] data, int offset, int length,
            MediaEncoding.Encoding encoding) {
        if (length <= 0 || offset < 0 || offset + length > data.length)
            return false;

        if (encoding == MediaEncoding.Encoding.H265) {
            if (length < 2 || (data[offset] & 0x80) != 0)
                return false;
            return (data[offset + 1] & 0x07) != 0;
        }

        int naluType = data[offset] & 0x1f;
        return (data[offset] & 0x80) == 0 && naluType > 0 && naluType <= 23;
    }

    private static byte[] withFourByteStartCode(byte[] data, int offset, int length) {
        byte[] normalized = new byte[FOUR_BYTE_START_CODE.length + length];
        System.arraycopy(FOUR_BYTE_START_CODE, 0, normalized, 0, FOUR_BYTE_START_CODE.length);
        System.arraycopy(data, offset, normalized, FOUR_BYTE_START_CODE.length, length);
        return normalized;
    }

    private static StartCode findStartCode(byte[] data, int fromIndex) {
        for (int i = Math.max(0, fromIndex); i + 2 < data.length; i++) {
            if (i + 3 < data.length
                    && data[i] == 0x00 && data[i + 1] == 0x00
                    && data[i + 2] == 0x00 && data[i + 3] == 0x01)
                return new StartCode(i, 4);
            if (data[i] == 0x00 && data[i + 1] == 0x00 && data[i + 2] == 0x01)
                return new StartCode(i, 3);
        }
        return null;
    }

    private void resetVideoFrameAssembly() {
        videoFrameBuffer.reset();
        assemblingVideoFrame = false;
        expectedVideoPacketSequence = -1;
        assemblingTimestamp = -1;
        assemblingPayloadType = -1;
    }

    private void resetFlvEncoder(MediaEncoding.Encoding encoding) {
        flvEncoder = encoding == MediaEncoding.Encoding.H265
                ? new FlvHevcEncoder(true, false)
                : new FlvEncoder(true, false);
    }

    private void recordDrop(AtomicLong reasonCounter) {
        droppedFramesTotal.incrementAndGet();
        droppedFramesInterval.incrementAndGet();
        reasonCounter.incrementAndGet();
    }

    private static boolean isKeyframe(byte[] nalu, MediaEncoding.Encoding encoding) {
        if (nalu == null || nalu.length <= 4)
            return false;
        if (encoding == MediaEncoding.Encoding.H265) {
            int naluType = (nalu[4] >> 1) & 0x3f;
            return naluType >= 16 && naluType <= 21;
        }
        return (nalu[4] & 0x1f) == 5;
    }

    int bufferedVideoBytes() {
        return videoFrameBuffer.size();
    }

    boolean isAssemblingVideoFrame() {
        return assemblingVideoFrame;
    }

    UploadRateSnapshot calculateUploadRates(long elapsedNanos) {
        long videoBytes = uploadedVideoBytes.getAndSet(0);
        long audioBytes = uploadedAudioBytes.getAndSet(0);
        return new UploadRateSnapshot(
                calculateBitsPerSecond(videoBytes, elapsedNanos),
                calculateBitsPerSecond(audioBytes, elapsedNanos));
    }

    private static long calculateBitsPerSecond(long bytes, long elapsedNanos) {
        if (elapsedNanos <= 0)
            return 0;
        return Math.round(bytes * 8.0 * 1_000_000_000.0 / elapsedNanos);
    }

    private static long toKbps(long bitsPerSecond) {
        return Math.round(bitsPerSecond / 1000.0);
    }

    private static int nextSequence(int sequence) {
        return (sequence + 1) & 0xffff;
    }

    private static int configuredMaxVideoFrameSize() {
        try {
            return Configs.getInt("video.max-frame-size", DEFAULT_MAX_VIDEO_FRAME_SIZE);
        }
        catch (NumberFormatException ex) {
            logger.warn("invalid video.max-frame-size; using default {}", DEFAULT_MAX_VIDEO_FRAME_SIZE);
            return DEFAULT_MAX_VIDEO_FRAME_SIZE;
        }
    }

    private static final class StartCode {
        final int offset;
        final int length;

        StartCode(int offset, int length) {
            this.offset = offset;
            this.length = length;
        }
    }

    static final class UploadRateSnapshot {
        final long videoBitsPerSecond;
        final long audioBitsPerSecond;

        UploadRateSnapshot(long videoBitsPerSecond, long audioBitsPerSecond) {
            this.videoBitsPerSecond = videoBitsPerSecond;
            this.audioBitsPerSecond = audioBitsPerSecond;
        }

        long totalBitsPerSecond() {
            return videoBitsPerSecond + audioBitsPerSecond;
        }
    }
}
