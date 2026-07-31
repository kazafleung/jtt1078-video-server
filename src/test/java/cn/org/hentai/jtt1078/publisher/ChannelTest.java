package cn.org.hentai.jtt1078.publisher;

import cn.org.hentai.jtt1078.entity.MediaEncoding;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChannelTest
{
    @Test
    public void h264StreamStartsWithVideoOnlyHeader()
    {
        assertEquals(0x01, startStreamAndReadHeaderFlags(98));
    }

    @Test
    public void h265StreamStartsWithVideoOnlyHeader()
    {
        assertEquals(0x01, startStreamAndReadHeaderFlags(99));
    }

    @Test
    public void normalizesThreeAndFourByteAnnexBStartCodes()
    {
        byte[] frame = new byte[] {
                0x00, 0x00, 0x01, 0x67, 0x11, 0x22,
                0x00, 0x00, 0x00, 0x01, 0x68, 0x33
        };

        List<byte[]> nalus = Channel.extractNalus(frame, MediaEncoding.Encoding.H264);

        assertEquals(2, nalus.size());
        assertArrayEquals(new byte[] { 0x00, 0x00, 0x00, 0x01, 0x67, 0x11, 0x22 }, nalus.get(0));
        assertArrayEquals(new byte[] { 0x00, 0x00, 0x00, 0x01, 0x68, 0x33 }, nalus.get(1));
    }

    @Test
    public void acceptsLengthPrefixedAndRawNalus()
    {
        List<byte[]> lengthPrefixed = Channel.extractNalus(
                new byte[] { 0x00, 0x00, 0x00, 0x03, 0x65, 0x11, 0x22 },
                MediaEncoding.Encoding.H264);
        List<byte[]> raw = Channel.extractNalus(
                new byte[] { 0x65, 0x33, 0x44 },
                MediaEncoding.Encoding.H264);

        assertEquals(1, lengthPrefixed.size());
        assertArrayEquals(new byte[] { 0x00, 0x00, 0x00, 0x01, 0x65, 0x11, 0x22 },
                lengthPrefixed.get(0));
        assertEquals(1, raw.size());
        assertArrayEquals(new byte[] { 0x00, 0x00, 0x00, 0x01, 0x65, 0x33, 0x44 }, raw.get(0));
    }

    @Test
    public void initializesH264EncoderFromMixedAnnexBStartCodes()
    {
        Channel channel = new Channel("h264-annex-b");
        try
        {
            channel.writeVideo(1, 1000, 98, 0, new byte[] {
                    0x00, 0x00, 0x01, 0x67, 0x4d, 0x00, 0x14,
                    0x00, 0x00, 0x00, 0x01, 0x68, (byte) 0xee, 0x3c, (byte) 0x80,
                    0x00, 0x00, 0x01, 0x65, 0x11, 0x22
            });

            assertTrue(channel.flvEncoder.videoReady());
        }
        finally
        {
            channel.close();
        }
    }

    @Test
    public void initializesH265EncoderFromThreeByteStartCodes()
    {
        Channel channel = new Channel("h265-annex-b");
        try
        {
            channel.writeVideo(1, 1000, 99, 0, new byte[] {
                    0x00, 0x00, 0x01, 0x42, 0x01,
                    0x00, 0x00, 0x01, 0x44, 0x01,
                    0x00, 0x00, 0x01, 0x26, 0x01, 0x11, 0x22
            });

            assertTrue(channel.flvEncoder.videoReady());
        }
        finally
        {
            channel.close();
        }
    }

    @Test
    public void reassemblesLargeFrameFrom950ByteFragments()
    {
        Channel channel = new Channel("large-frame", 400 * 1024);
        try
        {
            byte[] frame = new byte[300 * 1024];
            Arrays.fill(frame, (byte) 0x55);
            frame[0] = 0x00;
            frame[1] = 0x00;
            frame[2] = 0x00;
            frame[3] = 0x01;
            frame[4] = 0x65;

            writeFragmentedFrame(channel, frame, 950, 100, 1234, 98);

            assertEquals(0, channel.bufferedVideoBytes());
            assertFalse(channel.isAssemblingVideoFrame());
            assertTrue(channel.isPublishing());
        }
        finally
        {
            channel.close();
        }
    }

    @Test
    public void dropsOversizedFrameAndAcceptsTheNextAtomicFrame()
    {
        Channel channel = new Channel("oversized-frame", 2000);
        try
        {
            byte[] first = new byte[1500];
            byte[] middle = new byte[600];
            channel.writeVideo(10, 1000, 98, 1, first);
            assertTrue(channel.isAssemblingVideoFrame());

            channel.writeVideo(11, 1000, 98, 3, middle);
            assertEquals(0, channel.bufferedVideoBytes());
            assertFalse(channel.isAssemblingVideoFrame());

            channel.writeVideo(12, 1040, 98, 0, new byte[] { 0x65, 0x11, 0x22 });
            assertTrue(channel.isPublishing());
        }
        finally
        {
            channel.close();
        }
    }

    @Test
    public void dropsFrameWhenAFragmentIsMissing()
    {
        Channel channel = new Channel("missing-fragment", 4096);
        try
        {
            channel.writeVideo(20, 2000, 98, 1, new byte[] { 0x00, 0x00 });
            channel.writeVideo(22, 2000, 98, 2, new byte[] { 0x01, 0x65, 0x11 });

            assertEquals(0, channel.bufferedVideoBytes());
            assertFalse(channel.isAssemblingVideoFrame());
        }
        finally
        {
            channel.close();
        }
    }

    @Test
    public void handlesSequenceWrapAndStartCodeAcrossFragments()
    {
        Channel channel = new Channel("sequence-wrap", 4096);
        try
        {
            channel.writeVideo(65535, 3000, 98, 1, new byte[] { 0x00, 0x00 });
            channel.writeVideo(0, 3000, 98, 2, new byte[] { 0x01, 0x65, 0x11, 0x22 });

            assertEquals(0, channel.bufferedVideoBytes());
            assertFalse(channel.isAssemblingVideoFrame());
        }
        finally
        {
            channel.close();
        }
    }

    @Test
    public void allowsInterleavedPacketSequenceDuringVideoAssembly()
    {
        Channel channel = new Channel("interleaved-audio", 4096);
        try
        {
            channel.writeVideo(30, 4000, 98, 1, new byte[] { 0x00, 0x00 });
            channel.observePacketSequence(31); // e.g. an audio packet on the same JT/T connection
            channel.writeVideo(32, 4000, 98, 3, new byte[] { 0x01, 0x65 });
            channel.writeVideo(33, 4000, 98, 2, new byte[] { 0x11, 0x22 });

            assertEquals(0, channel.bufferedVideoBytes());
            assertFalse(channel.isAssemblingVideoFrame());
        }
        finally
        {
            channel.close();
        }
    }

    @Test
    public void calculatesUploadRateFromReceivedMediaPayloadBytes()
    {
        Channel channel = new Channel("upload-rate", 4096);
        try
        {
            byte[] payload = new byte[1000];
            payload[0] = 0x65;
            channel.writeVideo(1, 5000, 98, 0, payload);

            Channel.UploadRateSnapshot first = channel.calculateUploadRates(1_000_000_000L);
            Channel.UploadRateSnapshot second = channel.calculateUploadRates(1_000_000_000L);

            assertEquals(8000, first.videoBitsPerSecond);
            assertEquals(0, first.audioBitsPerSecond);
            assertEquals(8000, first.totalBitsPerSecond());
            assertEquals(0, second.totalBitsPerSecond());
        }
        finally
        {
            channel.close();
        }
    }

    @Test
    public void statusInfoSummarizesChannelHealthAndResetsIntervalCounters()
    {
        Channel channel = new Channel("status-metrics", 2000);
        try
        {
            channel.writeVideo(1, 6000, 98, 0,
                    new byte[] { 0x00, 0x00, 0x00, 0x01, 0x65, 0x11 });
            channel.writeVideo(2, 6040, 98, 1, new byte[] { 0x00, 0x00 });
            channel.writeVideo(4, 6040, 98, 2, new byte[] { 0x01, 0x61, 0x22 });

            String first = channel.statusInfo();
            String second = channel.statusInfo();

            assertTrue(first.contains("tag=status-metrics state=STREAMING publishing=true codec=H264 pt=98"));
            assertTrue(first.contains("videoPackets10s=3 audioPackets10s=0 packetsTotal=3"));
            assertTrue(first.contains("frames10s=1 framesTotal=1"));
            assertTrue(first.contains("drops10s=1 dropsTotal=1 dropSeq=1"));
            assertTrue(first.contains("buffer=0 peakBuffer=2 maxBuffer=2000"));
            assertTrue(first.contains("keyframeAgeSec=0"));

            assertTrue(second.contains("videoPackets10s=0 audioPackets10s=0 packetsTotal=3"));
            assertTrue(second.contains("frames10s=0 framesTotal=1"));
            assertTrue(second.contains("drops10s=0 dropsTotal=1 dropSeq=0"));
        }
        finally
        {
            channel.close();
        }
    }

    private int startStreamAndReadHeaderFlags(int payloadType)
    {
        Channel channel = new Channel("test-" + payloadType);
        try
        {
            channel.writeVideo(0, 0, payloadType, new byte[] { 0x00, 0x00, 0x00, 0x01 });
            return channel.flvEncoder.getHeader().getBytes()[4] & 0xff;
        }
        finally
        {
            channel.close();
        }
    }

    private void writeFragmentedFrame(Channel channel, byte[] frame, int fragmentSize,
            int firstSequence, long timestamp, int payloadType)
    {
        int offset = 0;
        int sequence = firstSequence;
        while (offset < frame.length)
        {
            int length = Math.min(fragmentSize, frame.length - offset);
            byte[] fragment = Arrays.copyOfRange(frame, offset, offset + length);
            int packetType;
            if (offset == 0)
                packetType = 1;
            else if (offset + length == frame.length)
                packetType = 2;
            else
                packetType = 3;

            channel.writeVideo(sequence, timestamp, payloadType, packetType, fragment);
            sequence = (sequence + 1) & 0xffff;
            offset += length;
        }
    }
}
