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
package com.alibaba.rocketmq.client.impl.consumer;

import com.alibaba.rocketmq.client.consumer.DefaultMQPushConsumer;
import com.alibaba.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import com.alibaba.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import com.alibaba.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import com.alibaba.rocketmq.client.hook.ConsumeMessageContext;
import com.alibaba.rocketmq.client.log.ClientLogger;
import com.alibaba.rocketmq.client.stat.ConsumerStatsManager;
import com.alibaba.rocketmq.common.MixAll;
import com.alibaba.rocketmq.common.ThreadFactoryImpl;
import com.alibaba.rocketmq.common.message.MessageConst;
import com.alibaba.rocketmq.common.message.MessageExt;
import com.alibaba.rocketmq.common.message.MessageQueue;
import com.alibaba.rocketmq.common.protocol.body.CMResult;
import com.alibaba.rocketmq.common.protocol.body.ConsumeMessageDirectlyResult;
import com.alibaba.rocketmq.remoting.common.RemotingHelper;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;


/**
 * @author shijia.wxr
 */
public class ConsumeMessageConcurrentlyService implements ConsumeMessageService {
    private static final Logger log = ClientLogger.getLog();
    private final DefaultMQPushConsumerImpl defaultMQPushConsumerImpl;
    private final DefaultMQPushConsumer defaultMQPushConsumer;
    private final MessageListenerConcurrently messageListener;
    private final BlockingQueue<Runnable> consumeRequestQueue; //�������
    private final ThreadPoolExecutor consumeExecutor; //���̳�
    private final String consumerGroup;

    //����ֻ��һ���̵߳��̳߳أ���������ָ���ӳٺ�ִ���߳�����  ScheduledExecutorService��ʱ����ִ��ָ��������,�߳�����������submitConsumeRequestLater
    private final ScheduledExecutorService scheduledExecutorService;

    /* ���̳س�ʼ�� */
    public ConsumeMessageConcurrentlyService(DefaultMQPushConsumerImpl defaultMQPushConsumerImpl,
            MessageListenerConcurrently messageListener) {
        this.defaultMQPushConsumerImpl = defaultMQPushConsumerImpl;
        this.messageListener = messageListener;

        this.defaultMQPushConsumer = this.defaultMQPushConsumerImpl.getDefaultMQPushConsumer();
        this.consumerGroup = this.defaultMQPushConsumer.getConsumerGroup();
        this.consumeRequestQueue = new LinkedBlockingQueue<Runnable>();

        this.consumeExecutor = new ThreadPoolExecutor(//
            this.defaultMQPushConsumer.getConsumeThreadMin(),//
            this.defaultMQPushConsumer.getConsumeThreadMax(),//
            1000 * 60,//
            TimeUnit.MILLISECONDS,//
            this.consumeRequestQueue,//
            new ThreadFactoryImpl("ConsumeMessageThread_"));

        //����ֻ��һ���̵߳��̳߳أ���������ָ���ӳٺ�ִ���߳�����  ScheduledExecutorService��ʱ����ִ��ָ�������� �߳���������submitConsumeRequestLater
        this.scheduledExecutorService =
                Executors.newSingleThreadScheduledExecutor(new ThreadFactoryImpl(
                    "ConsumeMessageScheduledThread_"));
    }


    public void start() { /* �����\�Ќ��H���������� ConsumeRequest */
    }


    public void shutdown() {
        this.scheduledExecutorService.shutdown();
        this.consumeExecutor.shutdown();
    }


    public ConsumerStatsManager getConsumerStatsManager() {
        return this.defaultMQPushConsumerImpl.getConsumerStatsManager();
    }


    class ConsumeRequest implements Runnable {
        private final List<MessageExt> msgs;
        private final ProcessQueue processQueue;
        private final MessageQueue messageQueue;

        //// ConsumeMessageConcurrentlyService.submitConsumeRequest  msgs��Դ������
        public ConsumeRequest(List<MessageExt> msgs, ProcessQueue processQueue, MessageQueue messageQueue) {
            this.msgs = msgs;
            this.processQueue = processQueue;
            this.messageQueue = messageQueue;
        }


        @Override
        public void run() { ////ConsumeMessageConcurrentlyService.submitConsumeRequest �д���ִ��
            if (this.processQueue.isDropped()) {
                log.info("the message queue not be able to consume, because it's dropped {}",
                    this.messageQueue);
                return;
            }

            /* �����listenerҲ����consumer����proxy��registerMessageListener��lister */
            MessageListenerConcurrently listener = ConsumeMessageConcurrentlyService.this.messageListener;
            ConsumeConcurrentlyContext context = new ConsumeConcurrentlyContext(messageQueue);
            ConsumeConcurrentlyStatus status = null;

            ConsumeMessageContext consumeMessageContext = null;
            if (ConsumeMessageConcurrentlyService.this.defaultMQPushConsumerImpl.hasHook()) {
                consumeMessageContext = new ConsumeMessageContext();
                consumeMessageContext
                    .setConsumerGroup(ConsumeMessageConcurrentlyService.this.defaultMQPushConsumer
                        .getConsumerGroup());
                consumeMessageContext.setMq(messageQueue);
                consumeMessageContext.setMsgList(msgs);
                consumeMessageContext.setSuccess(false);
                ConsumeMessageConcurrentlyService.this.defaultMQPushConsumerImpl
                    .executeHookBefore(consumeMessageContext);
            }

            long beginTimestamp = System.currentTimeMillis();

            /* ҵ��������������Ϣ�����������ѽ����RECONSUME_OK,ʧ�ܷ��� RECONSUME_LATER */
            try {
                ConsumeMessageConcurrentlyService.this.resetRetryTopic(msgs);
                //ҵ�������￪ʼ������Ϣ  ���� pullConsumer���е�consumer.registerMessageListener()�ص�����������ִ��
                status = listener.consumeMessage(Collections.unmodifiableList(msgs), context);
            }
            catch (Throwable e) {
                log.warn("consumeMessage exception: {} Group: {} Msgs: {} MQ: {}",//
                    RemotingHelper.exceptionSimpleDesc(e),//
                    ConsumeMessageConcurrentlyService.this.consumerGroup,//
                    msgs,//
                    messageQueue);
            }

            long consumeRT = System.currentTimeMillis() - beginTimestamp;

            if (null == status) { //���ҵ��consumer�ķ���ֵΪ�գ���Ĭ����Ҫ��������
                log.warn("consumeMessage return null, Group: {} Msgs: {} MQ: {}",//
                    ConsumeMessageConcurrentlyService.this.consumerGroup,//
                    msgs,//
                    messageQueue);
                status = ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }

            if (ConsumeMessageConcurrentlyService.this.defaultMQPushConsumerImpl.hasHook()) {
                consumeMessageContext.setStatus(status.toString());
                consumeMessageContext.setSuccess(ConsumeConcurrentlyStatus.CONSUME_SUCCESS == status);
                ConsumeMessageConcurrentlyService.this.defaultMQPushConsumerImpl
                    .executeHookAfter(consumeMessageContext);
            }

            ConsumeMessageConcurrentlyService.this.getConsumerStatsManager().incConsumeRT(
                ConsumeMessageConcurrentlyService.this.consumerGroup, messageQueue.getTopic(), consumeRT);

            if (!processQueue.isDropped()) {
                //��ҵ�����ص�status CONSUME_SUCCESS����RECONSUME_LATER����Ӧ�Ĵ���
                ConsumeMessageConcurrentlyService.this.processConsumeResult(status, context, this);
            }
            else {
                log.warn("processQueue is dropped without process consume result. messageQueue={}, msgs={}",
                    messageQueue, msgs);
            }
        }


        public List<MessageExt> getMsgs() {
            return msgs;
        }


        public ProcessQueue getProcessQueue() {
            return processQueue;
        }


        public MessageQueue getMessageQueue() {
            return messageQueue;
        }
    }

    //ConsumeMessageConcurrentlyService.processConsumeResult��ִ��
    //������Ϣ��broker������������Զ���
    public boolean sendMessageBack(final MessageExt msg, final ConsumeConcurrentlyContext context) {
        int delayLevel = context.getDelayLevelWhenNextConsume();

        try {
            this.defaultMQPushConsumerImpl.sendMessageBack(msg, delayLevel, context.getMessageQueue()
                .getBrokerName());
            return true;
        }
        catch (Exception e) {
            log.error("sendMessageBack exception, group: " + this.consumerGroup + " msg: " + msg.toString(),
                e);
        }

        return false;
    }


    /**
     *
     * ������δ����˼·��
     * ���ConsumeConcurrentlyStatus ��ʶ��ҵ������״̬�ǳɹ�����broker�϶��е�offsetλ��ǰ�ƣ�һ����Ϣ�����λ��+1��
     * ���ҵ������ʧ�ܣ����������Ϣ����Ͷ�ݻ�broker ������Ͷ��ȥ����Ϣ���ӳ�Ͷ�ݻ���������һ��backoff���ơ���
     * �� �����ֳַ����������
     * 1�� ����Ͷ�ݻ�ȥ�ɹ��� ���е�offset��Ȼǰ�ơ�
     * 2�� ����Ͷ�ݻ�ȥʧ�ܣ� ���������Ͷʧ�ܵ���Ϣ��������ҵ�������ͬʱ�� ���е�λ�������Ͷʧ�ܵ���Ϣ��ѡ��һ��С��λ�㡣
     * ����1,2,3,4,5 ��2��3��Ͷʧ�ܣ���λ�����2�� ����ʹ���һ���ظ����ѵ����⣨����rocketmq����֤��Ϣ���أ� ��һ����������
     *
     *
     * @param status
     * @param context
     * @param consumeRequest
     */
    public void processConsumeResult(//ConsumeRequest.run��ִ��
            final ConsumeConcurrentlyStatus status, //
            final ConsumeConcurrentlyContext context, //
            final ConsumeRequest consumeRequest//
    ) {
        int ackIndex = context.getAckIndex();

        if (consumeRequest.getMsgs().isEmpty())
            return;

        switch (status) {
        case CONSUME_SUCCESS:
            if (ackIndex >= consumeRequest.getMsgs().size()) {
                ackIndex = consumeRequest.getMsgs().size() - 1;
            }
            int ok = ackIndex + 1;
            int failed = consumeRequest.getMsgs().size() - ok;
            /* ҵ����������Ϣ������Ӧ��ͳ�� */
            this.getConsumerStatsManager().incConsumeOKTPS(consumerGroup,
                consumeRequest.getMessageQueue().getTopic(), ok);
            this.getConsumerStatsManager().incConsumeFailedTPS(consumerGroup,
                consumeRequest.getMessageQueue().getTopic(), failed);
            break;
        case RECONSUME_LATER:
            ackIndex = -1;
            this.getConsumerStatsManager().incConsumeFailedTPS(consumerGroup,
                consumeRequest.getMessageQueue().getTopic(), consumeRequest.getMsgs().size());
            break;
        default:
            break;
        }

        switch (this.defaultMQPushConsumer.getMessageModel()) {
        case BROADCASTING:
            for (int i = ackIndex + 1; i < consumeRequest.getMsgs().size(); i++) {
                MessageExt msg = consumeRequest.getMsgs().get(i);
                log.warn("BROADCASTING, the message consume failed, drop it, {}", msg.toString());
            }
            break;
        case CLUSTERING:
            //������δ������˼�ǣ� ���ҵ����Ϣ����ʧ��(ackIndex = -1) ����ô�Ͱ������Ѿ�����������ʧ�ܵ���Ϣ���´��broker
            List<MessageExt> msgBackFailed = new ArrayList<MessageExt>(consumeRequest.getMsgs().size());
            for (int i = ackIndex + 1; i < consumeRequest.getMsgs().size(); i++) { //ע������i�Ǵ�ackIndex + 1��ʼ
                MessageExt msg = consumeRequest.getMsgs().get(i);
                //������ʧ�ܵ���Ϣ���´��broker . ����broker�����Զ���

                boolean result = this.sendMessageBack(msg, context); //ע����������broker��ʱ��topic��Ϊ RETRY_GROUP_TOPIC_PREFIX + ConsumerGroup
                if (!result) {
                    //����ĸ�ֵʵ�����������sendMessageBack�л��õ�
                    msg.setReconsumeTimes(msg.getReconsumeTimes() + 1); //��msg��Ҫ�ͻ����ٴ����ѣ� ��һ����Ϣ��1���ڶ���ʧ�ܵľ���+2,�Դ�����
                    msgBackFailed.add(msg); //���brokerʧ�ܣ������msgBackFailed����
                }
            }
            //�������ʧ�ܵ���Ϣ���´��broker��Ȼʧ�ܣ������ЩmsgBackFailed��msgs����ȥ����ͬʱ�ύ��һ�������̳߳أ� ���5���Ժ��ٴ���Ϣ��
            if (!msgBackFailed.isEmpty()) {
                consumeRequest.getMsgs().removeAll(msgBackFailed);

                //��5s���ٴ�������Щ���brokerʧ�ܵ���Ϣ��ҵ�����ѣ���Ϊ
                this.submitConsumeRequestLater(msgBackFailed, consumeRequest.getProcessQueue(),
                    consumeRequest.getMessageQueue());
            }
            break;
        default:
            break;
        }

        //�������ʧ�ܵ���Ϣ���´��broker ��Ȼʧ�ܣ� ��ǰ���msgBackFailed �ǿգ���ôremoveMessage �������
        //�õ���λ��offset����һ����Ϣ����С���Ǹ�λ�㣬�����msgBackFailed Ϊ�գ�������offset ����������Ϣ�����λ��+1

        //getMsgs()��ȡ����msgs�����д洢��������ʧ�ܴ��broker�ɹ���msg�����ѳɹ���msg
        long offset = consumeRequest.getProcessQueue().removeMessage(consumeRequest.getMsgs());
        if (offset >= 0) { //λ�����
            this.defaultMQPushConsumerImpl.getOffsetStore().updateOffset(consumeRequest.getMessageQueue(),
                offset, true);
        }
    }

    //5ms���ٴ�����Ϣ��ҵ������
    private void submitConsumeRequestLater(//
            final List<MessageExt> msgs, //
            final ProcessQueue processQueue, //
            final MessageQueue messageQueue//
    ) {

        this.scheduledExecutorService.schedule(new Runnable() {

            @Override
            public void run() {
                ConsumeMessageConcurrentlyService.this.submitConsumeRequest(msgs, processQueue, messageQueue,
                    true);
            }
        }, 5000, TimeUnit.MILLISECONDS);
    }

    //DefaultMQPushConsumerImpl.pullMessage.PullCallback.onSuccess��ִ�У������Ľӿ��� ConsumeMessageConcurrentlyService.submitConsumeRequest
    @Override
    public void submitConsumeRequest(//
            final List<MessageExt> msgs, //
            final ProcessQueue processQueue, //
            final MessageQueue messageQueue, //
            final boolean dispatchToConsume) {
        final int consumeBatchSize = this.defaultMQPushConsumer.getConsumeMessageBatchMaxSize();
        if (msgs.size() <= consumeBatchSize) { //���msg��С��consumeBatchSize����һ������  ????? ����Ӧ��Ҫ����msgs.size() > 0
            ConsumeRequest consumeRequest = new ConsumeRequest(msgs, processQueue, messageQueue);
            this.consumeExecutor.submit(consumeRequest);//�̳߳�ִ�� ConsumeRequest.run
        }
        else { //����һ������������ѵ���Ϣ���� ��ֳɼ�������
            for (int total = 0; total < msgs.size();) {
                List<MessageExt> msgThis = new ArrayList<MessageExt>(consumeBatchSize);
                for (int i = 0; i < consumeBatchSize; i++, total++) {
                    if (total < msgs.size()) {
                        msgThis.add(msgs.get(total));
                    }
                    else {
                        break;
                    }
                }
                //
                ConsumeRequest consumeRequest = new ConsumeRequest(msgThis, processQueue, messageQueue);
                this.consumeExecutor.submit(consumeRequest); //����ִ�� ConsumeRequest.run
            }
        }
    }


    @Override
    public void updateCorePoolSize(int corePoolSize) {
        if (corePoolSize > 0 //
                && corePoolSize <= Short.MAX_VALUE //
                && corePoolSize < this.defaultMQPushConsumer.getConsumeThreadMax()) {
            this.consumeExecutor.setCorePoolSize(corePoolSize);
        }
    }


    @Override
    public void incCorePoolSize() {
//        long corePoolSize = this.consumeExecutor.getCorePoolSize();
//        if (corePoolSize < this.defaultMQPushConsumer.getConsumeThreadMax()) {
//            this.consumeExecutor.setCorePoolSize(this.consumeExecutor.getCorePoolSize() + 1);
//        }
//
//        log.info("incCorePoolSize Concurrently from {} to {}, ConsumerGroup: {}", //
//            corePoolSize,//
//            this.consumeExecutor.getCorePoolSize(),//
//            this.consumerGroup);
    }


    @Override
    public void decCorePoolSize() {
//        long corePoolSize = this.consumeExecutor.getCorePoolSize();
//        if (corePoolSize > this.defaultMQPushConsumer.getConsumeThreadMin()) {
//            this.consumeExecutor.setCorePoolSize(this.consumeExecutor.getCorePoolSize() - 1);
//        }
//
//        log.info("decCorePoolSize Concurrently from {} to {}, ConsumerGroup: {}", //
//            corePoolSize,//
//            this.consumeExecutor.getCorePoolSize(),//
//            this.consumerGroup);
    }


    @Override
    public int getCorePoolSize() {
        return this.consumeExecutor.getCorePoolSize();
    }


    @Override
    public ConsumeMessageDirectlyResult consumeMessageDirectly(MessageExt msg, String brokerName) {
        ConsumeMessageDirectlyResult result = new ConsumeMessageDirectlyResult();
        result.setOrder(false);
        result.setAutoCommit(true);

        List<MessageExt> msgs = new ArrayList<MessageExt>();
        msgs.add(msg);
        MessageQueue mq = new MessageQueue();
        mq.setBrokerName(brokerName);
        mq.setTopic(msg.getTopic());
        mq.setQueueId(msg.getQueueId());

        ConsumeConcurrentlyContext context = new ConsumeConcurrentlyContext(mq);

        this.resetRetryTopic(msgs);

        final long beginTime = System.currentTimeMillis();

        log.info("consumeMessageDirectly receive new messge: {}", msg);

        try {
            ConsumeConcurrentlyStatus status = this.messageListener.consumeMessage(msgs, context);
            if (status != null) {
                switch (status) {
                case CONSUME_SUCCESS:
                    result.setConsumeResult(CMResult.CR_SUCCESS);
                    break;
                case RECONSUME_LATER:
                    result.setConsumeResult(CMResult.CR_LATER);
                    break;
                default:
                    break;
                }
            }
            else {
                result.setConsumeResult(CMResult.CR_RETURN_NULL);
            }
        }
        catch (Throwable e) {
            result.setConsumeResult(CMResult.CR_THROW_EXCEPTION);
            result.setRemark(RemotingHelper.exceptionSimpleDesc(e));

            log.warn(String.format("consumeMessageDirectly exception: %s Group: %s Msgs: %s MQ: %s",//
                RemotingHelper.exceptionSimpleDesc(e),//
                ConsumeMessageConcurrentlyService.this.consumerGroup,//
                msgs,//
                mq), e);
        }

        result.setSpentTimeMills(System.currentTimeMillis() - beginTime);

        log.info("consumeMessageDirectly Result: {}", result);

        return result;
    }


    public void resetRetryTopic(final List<MessageExt> msgs) {
        final String groupTopic = MixAll.getRetryTopic(consumerGroup);
        for (MessageExt msg : msgs) {
            String retryTopic = msg.getProperty(MessageConst.PROPERTY_RETRY_TOPIC);
            if (retryTopic != null && groupTopic.equals(msg.getTopic())) {
                msg.setTopic(retryTopic);
            }
        }
    }
}
