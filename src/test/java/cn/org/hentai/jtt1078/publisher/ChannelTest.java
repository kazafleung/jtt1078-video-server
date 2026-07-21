package cn.org.hentai.jtt1078.publisher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}
