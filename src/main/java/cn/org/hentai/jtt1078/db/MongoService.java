package cn.org.hentai.jtt1078.db;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;

/**
 * Thin wrapper around the MongoDB sync driver.
 * Provides stream-status updates for the stream_sessions collection.
 *
 * Initialise once at startup via {@link #init(String, String)};
 * then use {@link #getInstance()} anywhere.
 */
public class MongoService
{
    private static final Logger logger = LoggerFactory.getLogger(MongoService.class);

    private static volatile MongoService INSTANCE;

    private final MongoClient client;
    private final MongoCollection<Document> streamSessions;

    private MongoService(String uri, String dbName)
    {
        this.client = MongoClients.create(uri);
        MongoDatabase db = client.getDatabase(dbName);
        this.streamSessions = db.getCollection("stream_sessions");
        logger.info("[mongo] connected to database: {}", dbName);
    }

    public static void init(String uri, String dbName)
    {
        INSTANCE = new MongoService(uri, dbName);
    }

    public static MongoService getInstance()
    {
        return INSTANCE;
    }

    /**
     * Updates the {@code status} and {@code updatedAt} fields of the
     * matching stream_sessions document.  Does nothing if no document
     * with the given tag exists.
     *
     * @param tag    channel tag in the form "{clientId}-{channelNo}"
     * @param status "STREAMING" or "NOT_STREAMING"
     */
    public void updateStreamStatus(String tag, String status)
    {
        try
        {
            streamSessions.updateOne(
                    eq("tag", tag),
                    combine(set("status", status), set("updatedAt", new Date()))
            );
        }
        catch (Exception e)
        {
            logger.error("[mongo] failed to update stream status for {}: {}", tag, e.getMessage());
        }
    }

    public void close()
    {
        try { client.close(); } catch (Exception ignored) { }
    }
}
