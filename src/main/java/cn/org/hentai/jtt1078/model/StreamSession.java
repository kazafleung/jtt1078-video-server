package cn.org.hentai.jtt1078.model;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Tracks live streaming sessions initiated via JT/T 1078 T9101 commands.
 * Maps to the stream_sessions collection in MongoDB.
 */
public class StreamSession {

    private String id;

    /** 媒体服务器标识: 12位clientId(左补0) + "-" + channelNo */
    private String tag;

    /** 终端手机号 */
    private String clientId;

    /** 逻辑通道号 */
    private int channelNo;

    /** 媒体类型: 0=音视频 1=视频 2=双向对讲 3=监听 4=中心广播 5=透传 */
    private int mediaType;

    /** 码流类型: 0=主码流 1=子码流 */
    private int streamType;

    /** 媒体服务器IP */
    private String serverIp;

    /** 媒体服务器TCP端口 */
    private int serverTcpPort;

    /** 媒体服务器UDP端口 */
    private int serverUdpPort;

    /** 流状态 */
    private Status status;

    /** 请求时间 (UTC) */
    private LocalDateTime requestedAt;

    /** 最后状态更新时间 (UTC) */
    private LocalDateTime updatedAt;

    public enum Status {
        /** T9101 sent, waiting for device to connect to media server */
        REQUESTED,
        /** Media server confirmed stream is active */
        STREAMING,
        /** Media server confirmed stream is not active */
        NOT_STREAMING,
        /** T9102 command=2: stream paused */
        PAUSED,
        /** T9102 command=0/4: stream closed, or media server lost the stream */
        STOPPED
    }

    public StreamSession markUpdated() {
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
        return this;
    }

    public String getId() {
        return id;
    }

    public StreamSession setId(String id) {
        this.id = id;
        return this;
    }

    public String getTag() {
        return tag;
    }

    public StreamSession setTag(String tag) {
        this.tag = tag;
        return this;
    }

    public String getClientId() {
        return clientId;
    }

    public StreamSession setClientId(String clientId) {
        this.clientId = clientId;
        return this;
    }

    public int getChannelNo() {
        return channelNo;
    }

    public StreamSession setChannelNo(int channelNo) {
        this.channelNo = channelNo;
        return this;
    }

    public int getMediaType() {
        return mediaType;
    }

    public StreamSession setMediaType(int mediaType) {
        this.mediaType = mediaType;
        return this;
    }

    public int getStreamType() {
        return streamType;
    }

    public StreamSession setStreamType(int streamType) {
        this.streamType = streamType;
        return this;
    }

    public String getServerIp() {
        return serverIp;
    }

    public StreamSession setServerIp(String serverIp) {
        this.serverIp = serverIp;
        return this;
    }

    public int getServerTcpPort() {
        return serverTcpPort;
    }

    public StreamSession setServerTcpPort(int serverTcpPort) {
        this.serverTcpPort = serverTcpPort;
        return this;
    }

    public int getServerUdpPort() {
        return serverUdpPort;
    }

    public StreamSession setServerUdpPort(int serverUdpPort) {
        this.serverUdpPort = serverUdpPort;
        return this;
    }

    public Status getStatus() {
        return status;
    }

    public StreamSession setStatus(Status status) {
        this.status = status;
        return this;
    }

    public LocalDateTime getRequestedAt() {
        return requestedAt;
    }

    public StreamSession setRequestedAt(LocalDateTime requestedAt) {
        this.requestedAt = requestedAt;
        return this;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public StreamSession setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
        return this;
    }
}
