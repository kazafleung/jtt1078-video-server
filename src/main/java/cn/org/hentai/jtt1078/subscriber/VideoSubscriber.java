package cn.org.hentai.jtt1078.subscriber;

import cn.org.hentai.jtt1078.codec.MP3Encoder;
import cn.org.hentai.jtt1078.flv.AudioTag;
import cn.org.hentai.jtt1078.flv.FlvAudioTagEncoder;
import cn.org.hentai.jtt1078.flv.FlvEncoder;
import cn.org.hentai.jtt1078.util.ByteBufUtils;
import cn.org.hentai.jtt1078.util.FLVUtils;
import cn.org.hentai.jtt1078.util.HttpChunk;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Created by matrixy on 2020/1/13.
 */
public class VideoSubscriber extends Subscriber
{
    static Logger logger = LoggerFactory.getLogger(VideoSubscriber.class);

    private long videoTimestamp = 0;
    private long audioTimestamp = 0;
    private long lastVideoFrameTimeOffset = 0;
    private long lastAudioFrameTimeOffset = 0;
    private boolean videoHeaderSent = false;
    // Wait for the first live keyframe so subscribers always start at a clean GOP boundary.
    // Sending mid-GOP P-frames to a new subscriber causes MSE decode errors because
    // the reference frames (earlier P-frames in the same GOP) were never sent.
    private boolean waitingForKeyframe = true;

    public VideoSubscriber(String tag, ChannelHandlerContext ctx)
    {
        super(tag, ctx);
    }

    @Override
    public void onVideoData(long timeoffset, byte[] data, FlvEncoder flvEncoder)
    {
        if (data == null) return;

        if (waitingForKeyframe)
        {
            // FLV video tag layout: [tagType(1)][dataSize(3)][timestamp(4)][streamId(3)][videoData...]
            // videoData byte 0 (= packet byte 11): FrameType(4bits)|CodecID(4bits)
            //   0x17 = keyframe + AVC,  0x27 = inter-frame + AVC
            if (data.length <= 11 || data[11] != 0x17) return;

            // First live keyframe — send FLV header and AVC sequence header now
            enqueue(HttpChunk.make(flvEncoder.getHeader().getBytes()));
            byte[] seqHeader = flvEncoder.getVideoHeader().getBytes();
            FLVUtils.resetTimestamp(seqHeader, 0);
            enqueue(HttpChunk.make(seqHeader));

            videoHeaderSent = true;
            waitingForKeyframe = false;
            videoTimestamp = 0;
            lastVideoFrameTimeOffset = timeoffset;
        }

        // Advance timestamp before applying so every frame gets a unique, monotonically
        // increasing DTS (the original code advanced AFTER, causing two frames at DTS=0).
        videoTimestamp += (int)(timeoffset - lastVideoFrameTimeOffset);
        lastVideoFrameTimeOffset = timeoffset;
        FLVUtils.resetTimestamp(data, (int) videoTimestamp);
        enqueue(HttpChunk.make(data));
    }

    private FlvAudioTagEncoder audioEncoder = new FlvAudioTagEncoder();
    MP3Encoder mp3Encoder = new MP3Encoder();

    @Override
    public void onAudioData(long timeoffset, byte[] data, FlvEncoder flvEncoder)
    {
        if (!videoHeaderSent) return;

        byte[] mp3Data = mp3Encoder.encode(data);
        if (mp3Data == null || mp3Data.length == 0) return;
        AudioTag audioTag = new AudioTag(0, mp3Data.length + 1, AudioTag.MP3, (byte) 0, (byte)1, (byte) 0, mp3Data);
        byte[] frameData = null;
        try
        {
            ByteBuf audioBuf = audioEncoder.encode(audioTag);
            frameData = ByteBufUtils.readReadableBytes(audioBuf);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        if (lastAudioFrameTimeOffset == 0) lastAudioFrameTimeOffset = timeoffset;

        if (data == null) return;

        FLVUtils.resetTimestamp(frameData, (int) audioTimestamp);
        audioTimestamp += (int)(timeoffset - lastAudioFrameTimeOffset);
        lastAudioFrameTimeOffset = timeoffset;

        enqueue(HttpChunk.make(frameData));
    }

    @Override
    public void close()
    {
        super.close();
        mp3Encoder.close();
    }
}
