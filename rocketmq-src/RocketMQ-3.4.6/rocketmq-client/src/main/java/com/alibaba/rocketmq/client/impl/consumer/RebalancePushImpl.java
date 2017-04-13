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

import com.alibaba.rocketmq.client.consumer.AllocateMessageQueueStrategy;
import com.alibaba.rocketmq.client.consumer.store.OffsetStore;
import com.alibaba.rocketmq.client.consumer.store.ReadOffsetType;
import com.alibaba.rocketmq.client.exception.MQClientException;
import com.alibaba.rocketmq.client.impl.factory.MQClientInstance;
import com.alibaba.rocketmq.common.MixAll;
import com.alibaba.rocketmq.common.UtilAll;
import com.alibaba.rocketmq.common.consumer.ConsumeFromWhere;
import com.alibaba.rocketmq.common.message.MessageQueue;
import com.alibaba.rocketmq.common.protocol.heartbeat.ConsumeType;
import com.alibaba.rocketmq.common.protocol.heartbeat.MessageModel;

import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;


/**
 * //selectOneMessageQueue  messageQueueList ��Ͷ����Ϣ��ʱ���Ӧ��topic���У�ÿ��Ͷ�ݵ�ʱ������ѡ�����Ͷ�ݣ���ROCKET�����ֲ�7.8��
 //rebalance��ص���������ѣ������ж������������ͬһ��topic����topic��10�����У���������1����1-5���У�������2����6-10���ˣ���ROCKETMQ�����ֲ�7-5
 * @author shijia.wxr  ����ʹ���� DefaultMQPushConsumerImpl
 */
public class RebalancePushImpl extends RebalanceImpl {
    private final DefaultMQPushConsumerImpl defaultMQPushConsumerImpl;


    public RebalancePushImpl(DefaultMQPushConsumerImpl defaultMQPushConsumerImpl) {
        this(null, null, null, null, defaultMQPushConsumerImpl);
    }

    public RebalancePushImpl(String consumerGroup, MessageModel messageModel,
            AllocateMessageQueueStrategy allocateMessageQueueStrategy, MQClientInstance mQClientFactory,
            DefaultMQPushConsumerImpl defaultMQPushConsumerImpl) {
        super(consumerGroup, messageModel, allocateMessageQueueStrategy, mQClientFactory);
        this.defaultMQPushConsumerImpl = defaultMQPushConsumerImpl;
    }

    //updateProcessQueueTableInRebalance-> RebalancePushImpl.dispatchPullRequest

    //�ڰ�topic��rebalance������ʱ��PullRequest���ַ���ȥ, һ��PullRequest��Ӧһ�������߷����topic��ĳһ�����е����ѡ�
    //Ҳ���Ǵ��� PullMessageService.pullRequestQueue��
    @Override
    public void dispatchPullRequest(List<PullRequest> pullRequestList) {
        for (PullRequest pullRequest : pullRequestList) {
            this.defaultMQPushConsumerImpl.executePullRequestImmediately(pullRequest);
            log.info("doRebalance, {}, add a new pull request {}", consumerGroup, pullRequest);
        }
    }

    @Override
    public long computePullFromWhere(MessageQueue mq) {
        long result = -1;
        final ConsumeFromWhere consumeFromWhere =
                this.defaultMQPushConsumerImpl.getDefaultMQPushConsumer().getConsumeFromWhere();
        final OffsetStore offsetStore = this.defaultMQPushConsumerImpl.getOffsetStore();
        switch (consumeFromWhere) {
        case CONSUME_FROM_LAST_OFFSET_AND_FROM_MIN_WHEN_BOOT_FIRST:
        case CONSUME_FROM_MIN_OFFSET:
        case CONSUME_FROM_MAX_OFFSET:
        case CONSUME_FROM_LAST_OFFSET: { //��remote broker��ȡ���е�����λ�㡣
            long lastOffset = offsetStore.readOffset(mq, ReadOffsetType.READ_FROM_STORE);
            if (lastOffset >= 0) { //�����������ߴ洢���Ǹ�λ�㿪ʼ��
                result = lastOffset;
            }
            // First start,no offset
            else if (-1 == lastOffset) { //��Ⱥ��һ���������ѵ�ʱ�򣬴�broker��ȡ������ʷλ��ʱ��  ����Ƿ��������topic, ��Ҫ���ݵ���ʼλ�㣬
                //���������µ�λ�㡣
                if (mq.getTopic().startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX)) {
                    result = 0L;
                }
                else {
                    try {
                        result = this.mQClientFactory.getMQAdminImpl().maxOffset(mq);
                    }
                    catch (MQClientException e) {
                        result = -1;
                    }
                }
            }
            else {
                result = -1;
            }
            break;
        }
        case CONSUME_FROM_FIRST_OFFSET: {
            long lastOffset = offsetStore.readOffset(mq, ReadOffsetType.READ_FROM_STORE);
            if (lastOffset >= 0) { //��broker��ȡ������ʷλ�㣬�����ʷλ�㿪ʼ��
                result = lastOffset;
            }
            else if (-1 == lastOffset) { //��broker��ȡ������ʷλ�㣬�����ʼλ�㿪ʼ��
                result = 0L;
            }
            else {
                result = -1;
            }
            break;
        }
        case CONSUME_FROM_TIMESTAMP: {
            long lastOffset = offsetStore.readOffset(mq, ReadOffsetType.READ_FROM_STORE);
            if (lastOffset >= 0) {
                result = lastOffset;
            }
            else if (-1 == lastOffset) {
                if (mq.getTopic().startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX)) {
                    try {
                        result = this.mQClientFactory.getMQAdminImpl().maxOffset(mq);
                    }
                    catch (MQClientException e) {
                        result = -1;
                    }
                }
                else {
                    try {
                        /**
                         *  //��ָ����ʱ�����ʼ����λ�����ѣ� ͨ��ʱ�����λλ��ķ������£�
                         //��ͨ��consume queue��mapfile������޸�ʱ�䣨�Ȳ���ʱ��Ҫ�� �ҵ�mapfile,
                         //Ȼ���mapfile��ͷ��ʼ�����ֲ��ң���consume queue��item�ҵ�commitlog�е���Ϣ��
                         //����Ϣ�Ĵ洢ʱ��Ͳ�ѯʱ�����ȶԣ�ֱ����ȷ��λ�����߶�λ�������ѯʱ����С���Ǹ���Ϣ��
                         */
                        long timestamp =
                                UtilAll.parseDate(
                                    this.defaultMQPushConsumerImpl.getDefaultMQPushConsumer()
                                        .getConsumeTimestamp(), UtilAll.yyyyMMddHHmmss).getTime();
                        result = this.mQClientFactory.getMQAdminImpl().searchOffset(mq, timestamp);
                    }
                    catch (MQClientException e) {
                        result = -1;
                    }
                }
            }
            else {
                result = -1;
            }
            break;
        }

        default:
            break;
        }

        return result;
    }


    @Override
    public void messageQueueChanged(String topic, Set<MessageQueue> mqAll, Set<MessageQueue> mqDivided) {
    }


    @Override
    public boolean removeUnnecessaryMessageQueue(MessageQueue mq, ProcessQueue pq) {
        this.defaultMQPushConsumerImpl.getOffsetStore().persist(mq); //����λ��ͬ����broker .
        this.defaultMQPushConsumerImpl.getOffsetStore().removeOffset(mq); //�����ش洢�Ķ�������λ�㡣
        if (this.defaultMQPushConsumerImpl.isConsumeOrderly()
                && MessageModel.CLUSTERING.equals(this.defaultMQPushConsumerImpl.messageModel())) {
            try {
                if (pq.getLockConsume().tryLock(1000, TimeUnit.MILLISECONDS)) {
                    try {
                        this.unlock(mq, true);
                        return true;
                    }
                    finally {
                        pq.getLockConsume().unlock();
                    }
                }
                else {
                    log.warn(
                        "[WRONG]mq is consuming, so can not unlock it, {}. maybe hanged for a while, {}",//
                        mq,//
                        pq.getTryUnlockTimes());

                    pq.incTryUnlockTimes();
                }
            }
            catch (Exception e) {
                log.error("removeUnnecessaryMessageQueue Exception", e);
            }

            return false;
        }
        return true;
    }


    @Override
    public ConsumeType consumeType() {
        return ConsumeType.CONSUME_PASSIVELY;
    }
}
