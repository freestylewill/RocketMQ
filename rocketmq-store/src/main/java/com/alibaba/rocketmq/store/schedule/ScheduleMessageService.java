/**
 * $Id: ScheduleMessageService.java 1831 2013-05-16 01:39:51Z shijia.wxr $
 */
package com.alibaba.rocketmq.store.schedule;

import java.util.HashMap;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.rocketmq.common.Message;
import com.alibaba.rocketmq.common.MessageDecoder;
import com.alibaba.rocketmq.common.MessageExt;
import com.alibaba.rocketmq.common.MixAll;
import com.alibaba.rocketmq.common.TopicFilterType;
import com.alibaba.rocketmq.common.sysflag.MessageSysFlag;
import com.alibaba.rocketmq.store.ConsumeQueue;
import com.alibaba.rocketmq.store.DefaultMessageStore;
import com.alibaba.rocketmq.store.MessageExtBrokerInner;
import com.alibaba.rocketmq.store.PutMessageResult;
import com.alibaba.rocketmq.store.PutMessageStatus;
import com.alibaba.rocketmq.store.SelectMapedBufferResult;


/**
 * ��ʱ��Ϣ����
 * 
 * @author vintage.wang@gmail.com shijia.wxr@taobao.com
 * 
 */
public class ScheduleMessageService {
    private static final Logger log = LoggerFactory.getLogger(MixAll.StoreLoggerName);
    public static final String SCHEDULE_TOPIC = "SCHEDULE_TOPIC_XXXX";
    private static final long FIRST_DELAY_TIME = 1000L;
    private static final long DELAY_FOR_A_WHILE = 100L;
    private static final long DELAY_FOR_A_PERIOD = 10000L;

    // ÿ��level��Ӧ����ʱʱ��
    private final ConcurrentHashMap<Integer /* level */, Long/* delay timeMillis */> delayLevelTable =
            new ConcurrentHashMap<Integer, Long>(32);

    // ��ʱ���㵽������
    private final ConcurrentHashMap<Integer /* level */, Long/* offset */> offsetTable =
            new ConcurrentHashMap<Integer, Long>(32);

    // ���ֵ
    private int maxDelayLevel;

    // ��ʱ��
    private final Timer timer = new Timer("ScheduleMessageTimerThread", true);

    // �洢�������
    private final DefaultMessageStore defaultMessageStore;

    class DeliverDelayedMessageTimerTask extends TimerTask {
        private final int delayLevel;
        private final long offset;


        public DeliverDelayedMessageTimerTask(int delayLevel, long offset) {
            this.delayLevel = delayLevel;
            this.offset = offset;
        }


        @Override
        public void run() {
            try {
                this.executeOnTimeup();
            }
            catch (Exception e) {
                log.error("executeOnTimeup exception", e);
                ScheduleMessageService.this.timer.schedule(new DeliverDelayedMessageTimerTask(this.delayLevel,
                    this.offset), DELAY_FOR_A_PERIOD);
            }
        }


        private MessageExtBrokerInner messageTimeup(MessageExt msgExt) {
            MessageExtBrokerInner msgInner = new MessageExtBrokerInner();
            msgInner.setBody(msgExt.getBody());
            msgInner.setFlag(msgExt.getFlag());
            msgInner.setProperties(msgExt.getProperties());

            TopicFilterType topicFilterType =
                    (msgInner.getSysFlag() & MessageSysFlag.MultiTagsFlag) == MessageSysFlag.MultiTagsFlag ? TopicFilterType.MULTI_TAG
                            : TopicFilterType.SINGLE_TAG;
            long tagsCodeValue = MessageExtBrokerInner.tagsString2tagsCode(topicFilterType, msgInner.getTags());
            msgInner.setTagsCode(tagsCodeValue);
            msgInner.setPropertiesString(MessageDecoder.messageProperties2String(msgExt.getProperties()));

            msgInner.setSysFlag(msgExt.getSysFlag());
            msgInner.setBornTimestamp(msgExt.getBornTimestamp());
            msgInner.setBornHost(msgExt.getBornHost());
            msgInner.setStoreHost(msgExt.getStoreHost());
            msgInner.setReconsumeTimes(msgExt.getReconsumeTimes());

            msgInner.setWaitStoreMsgOK(false);
            msgInner.clearProperty(Message.PROPERTY_DELAY_TIME_LEVEL);

            // �ָ�Topic
            msgInner.setTopic(msgInner.getProperty(Message.PROPERTY_REAL_TOPIC));

            // �ָ�QueueId
            String queueIdStr = msgInner.getProperty(Message.PROPERTY_REAL_QUEUE_ID);
            int queueId = Integer.parseInt(queueIdStr);
            msgInner.setQueueId(queueId);

            return msgInner;
        }


        public void executeOnTimeup() {
            ConsumeQueue cq =
                    ScheduleMessageService.this.defaultMessageStore.findConsumeQueue(SCHEDULE_TOPIC,
                        delayLevel2QueueId(delayLevel));
            if (cq != null) {
                SelectMapedBufferResult bufferCQ = cq.getIndexBuffer(this.offset);
                if (bufferCQ != null) {
                    try {
                        long nextOffset = offset;
                        int i = 0;
                        for (; i < bufferCQ.getSize(); i += ConsumeQueue.CQStoreUnitSize) {
                            long offsetPy = bufferCQ.getByteBuffer().getLong();
                            int sizePy = bufferCQ.getByteBuffer().getInt();
                            long tagsCode = bufferCQ.getByteBuffer().getLong();

                            // ������洢��tagsCodeʵ����һ��ʱ���
                            long deliverTimestamp = tagsCode;

                            nextOffset = offset + (i / ConsumeQueue.CQStoreUnitSize);

                            long countdown = deliverTimestamp - System.currentTimeMillis();
                            // ʱ�䵽�ˣ���Ͷ��
                            if (countdown <= 0) {
                                MessageExt msgExt =
                                        ScheduleMessageService.this.defaultMessageStore.lookMessageByOffset(offsetPy,
                                            sizePy);
                                if (msgExt != null) {
                                    MessageExtBrokerInner msgInner = this.messageTimeup(msgExt);
                                    PutMessageResult putMessageResult =
                                            ScheduleMessageService.this.defaultMessageStore.putMessage(msgInner);
                                    // �ɹ�
                                    if (putMessageResult != null
                                            && putMessageResult.getPutMessageStatus() == PutMessageStatus.PUT_OK) {
                                        continue;
                                    }
                                    // ʧ��
                                    else {
                                        log.error("a message time up, but reput it failed, topic: {} msgId {}",
                                            msgExt.getTopic(), msgExt.getMsgId());
                                        ScheduleMessageService.this.timer.schedule(
                                            new DeliverDelayedMessageTimerTask(this.delayLevel, nextOffset),
                                            DELAY_FOR_A_PERIOD);
                                        ScheduleMessageService.this.updateOffset(this.delayLevel, nextOffset);
                                        return;
                                    }
                                }
                            }
                            // ʱ��δ����������ʱ
                            else {
                                ScheduleMessageService.this.timer.schedule(new DeliverDelayedMessageTimerTask(
                                    this.delayLevel, nextOffset), countdown);
                                ScheduleMessageService.this.updateOffset(this.delayLevel, nextOffset);
                                return;
                            }
                        } // end of for

                        nextOffset = offset + (i / ConsumeQueue.CQStoreUnitSize);
                        ScheduleMessageService.this.timer.schedule(new DeliverDelayedMessageTimerTask(
                            this.delayLevel, nextOffset), DELAY_FOR_A_WHILE);
                        ScheduleMessageService.this.updateOffset(this.delayLevel, nextOffset);
                        return;
                    }
                    finally {
                        // �����ͷ���Դ
                        bufferCQ.release();
                    }
                } // end of if (bufferCQ != null)
            } // end of if (cq != null)

            ScheduleMessageService.this.timer.schedule(new DeliverDelayedMessageTimerTask(this.delayLevel,
                this.offset), DELAY_FOR_A_WHILE);
        }
    }


    private void updateOffset(int delayLevel, long offset) {
        this.offsetTable.put(delayLevel, offset);
    }


    public ScheduleMessageService(final DefaultMessageStore defaultMessageStore) {
        this.defaultMessageStore = defaultMessageStore;
    }


    public static int queueId2DelayLevel(final int queueId) {
        return queueId + 1;
    }


    public static int delayLevel2QueueId(final int delayLevel) {
        return delayLevel - 1;
    }


    public long computeDeliverTimestamp(final int delayLevel, final long storeTimestamp) {
        Long time = this.delayLevelTable.get(delayLevel);
        if (time != null) {
            return time + storeTimestamp;
        }

        return storeTimestamp + 1000;
    }


    public boolean parseDelayLevel() {
        HashMap<String, Long> timeUnitTable = new HashMap<String, Long>();
        timeUnitTable.put("s", 1000L);
        timeUnitTable.put("m", 1000L * 60);
        timeUnitTable.put("h", 1000L * 60 * 60);
        timeUnitTable.put("d", 1000L * 60 * 60 * 24);

        String levelString = this.defaultMessageStore.getMessageStoreConfig().getMessageDelayLevel();
        try {
            String[] levelArray = levelString.split(" ");
            for (int i = 0; i < levelArray.length; i++) {
                String value = levelArray[i];
                String ch = value.substring(value.length() - 1);
                Long tu = timeUnitTable.get(ch);

                int level = i + 1;
                if (level > this.maxDelayLevel) {
                    this.maxDelayLevel = level;
                }
                long num = Long.parseLong(value.substring(0, value.length() - 1));
                long delayTimeMillis = tu * num;
                this.delayLevelTable.put(level, delayTimeMillis);
            }
        }
        catch (Exception e) {
            log.error("parseDelayLevel exception", e);
            log.info("levelString String = {}", levelString);
            return false;
        }

        return true;
    }


    public boolean load() {
        boolean result = this.parseDelayLevel();
        if (result) {
            String str = MixAll.file2String(this.defaultMessageStore.getMessageStoreConfig().getDelayOffsetStorePath());
            if (str != null) {
                Properties prop = MixAll.string2Properties(str);
                if (prop != null) {
                    Set<Object> keyset = prop.keySet();
                    for (Object object : keyset) {
                        String propValue = prop.getProperty(object.toString());
                        if (propValue != null) {
                            this.updateOffset(Integer.parseInt(object.toString()), Long.parseLong(propValue));
                            log.info("load delay offset table, LEVEL: {} OFFSET {}", object.toString(), propValue);
                        }
                    }
                }
            }
        }
        return result;
    }


    public void start() {
        // Ϊÿ����ʱ�������Ӷ�ʱ��
        for (Integer level : this.delayLevelTable.keySet()) {
            Long timeDelay = this.delayLevelTable.get(level);
            Long offset = this.offsetTable.get(level);
            if (null == offset) {
                offset = 0L;
            }

            if (timeDelay != null) {
                this.timer.schedule(new DeliverDelayedMessageTimerTask(level, offset), FIRST_DELAY_TIME);
            }
        }

        // ��ʱ����ʱ����ˢ��
        this.timer.scheduleAtFixedRate(new TimerTask() {

            @Override
            public void run() {
                try {
                    ScheduleMessageService.this.flush();
                }
                catch (Exception e) {
                    log.error("scheduleAtFixedRate flush exception", e);
                }
            }
        }, 10000, this.defaultMessageStore.getMessageStoreConfig().getFlushDelayOffsetInterval());
    }


    private void flush() {
        StringBuilder sb = new StringBuilder();
        for (Integer level : this.offsetTable.keySet()) {
            Long offset = this.offsetTable.get(level);
            if (null == offset) {
                offset = 0L;
            }

            sb.append(level + "=" + offset + IOUtils.LINE_SEPARATOR);
        }

        if (sb.toString().length() == 0)
            return;

        boolean result =
                MixAll.string2File(sb.toString(), this.defaultMessageStore.getMessageStoreConfig()
                    .getDelayOffsetStorePath());
        log.info("flush delay offset table, {}", (result ? "SUCCESS" : "FAILED"));
    }


    public void shutdown() {
        this.timer.cancel();
    }


    public int getMaxDelayLevel() {
        return maxDelayLevel;
    }
}