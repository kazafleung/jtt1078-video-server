package cn.org.hentai.jtt1078.model;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class StreamSessionTest {

    @Test
    public void buildTagKeepsLegacyPaddingForNumericClientIds() {
        assertEquals("000000000123-1", StreamSession.buildTag("123", 1));
    }

    @Test
    public void buildTagPreservesStringClientIds() {
        assertEquals("device-A7-2", StreamSession.buildTag(" device-A7 ", 2));
    }

    @Test
    public void buildTagRejectsBlankClientIds() {
        assertThrows(IllegalArgumentException.class, () -> StreamSession.buildTag("  ", 1));
    }
}
