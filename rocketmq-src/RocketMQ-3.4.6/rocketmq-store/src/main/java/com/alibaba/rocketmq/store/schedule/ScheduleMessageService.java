/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.alibaba.rocketmq.store.schedule;

import com.alibaba.rocketmq.common.ConfigManager;
import com.alibaba.rocketmq.common.TopicFilterType;
import com.alibaba.rocketmq.common.constant.LoggerName;
import com.alibaba.rocketmq.common.message.MessageAccessor;
import com.alibaba.rocketmq.common.message.MessageConst;
import com.alibaba.rocketmq.common.message.MessageDecoder;
import com.alibaba.rocketmq.common.message.MessageExt;
import com.alibaba.rocketmq.common.running.RunningStats;
import com.alibaba.rocketmq.store.*;
import com.alibaba.rocketmq.store.config.StorePathConfigHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;


/**
 *
 *
 *  �����ӳ���Ϣ�Ĵ���ʽ�������ģ�
 *  ����ϵͳԤ���� "1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h" �������ɸ��ӳ�level;
 *  Ȼ�� broker�ϴ�����һ�������ӳ���ϢͶ�ݵ�topic�� SCHEDULE_TOPIC_XXXX��  ���topic�����ɶ��У� ÿһ�����ж�Ӧ��һ���ӳ�level ��
 *
 *  Ͷ����Ͷ����Ϣ�Ժ�broker�������Ϣ��delay level ��д�� SCHEDULE_TOPIC_XXXX ��Ӧ�Ķ��У�ͬʱ��commitlog����Ϣ������������topic��
 *  ����Id���ԡ� SCHEDULE_TOPIC_XXXX ��ÿһ�����ж�Ӧ��һ��timer , ��ʱͨ������(consume queue���������ȡcommitlog ����Ϣ ��
 *  ��������ӳ�Ͷ��ʱ�䣬��д����ʵtopic�Ķ��С�
 *
 *
 *  һ�仰�� �����Ȱ���ϢͶ�ݵ�delay topic�ݴ棬Ȼ��ͨ����ʱ����delay topic�ݴ����ϢͶ�ݵ���ʵ��topic.
 *
 *
 *
 * @author shijia.wxr
 */
public class ScheduleMessageService extends ConfigManager {
    public static final String SCHEDULE_TOPIC = "SCHEDULE_TOPIC_XXXX"; //�ӳ���ϢTOPIC
    private static final Logger log = LoggerFactory.getLogger(LoggerName.StoreLoggerName);
    private static final long FIRST_DELAY_TIME = 1000L;
    private static final long DELAY_FOR_A_WHILE = 100L;
    private static final long DELAY_FOR_A_PERIOD = 10000L;
    //��¼ÿһ���ӳ�level���ӳٺ���, level��1��ʼ��
    private final ConcurrentHashMap<Integer /* level */, Long/* delay timeMillis */> delayLevelTable =
            new ConcurrentHashMap<Integer, Long>(32);
    //��¼ÿһ���ӳ�level��Ӧ�� consume queue(���Ѷ��У����߼�λ�㡣
    private final ConcurrentHashMap<Integer /* level */, Long/* offset */> offsetTable =
            new ConcurrentHashMap<Integer, Long>(32);
    private final Timer timer = new Timer("ScheduleMessageTimerThread", true);
    private final DefaultMessageStore defaultMessageStore;
    private int maxDelayLevel;


    public ScheduleMessageService(final DefaultMessageStore defaultMessageStore) {
        this.defaultMessageStore = defaultMessageStore;
    }


    public void buildRunningStats(HashMap<String, String> stats) {
        Iterator<Entry<Integer, Long>> it = this.offsetTable.entrySet().iterator();
        while (it.hasNext()) {
            Entry<Integer, Long> next = it.next();
            int queueId = delayLevel2QueueId(next.getKey());
            long delayOffset = next.getValue();
            long maxOffset = this.defaultMessageStore.getMaxOffsetInQuque(SCHEDULE_TOPIC, queueId);
            String value = String.format("%d,%d", delayOffset, maxOffset);
            String key = String.format("%s_%d", RunningStats.scheduleMessageOffset.name(), next.getKey());
            stats.put(key, value);
        }
    }


    public static int queueId2DelayLevel(final int queueId) {
        return queueId + 1;
    }


    public static int delayLevel2QueueId(final int delayLevel) {
        return delayLevel - 1;
    }


    private void updateOffset(int delayLevel, long offset) {
        this.offsetTable.put(delayLevel, offset);
    }


    public long computeDeliverTimestamp(final int delayLevel, final long storeTimestamp) {
        Long time = this.delayLevelTable.get(delayLevel);
        if (time != null) {
            return time + storeTimestamp;
        }

        return storeTimestamp + 1000;
    }


    public void start() {
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

        this.timer.scheduleAtFixedRate(new TimerTask() {

            @Override
            public void run() {
                try {
                    ScheduleMessageService.this.persist();
                }
                catch (Exception e) {
                    log.error("scheduleAtFixedRate flush exception", e);
                }
            }
        }, 10000, this.defaultMessageStore.getMessageStoreConfig().getFlushDelayOffsetInterval());
    }


    public void shutdown() {
        this.timer.cancel();
    }


    public int getMaxDelayLevel() {
        return maxDelayLevel;
    }


    public String encode() {
        return this.encode(false);
    }


    public String encode(final boolean prettyFormat) {
        DelayOffsetSerializeWrapper delayOffsetSerializeWrapper = new DelayOffsetSerializeWrapper();
        delayOffsetSerializeWrapper.setOffsetTable(this.offsetTable);
        return delayOffsetSerializeWrapper.toJson(prettyFormat);
    }

    //�����ļ� root/store/config/delayOffset.json �е����ݵ� ScheduleMessageService.offsetTable
    @Override
    public void decode(String jsonString) { //ConfigManager.configFilePath��ִ��
        if (jsonString != null) {
            DelayOffsetSerializeWrapper delayOffsetSerializeWrapper =
                    DelayOffsetSerializeWrapper.fromJson(jsonString, DelayOffsetSerializeWrapper.class);
            if (delayOffsetSerializeWrapper != null) {
                this.offsetTable.putAll(delayOffsetSerializeWrapper.getOffsetTable());
            }
        }
    }

    /*
    * {"offsetTable":{12:269,6:65,13:297,5:122,7:118,8:158,9:164,10:280,3:5100699,11:279,4:1514}}
    * */
    //root/store/config/delayOffset.json
    @Override
    public String configFilePath() { //ConfigManager.configFilePath��ִ��
        return StorePathConfigHelper.getDelayOffsetStorePath(this.defaultMessageStore.getMessageStoreConfig()
            .getStorePathRootDir());
    }


    public boolean load() { //DefaultMessageStore.load    configFilePath(//root/store/config/delayOffset.json) ��ִ��
        boolean result = super.load();
        result = result && this.parseDelayLevel();
        return result;
    }


    public boolean parseDelayLevel() {
        HashMap<String, Long> timeUnitTable = new HashMap<String, Long>();
        timeUnitTable.put("s", 1000L);
        timeUnitTable.put("m", 1000L * 60);
        timeUnitTable.put("h", 1000L * 60 * 60);
        timeUnitTable.put("d", 1000L * 60 * 60 * 24);

        //Ĭ������ "1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h"
        String levelString = this.defaultMessageStore.getMessageStoreConfig().getMessageDelayLevel();
        try {
            String[] levelArray = levelString.split(" ");
            for (int i = 0; i < levelArray.length; i++) {
                String value = levelArray[i];
                String ch = value.substring(value.length() - 1); //ʱ�䵥λ�� s, m , h , d
                Long tu = timeUnitTable.get(ch); //��ʱ�䵥λ����ɺ��� ��

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
                log.error("ScheduleMessageService, executeOnTimeup exception", e);
                ScheduleMessageService.this.timer.schedule(new DeliverDelayedMessageTimerTask(
                    this.delayLevel, this.offset), DELAY_FOR_A_PERIOD);
            }
        }


        private long correctDeliverTimestamp(final long now, final long deliverTimestamp) {
            long result = deliverTimestamp;
            //���Ͷ��ʱ�䡣
            long maxTimestamp = now + ScheduleMessageService.this.delayLevelTable.get(this.delayLevel);
            if (deliverTimestamp > maxTimestamp) { //�Ѿ��������Ͷ��ʱ�䣬���Ͷ��ʱ������Ϊ��ǰʱ�䡣
                result = now;
            }

            return result;
        }


        public void executeOnTimeup() {
            ConsumeQueue cq =
                    ScheduleMessageService.this.defaultMessageStore.findConsumeQueue(SCHEDULE_TOPIC,
                        delayLevel2QueueId(delayLevel));

            long failScheduleOffset = offset;

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

                            long now = System.currentTimeMillis();
                            long deliverTimestamp = this.correctDeliverTimestamp(now, tagsCode);

                            nextOffset = offset + (i / ConsumeQueue.CQStoreUnitSize);

                            long countdown = deliverTimestamp - now;
                            if (countdown <= 0) { //����Ͷ��ʱ�䣬 �����Ϣд����ʵtopic�Ķ��С�
                                MessageExt msgExt =
                                        ScheduleMessageService.this.defaultMessageStore.lookMessageByOffset(
                                            offsetPy, sizePy);

                                if (msgExt != null) {
                                    try {
                                        MessageExtBrokerInner msgInner = this.messageTimeup(msgExt);
                                        PutMessageResult putMessageResult =
                                                ScheduleMessageService.this.defaultMessageStore
                                                    .putMessage(msgInner);
                                        if (putMessageResult != null
                                                && putMessageResult.getPutMessageStatus() == PutMessageStatus.PUT_OK) {
                                            continue;
                                        }
                                        else {
                                            log.error(
                                                "ScheduleMessageService, a message time up, but reput it failed, topic: {} msgId {}",
                                                msgExt.getTopic(), msgExt.getMsgId());
                                            ScheduleMessageService.this.timer.schedule(
                                                new DeliverDelayedMessageTimerTask(this.delayLevel,
                                                    nextOffset), DELAY_FOR_A_PERIOD);
                                            ScheduleMessageService.this.updateOffset(this.delayLevel,
                                                nextOffset);
                                            return;
                                        }
                                    }
                                    catch (Exception e) {
                                        log.error(
                                            "ScheduleMessageService, messageTimeup execute error, drop it. msgExt="
                                                    + msgExt + ", nextOffset=" + nextOffset + ",offsetPy="
                                                    + offsetPy + ",sizePy=" + sizePy, e);
                                    }
                                }
                            }
                            else { //û����ϢͶ��ʱ�䣬���ύһ���ӳ����� ��ָ����ʱ������ deliverTimestamp - now �� ����
                                ScheduleMessageService.this.timer.schedule(
                                    new DeliverDelayedMessageTimerTask(this.delayLevel, nextOffset),
                                    countdown);
                                ScheduleMessageService.this.updateOffset(this.delayLevel, nextOffset);
                                return;
                            }
                        }
                        //һ��������һ����Ϣ����Ͷ�ݵ�����ʵ��topic�Ķ��У���ǰ���߼�λ�㲢�־û���broker��json�ļ��� Ȼ�������һ������
                        nextOffset = offset + (i / ConsumeQueue.CQStoreUnitSize);
                        ScheduleMessageService.this.timer.schedule(new DeliverDelayedMessageTimerTask(
                            this.delayLevel, nextOffset), DELAY_FOR_A_WHILE);
                        ScheduleMessageService.this.updateOffset(this.delayLevel, nextOffset);
                        return;
                    }
                    finally {
                        bufferCQ.release();
                    }
                } // end of if (bufferCQ != null)
                else {
                    long cqMinOffset = cq.getMinOffsetInQuque();
                    if (offset < cqMinOffset) {
                        failScheduleOffset = cqMinOffset;
                        log.error("schedule CQ offset invalid. offset=" + offset + ", cqMinOffset="
                                + cqMinOffset + ", queueId=" + cq.getQueueId());
                    }
                }
            } // end of if (cq != null)

            ScheduleMessageService.this.timer.schedule(new DeliverDelayedMessageTimerTask(this.delayLevel,
                failScheduleOffset), DELAY_FOR_A_WHILE);
        }


        private MessageExtBrokerInner messageTimeup(MessageExt msgExt) {
            MessageExtBrokerInner msgInner = new MessageExtBrokerInner();
            msgInner.setBody(msgExt.getBody());
            msgInner.setFlag(msgExt.getFlag());
            MessageAccessor.setProperties(msgInner, msgExt.getProperties());

            TopicFilterType topicFilterType = MessageExt.parseTopicFilterType(msgInner.getSysFlag());
            long tagsCodeValue =
                    MessageExtBrokerInner.tagsString2tagsCode(topicFilterType, msgInner.getTags());
            msgInner.setTagsCode(tagsCodeValue);
            msgInner.setPropertiesString(MessageDecoder.messageProperties2String(msgExt.getProperties()));

            msgInner.setSysFlag(msgExt.getSysFlag());
            msgInner.setBornTimestamp(msgExt.getBornTimestamp());
            msgInner.setBornHost(msgExt.getBornHost());
            msgInner.setStoreHost(msgExt.getStoreHost());
            msgInner.setReconsumeTimes(msgExt.getReconsumeTimes());

            msgInner.setWaitStoreMsgOK(false);
            MessageAccessor.clearProperty(msgInner, MessageConst.PROPERTY_DELAY_TIME_LEVEL);

            msgInner.setTopic(msgInner.getProperty(MessageConst.PROPERTY_REAL_TOPIC));

            String queueIdStr = msgInner.getProperty(MessageConst.PROPERTY_REAL_QUEUE_ID);
            int queueId = Integer.parseInt(queueIdStr);
            msgInner.setQueueId(queueId);

            return msgInner;
        }
    }
}
