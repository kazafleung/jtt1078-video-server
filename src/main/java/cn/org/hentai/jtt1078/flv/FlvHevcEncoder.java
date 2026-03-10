package cn.org.hentai.jtt1078.flv;

import cn.org.hentai.jtt1078.util.Packet;

import java.util.Arrays;

/**
 * FLV encoder for H.265 / HEVC video using the enhanced (non-standard) FLV format
 * that is widely supported by Chinese media players (mpegts.js, xgplayer, etc.).
 *
 * Video tag codec byte:
 *   0x1C = keyframe   + HEVC  (frameType=1, codecID=12)
 *   0x2C = inter-frame + HEVC (frameType=2, codecID=12)
 *
 * H.265 NALU types relevant here (nal_unit_type = (nalu[4] >> 1) & 0x3F):
 *   32 = VPS, 33 = SPS, 34 = PPS
 *   16-21 = IDR / BLA / CRA (keyframes)
 *   0-15  = slice data (inter frames)
 */
public class FlvHevcEncoder extends FlvEncoder
{
    Packet hevcVPS;
    int hevcVPSSize;

    public FlvHevcEncoder(boolean haveVideo, boolean haveAudio)
    {
        super(haveVideo, haveAudio);
    }

    @Override
    public byte[] write(byte[] nalu, int nTimeStamp)
    {
        if (nalu == null || nalu.length <= 5) return null;

        // H.265 NALU header is 2 bytes after the 4-byte start code.
        // nal_unit_type occupies bits 9-14 of the 16-bit header word:
        //   nal_unit_type = (nalu[4] >> 1) & 0x3F
        int naluType = (nalu[4] >> 1) & 0x3F;

        // Skip SEI and AUD
        if (naluType == 35 || naluType == 39 || naluType == 40) return null;

        // Accumulate parameter sets
        if (naluType == 32) {           // VPS
            hevcVPS = Packet.create(nalu);
            hevcVPSSize = nalu.length;
        } else if (naluType == 33) {    // SPS
            SPS = Packet.create(nalu);
            SPSSize = nalu.length;
        } else if (naluType == 34) {    // PPS
            PPS = Packet.create(nalu);
            PPSSize = nalu.length;
        }

        // Build sequence header as soon as we have SPS + PPS (VPS is optional)
        if (SPS != null && PPS != null && !writeAVCSeqHeader)
        {
            buildHEVCSeqHeader(nTimeStamp);
            writeAVCSeqHeader = true;
        }

        if (!writeAVCSeqHeader) return null;

        // Parameter set NALUs are embedded in the sequence header; skip from frame data
        if (naluType == 32 || naluType == 33 || naluType == 34) return null;

        videoFrame.reset();
        writeHEVCFrame(nalu, nTimeStamp);

        if (videoFrame.size() == 0) return null;

        // BLA (16-18), IDR (19-20), CRA (21) are all intra / keyframe types
        boolean isKeyframe = (naluType >= 16 && naluType <= 21);
        if (isKeyframe) lastIFrame = videoFrame.toByteArray();

        return videoFrame.toByteArray();
    }

    /**
     * Builds the FLV video sequence-header tag containing an
     * HEVCDecoderConfigurationRecord (ISO 14496-15 §8.3.3.1).
     *
     * Profile / level bytes are read directly from the raw SPS NALU:
     *   sps[7]     = general_profile_space|tier|profile_idc
     *   sps[8-11]  = general_profile_compatibility_flags  (4 bytes)
     *   sps[12-17] = general_constraint_indicator_flags   (6 bytes)
     *   sps[18]    = general_level_idc
     * (Indices include the 4-byte start code and 2-byte NALU header prefix.)
     */
    void buildHEVCSeqHeader(int nTimeStamp)
    {
        byte[] spsData = SPS.data;

        byte profileTierByte  = spsData.length > 7  ? spsData[7]  : 0x01;
        byte[] compatFlags    = new byte[4];
        byte[] constraintFlags = new byte[6];
        if (spsData.length > 11) System.arraycopy(spsData, 8,  compatFlags,    0, 4);
        if (spsData.length > 17) System.arraycopy(spsData, 12, constraintFlags, 0, 6);
        byte levelIdc = spsData.length > 18 ? spsData[18] : (byte) 0x5A; // default level 3.0

        boolean hasVPS       = hevcVPS != null;
        int     vpsPayload   = hasVPS   ? hevcVPSSize - 4 : 0;
        int     spsPayload   = SPSSize  - 4;
        int     ppsPayload   = PPSSize  - 4;
        int     numArrays    = hasVPS ? 3 : 2;

        // HEVCDecoderConfigurationRecord fixed header: 23 bytes
        // Each parameter-set array: 1 (type) + 2 (numNalus) + 2 (naluLen) + payload = 5 + payload
        int hvccSize = 23
                + (hasVPS ? 5 + vpsPayload : 0)
                + 5 + spsPayload
                + 5 + ppsPayload;

        // FLV video tag data: 1 (codecByte) + 1 (packetType) + 3 (compTime) + hvccSize
        int nDataSize = 5 + hvccSize;

        // FLV tag: 11 (header) + nDataSize + 4 (prevTagSize)
        videoHeader = Packet.create(nDataSize + 16);

        // ── FLV tag header ─────────────────────────────────────────────────
        videoHeader.addByte((byte) 0x09);            // tag type = video
        videoHeader.add3Bytes(nDataSize);
        videoHeader.add3Bytes(nTimeStamp & 0xFFFFFF);
        videoHeader.addByte((byte) (nTimeStamp >> 24));
        videoHeader.add3Bytes(streamID);

        // ── Video data ─────────────────────────────────────────────────────
        videoHeader.addByte((byte) 0x1C);            // keyframe (1) + HEVC codecID (12 = 0xC)
        videoHeader.addByte((byte) 0x00);            // AVCPacketType = 0: sequence header
        videoHeader.add3Bytes(0x00);                 // composition time

        // ── HEVCDecoderConfigurationRecord ─────────────────────────────────
        videoHeader.addByte((byte) 0x01);            // configurationVersion
        videoHeader.addByte(profileTierByte);
        videoHeader.addBytes(compatFlags);
        videoHeader.addBytes(constraintFlags);
        videoHeader.addByte(levelIdc);
        videoHeader.addByte((byte) 0xF0);
        videoHeader.addByte((byte) 0x00);            // min_spatial_segmentation_idc (reserved 0xF | 12-bit)
        videoHeader.addByte((byte) 0xFC);            // parallelismType (reserved 0x3F | 2-bit = 0)
        videoHeader.addByte((byte) (0xFC | 1));      // chroma_format_idc = 1 (4:2:0)
        videoHeader.addByte((byte) (0xF8 | 0));      // bit_depth_luma_minus8 = 0
        videoHeader.addByte((byte) (0xF8 | 0));      // bit_depth_chroma_minus8 = 0
        videoHeader.addByte((byte) 0x00);
        videoHeader.addByte((byte) 0x00);            // avgFrameRate = 0 (unspecified)
        // constantFrameRate(2) | numTemporalLayers(3)=1 | temporalIdNested(1)=1 | lengthSizeMinusOne(2)=3
        videoHeader.addByte((byte) ((0 << 6) | (1 << 3) | (1 << 2) | 3));
        videoHeader.addByte((byte) numArrays);

        // ── VPS array (if available) ────────────────────────────────────────
        if (hasVPS)
        {
            videoHeader.addByte((byte) (0x80 | 32)); // array_completeness=1, NAL_unit_type=VPS(32)
            videoHeader.addShort((short) 1);
            videoHeader.addShort((short) vpsPayload);
            videoHeader.addBytes(Arrays.copyOfRange(hevcVPS.data, 4, hevcVPSSize));
        }

        // ── SPS array ───────────────────────────────────────────────────────
        videoHeader.addByte((byte) (0x80 | 33));     // NAL_unit_type=SPS(33)
        videoHeader.addShort((short) 1);
        videoHeader.addShort((short) spsPayload);
        videoHeader.addBytes(Arrays.copyOfRange(SPS.data, 4, SPSSize));

        // ── PPS array ───────────────────────────────────────────────────────
        videoHeader.addByte((byte) (0x80 | 34));     // NAL_unit_type=PPS(34)
        videoHeader.addShort((short) 1);
        videoHeader.addShort((short) ppsPayload);
        videoHeader.addBytes(Arrays.copyOfRange(PPS.data, 4, PPSSize));

        prevTagSize = 11 + nDataSize;
        videoHeader.addInt(prevTagSize);
    }

    /**
     * Writes one H.265 NALU as an enhanced-FLV video tag into {@code videoFrame}.
     * The NALU is length-prefixed (4-byte big-endian) without its start code.
     */
    void writeHEVCFrame(byte[] nalu, int nTimeStamp)
    {
        int naluType   = (nalu[4] >> 1) & 0x3F;
        boolean isKey  = (naluType >= 16 && naluType <= 21);

        int naluPayload = nalu.length - 4;          // skip 4-byte start code
        int nDataSize   = 1 + 1 + 3 + 4 + naluPayload;

        writeByte(0x09);                             // FLV tag type = video
        writeU3(nDataSize);
        writeU3(nTimeStamp & 0xFFFFFF);
        writeByte(nTimeStamp >> 24);
        writeU3(streamID);

        writeByte(isKey ? 0x1C : 0x2C);             // HEVC codecID=12, frameType 1=key / 2=inter
        writeByte(0x01);                             // AVCPacketType = 1: NALU data
        writeU3(0x00);                               // compositionTime
        writeU4(naluPayload);                        // length-prefixed NALU
        writeBytes(nalu, 4, naluPayload);

        prevTagSize = 11 + nDataSize;
        writeU4(prevTagSize);
    }
}
