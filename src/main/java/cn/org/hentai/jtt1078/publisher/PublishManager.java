package cn.org.hentai.jtt1078.publisher;

import cn.org.hentai.jtt1078.db.MongoService;
import cn.org.hentai.jtt1078.entity.Media;
import cn.org.hentai.jtt1078.subscriber.Subscriber;
import cn.org.hentai.jtt1078.util.Configs;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Created by houcheng on 2019-12-11.
 */
public final class PublishManager {
    static Logger logger = LoggerFactory.getLogger(PublishManager.class);
    ConcurrentHashMap<String, Channel> channels;
    private final CopyOnWriteArrayList<Consumer<String>> monitorListeners;
    private volatile String latestMonitorSnapshot;

    private PublishManager() {
        channels = new ConcurrentHashMap<String, Channel>();
        monitorListeners = new CopyOnWriteArrayList<Consumer<String>>();
        latestMonitorSnapshot = createMonitorSnapshot(new ArrayList<String>(), System.currentTimeMillis());
    }

    public Subscriber subscribe(String tag, Media.Type type, ChannelHandlerContext ctx) {
        Channel chl = channels.get(tag);
        if (chl == null) {
            chl = new Channel(tag);
            channels.put(tag, chl);
        }
        Subscriber subscriber = null;
        if (type.equals(Media.Type.Video))
            subscriber = chl.subscribe(ctx);
        else
            throw new RuntimeException("unknown media type: " + type);

        subscriber.setName("subscriber-" + tag + "-" + subscriber.getId());
        subscriber.start();

        return subscriber;
    }

    public void publishAudio(String tag, int sequence, long timestamp, int payloadType, byte[] data) {
        Channel chl = channels.get(tag);
        if (chl != null) {
            chl.observePacketSequence(sequence);
            chl.writeAudio(timestamp, payloadType, data);
        }
    }

    public void publishVideo(String tag, int sequence, long timestamp, int payloadType, int packetType, byte[] data) {
        Channel chl = channels.get(tag);
        if (chl != null)
            chl.writeVideo(sequence, timestamp, payloadType, packetType, data);
    }

    public void publishVideo(String tag, int sequence, long timestamp, int payloadType, byte[] data) {
        publishVideo(tag, sequence, timestamp, payloadType, 0, data);
    }

    public void observePacket(String tag, int sequence) {
        Channel chl = channels.get(tag);
        if (chl != null)
            chl.observePacketSequence(sequence);
    }

    public Channel open(String tag) {
        Channel chl = channels.get(tag);
        if (chl == null) {
            chl = new Channel(tag);
            channels.put(tag, chl);
        }
        if (chl.isPublishing())
            throw new RuntimeException("channel already publishing");
        return chl;
    }

    public void close(String tag) {
        Channel chl = channels.remove(tag);
        if (chl != null) {
            chl.close();
            MongoService mongo = MongoService.getInstance();
            if (mongo != null)
                mongo.updateStreamStatus(tag, "STOPPED");
        }
    }

    public void unsubscribe(String tag, long watcherId) {
        Channel chl = channels.get(tag);
        if (chl != null)
            chl.unsubscribe(watcherId);
        logger.info("unsubscribe: {} - {}", tag, watcherId);
    }

    /**
     * Registers a dashboard client and returns the most recent completed 10-second snapshot.
     */
    public String addMonitorListener(Consumer<String> listener) {
        monitorListeners.add(listener);
        return latestMonitorSnapshot;
    }

    public void removeMonitorListener(Consumer<String> listener) {
        if (listener != null)
            monitorListeners.remove(listener);
    }

    static final PublishManager instance = new PublishManager();

    public static void init() {
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "channel-status-reporter");
            t.setDaemon(true);
            return t;
        }).scheduleAtFixedRate(() -> {
            List<String> channelStatuses = new ArrayList<String>();
            if (instance.channels.isEmpty()) {
                if (isConsoleStatusLogEnabled())
                    logger.info("[status] no active channels");
                instance.publishMonitorSnapshot(channelStatuses);
                return;
            }
            for (Iterator<Channel> it = instance.channels.values().iterator(); it.hasNext();) {
                Channel chl = it.next();
                String status = chl.statusInfo();
                channelStatuses.add(status);
                if (isConsoleStatusLogEnabled())
                    logger.info("[status] {}", status);
                if (!chl.isActivelyPublishing()) {
                    logger.warn("[status] channel {} is not actively publishing — marking ERROR and removing",
                            chl.getTag());
                    MongoService mongo = MongoService.getInstance();
                    if (mongo != null)
                        mongo.updateStreamStatus(chl.getTag(), "ERROR");
                    it.remove();
                    chl.close();
                } else {
                    MongoService mongo = MongoService.getInstance();
                    if (mongo != null) {
                        mongo.updateStreamStatus(chl.getTag(), "STREAMING");
                        long noSubSince = chl.getLastNoSubscriberTime();
                        if (noSubSince > 0 && (System.currentTimeMillis() - noSubSince) >= 30_000) {
                            logger.info(
                                    "[status] channel {} has had no subscribers for 30s — clearing subscriber records",
                                    chl.getTag());
                            mongo.clearSubscribers(chl.getTag());
                        }
                    }
                }
            }
            instance.publishMonitorSnapshot(channelStatuses);
        }, 10, 10, TimeUnit.SECONDS);
    }

    private void publishMonitorSnapshot(List<String> channelStatuses) {
        String snapshot = createMonitorSnapshot(channelStatuses, System.currentTimeMillis());
        latestMonitorSnapshot = snapshot;
        for (Consumer<String> listener : monitorListeners) {
            try {
                listener.accept(snapshot);
            }
            catch (RuntimeException ex) {
                logger.debug("monitor listener failed", ex);
            }
        }
    }

    static String createMonitorSnapshot(List<String> channelStatuses, long generatedAt) {
        StringBuilder json = new StringBuilder(128 + channelStatuses.size() * 512);
        json.append("{\"generatedAt\":").append(generatedAt)
                .append(",\"intervalSeconds\":10,\"channels\":[");
        for (int i = 0; i < channelStatuses.size(); i++) {
            if (i > 0)
                json.append(',');
            json.append('"').append(escapeJson(channelStatuses.get(i))).append('"');
        }
        return json.append("]}").toString();
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': escaped.append("\\\""); break;
                case '\\': escaped.append("\\\\"); break;
                case '\b': escaped.append("\\b"); break;
                case '\f': escaped.append("\\f"); break;
                case '\n': escaped.append("\\n"); break;
                case '\r': escaped.append("\\r"); break;
                case '\t': escaped.append("\\t"); break;
                default:
                    if (c < 0x20)
                        escaped.append(String.format("\\u%04x", (int) c));
                    else
                        escaped.append(c);
            }
        }
        return escaped.toString();
    }

    private static boolean isConsoleStatusLogEnabled() {
        return "on".equalsIgnoreCase(Configs.get("monitor.console-log"));
    }

    public static PublishManager getInstance() {
        return instance;
    }
}
