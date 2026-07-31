package cn.org.hentai.jtt1078.publisher;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class PublishManagerTest
{
    @Test
    public void createsEscapedDashboardSnapshotFromChannelStatusLines()
    {
        String snapshot = PublishManager.createMonitorSnapshot(Arrays.asList(
                "tag=device-1 state=STREAMING uploadKbps=512",
                "tag=device-\"2 state=STALE note=line\\break"), 123456789L);

        assertEquals(
                "{\"generatedAt\":123456789,\"intervalSeconds\":10,\"channels\":["
                        + "\"tag=device-1 state=STREAMING uploadKbps=512\","
                        + "\"tag=device-\\\"2 state=STALE note=line\\\\break\"]}",
                snapshot);
    }
}
