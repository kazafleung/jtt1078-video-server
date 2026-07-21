package cn.org.hentai.jtt1078.flv;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class FlvEncoderTest
{
    @Test
    public void writesAudioAndVideoPresenceFlagsToHeader()
    {
        assertEquals(0x00, headerFlags(new FlvEncoder(false, false)));
        assertEquals(0x01, headerFlags(new FlvEncoder(true, false)));
        assertEquals(0x04, headerFlags(new FlvEncoder(false, true)));
        assertEquals(0x05, headerFlags(new FlvEncoder(true, true)));
    }

    private int headerFlags(FlvEncoder encoder)
    {
        return encoder.getHeader().getBytes()[4] & 0xff;
    }
}
