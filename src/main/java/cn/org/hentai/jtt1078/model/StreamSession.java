package cn.org.hentai.jtt1078.model;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Tracks live streaming sessions initiated via JT/T 1078 T9101 commands.
 * One document per (clientId, channelNo) — upserted on each T9101 request.
 * Status is updated both by this server (on T9102 control commands) and by
 * the media server when it receives/loses stream data.
 */
public class StreamSession {

    private String id;

    /** 媒体服务器标识: 12位cid(左补0) + "-" + cho */
    private String tag;

    /** 终端手机号 (cid) */
    private String cid;

    /** 逻辑通道号 (cho) */
    private int cho;

    /** 媒体类型: 0=音视频 1=视频 2=双向对讲 3=监听 4=中心广播 5=透传 (mt) */
    private int mt;

    /** 码流类型: 0=主码流 1=子码流 (sty) */
    private int sty;

    /** 媒体服务器IP (sip) */
    private String sip;

    /** 媒体服务器TCP端口 (stp) */
    private int stp;

    /** 媒体服务器UDP端口 (sup) */
    private int sup;

    /** 流状态 (st) */
    private Status st;

    /** 请求时间 (UTC) (reqAt) */
    private LocalDateTime reqAt;

    /** 最后状态更新时间 (UTC) (upAt) */
    private LocalDateTime upAt;

    public enum Status {
        /** T9101 sent, waiting for device to connect to media server */
        REQUESTED,
        /** Media server confirmed stream is active */
        STREAMING,
        /** T9102 command=2: stream paused */
        PAUSED,
        /** T9102 command=0/4: stream closed, or media server lost the stream */
        STOPPED
    }

    public StreamSession markUpdated() {
        this.upAt = LocalDateTime.now(ZoneOffset.UTC);
        return this;
    }

    public static String buildTag(String cid, int cho) {
        return String.format("%012d", Long.parseLong(cid)) + "-" + cho;
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

    public String getCid() {
        return cid;
    }

    public StreamSession setCid(String cid) {
        this.cid = cid;
        return this;
    }

    public int getCho() {
        return cho;
    }

    public StreamSession setCho(int cho) {
        this.cho = cho;
        return this;
    }

    public int getMt() {
        return mt;
    }

    public StreamSession setMt(int mt) {
        this.mt = mt;
        return this;
    }

    public int getSty() {
        return sty;
    }

    public StreamSession setSty(int sty) {
        this.sty = sty;
        return this;
    }

    public String getSip() {
        return sip;
    }

    public StreamSession setSip(String sip) {
        this.sip = sip;
        return this;
    }

    public int getStp() {
        return stp;
    }

    public StreamSession setStp(int stp) {
        this.stp = stp;
        return this;
    }

    public int getSup() {
        return sup;
    }

    public StreamSession setSup(int sup) {
        this.sup = sup;
        return this;
    }

    public Status getSt() {
        return st;
    }

    public StreamSession setSt(Status st) {
        this.st = st;
        return this;
    }

    public LocalDateTime getReqAt() {
        return reqAt;
    }

    public StreamSession setReqAt(LocalDateTime reqAt) {
        this.reqAt = reqAt;
        return this;
    }

    public LocalDateTime getUpAt() {
        return upAt;
    }

    public StreamSession setUpAt(LocalDateTime upAt) {
        this.upAt = upAt;
        return this;
    }
}
