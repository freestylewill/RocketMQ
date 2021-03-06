/**
 * $Id: MessageDecoder.java 1831 2013-05-16 01:39:51Z shijia.wxr $
 */
package com.alibaba.rocketmq.common;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.rocketmq.common.sysflag.MessageSysFlag;


/**
 * 消息解码
 * 
 * @author vintage.wang@gmail.com shijia.wxr@taobao.com
 */
public class MessageDecoder {
    /**
     * 消息ID定长
     */
    public final static int MSG_ID_LENGTH = 4 + 8 + 8;

    /**
     * 存储记录各个字段位置
     */
    public final static int MessageMagicCodePostion = 4;
    public final static int MessageFlagPostion = 16;
    public final static int MessagePhysicOffsetPostion = 28;
    public final static int MessageStoreTimestampPostion = 56;


    public static String createMessageId(final ByteBuffer input, final int time, final ByteBuffer addr,
            final long offset) {
        input.flip();
        input.limit(MessageDecoder.MSG_ID_LENGTH);

        // 消息存储时间 4
        input.putInt(time);
        // 消息存储主机地址 IP PORT 8
        input.put(addr);
        // 消息对应的物理分区 OFFSET 8
        input.putLong(offset);

        return UtilALl.bytes2string(input.array());
    }


    public static MessageId decodeMessageId(final String msgId) throws UnknownHostException {
        long timestamp;
        SocketAddress address;
        long offset;

        // 时间
        byte[] data = UtilALl.string2bytes(msgId.substring(0, 8));
        ByteBuffer bb = ByteBuffer.wrap(data);
        timestamp = bb.getInt(0) * 1000;

        // 地址
        byte[] ip = UtilALl.string2bytes(msgId.substring(8, 16));
        byte[] port = UtilALl.string2bytes(msgId.substring(16, 24));
        bb = ByteBuffer.wrap(port);
        int portInt = bb.getInt(0);
        address = new InetSocketAddress(InetAddress.getByAddress(ip), portInt);

        // offset
        data = UtilALl.string2bytes(msgId.substring(24, 40));
        bb = ByteBuffer.wrap(data);
        offset = bb.getLong(0);

        return new MessageId(timestamp, address, offset);
    }


    public static MessageExt decode(java.nio.ByteBuffer byteBuffer) {
        return decode(byteBuffer, true);
    }


    /**
     * 客户端使用，SLAVE也会使用
     */
    public static MessageExt decode(java.nio.ByteBuffer byteBuffer, final boolean readBody) {
        try {
            MessageExt msgExt = new MessageExt();

            // 1 TOTALSIZE
            int storeSize = byteBuffer.getInt();
            msgExt.setStoreSize(storeSize);

            // 2 MAGICCODE
            byteBuffer.getInt();

            // 3 BODYCRC
            int bodyCRC = byteBuffer.getInt();
            msgExt.setBodyCRC(bodyCRC);

            // 4 QUEUEID
            int queueId = byteBuffer.getInt();
            msgExt.setQueueId(queueId);

            // 5 FLAG
            int flag = byteBuffer.getInt();
            msgExt.setFlag(flag);

            // 6 QUEUEOFFSET
            long queueOffset = byteBuffer.getLong();
            msgExt.setQueueOffset(queueOffset);

            // 7 PHYSICALOFFSET
            long physicOffset = byteBuffer.getLong();
            msgExt.setCommitLogOffset(physicOffset);

            // 8 SYSFLAG
            int sysFlag = byteBuffer.getInt();
            msgExt.setSysFlag(sysFlag);

            // 9 BORNTIMESTAMP
            long bornTimeStamp = byteBuffer.getLong();
            msgExt.setBornTimestamp(bornTimeStamp);

            // 10 BORNHOST
            byte[] bornHost = new byte[4];
            byteBuffer.get(bornHost, 0, 4);
            int port = byteBuffer.getInt();
            msgExt.setBornHost(new InetSocketAddress(InetAddress.getByAddress(bornHost), port));

            // 11 STORETIMESTAMP
            long storeTimestamp = byteBuffer.getLong();
            msgExt.setStoreTimestamp(storeTimestamp);

            // 12 STOREHOST
            byte[] storeHost = new byte[4];
            byteBuffer.get(storeHost, 0, 4);
            port = byteBuffer.getInt();
            msgExt.setStoreHost(new InetSocketAddress(InetAddress.getByAddress(storeHost), port));

            // 13 RECONSUMETIMES
            int reconsumeTimes = byteBuffer.getInt();
            msgExt.setReconsumeTimes(reconsumeTimes);

            // 14 Prepared Transaction Offset
            long preparedTransactionOffset = byteBuffer.getLong();
            msgExt.setPreparedTransactionOffset(preparedTransactionOffset);

            // 15 BODY
            int bodyLen = byteBuffer.getInt();
            if (bodyLen > 0) {
                if (readBody) {
                    byte[] body = new byte[bodyLen];
                    byteBuffer.get(body);

                    // uncompress body
                    if ((sysFlag & MessageSysFlag.CompressedFlag) == MessageSysFlag.CompressedFlag) {
                        body = UtilALl.uncompress(body);
                    }

                    msgExt.setBody(body);
                }
                else {
                    byteBuffer.position(byteBuffer.position() + bodyLen);
                }
            }

            // 16 TOPIC
            byte topicLen = byteBuffer.get();
            byte[] topic = new byte[(int) topicLen];
            byteBuffer.get(topic);
            msgExt.setTopic(new String(topic));

            // 17 properties
            short propertiesLength = byteBuffer.getShort();
            if (propertiesLength > 0) {
                byte[] properties = new byte[propertiesLength];
                byteBuffer.get(properties);
                String propertiesString = new String(properties);
                Map<String, String> map = string2messageProperties(propertiesString);
                msgExt.setProperties(map);
            }

            // 消息ID
            ByteBuffer byteBufferMsgId = ByteBuffer.allocate(MSG_ID_LENGTH);
            String msgId =
                    createMessageId(byteBufferMsgId, (int) (msgExt.getStoreTimestamp() / 1000),
                        msgExt.getStoreHostBytes(), msgExt.getCommitLogOffset());
            msgExt.setMsgId(msgId);

            return msgExt;
        }
        catch (UnknownHostException e) {
            byteBuffer.position(byteBuffer.limit());
        }
        catch (BufferUnderflowException e) {
            byteBuffer.position(byteBuffer.limit());
        }
        catch (Exception e) {
            byteBuffer.position(byteBuffer.limit());
        }

        return null;
    }


    public static List<MessageExt> decodes(java.nio.ByteBuffer byteBuffer) {
        return decodes(byteBuffer, true);
    }


    /**
     * 客户端使用
     */
    public static List<MessageExt> decodes(java.nio.ByteBuffer byteBuffer, final boolean readBody) {
        List<MessageExt> msgExts = new ArrayList<MessageExt>();
        while (byteBuffer.hasRemaining()) {
            MessageExt msgExt = decode(byteBuffer, readBody);
            if (null != msgExt) {
                msgExts.add(msgExt);
            }
            else {
                log.warn("message decode error.");
                break;
            }
        }
        return msgExts;
    }

    /**
     * 序列化消息属性
     */
    public static final char NAME_VALUE_SEPARATOR = 1;
    public static final char PROPERTY_SEPARATOR = 2;


    public static String messageProperties2String(Map<String, String> properties) {
        StringBuilder sb = new StringBuilder();
        if (properties != null) {
            for (final Map.Entry<String, String> entry : properties.entrySet()) {
                final String name = entry.getKey();
                final String value = entry.getValue();

                sb.append(name);
                sb.append(NAME_VALUE_SEPARATOR);
                sb.append(value);
                sb.append(PROPERTY_SEPARATOR);
            }
        }
        return sb.toString();
    }


    public static Map<String, String> string2messageProperties(final String properties) {
        Map<String, String> map = new HashMap<String, String>();
        if (properties != null) {
            String[] items = properties.split(String.valueOf(PROPERTY_SEPARATOR));
            if (items != null) {
                for (String i : items) {
                    String[] nv = i.split(String.valueOf(NAME_VALUE_SEPARATOR));
                    if (nv != null && 2 == nv.length) {
                        map.put(nv[0], nv[1]);
                    }
                }
            }
        }

        return map;
    }

    private static final Logger log = LoggerFactory.getLogger(MixAll.CommonLoggerName);
}
