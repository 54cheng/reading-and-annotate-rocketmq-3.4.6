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
import com.alibaba.rocketmq.client.impl.FindBrokerResult;
import com.alibaba.rocketmq.client.impl.factory.MQClientInstance;
import com.alibaba.rocketmq.client.log.ClientLogger;
import com.alibaba.rocketmq.common.MixAll;
import com.alibaba.rocketmq.common.message.MessageQueue;
import com.alibaba.rocketmq.common.protocol.body.LockBatchRequestBody;
import com.alibaba.rocketmq.common.protocol.body.UnlockBatchRequestBody;
import com.alibaba.rocketmq.common.protocol.heartbeat.ConsumeType;
import com.alibaba.rocketmq.common.protocol.heartbeat.MessageModel;
import com.alibaba.rocketmq.common.protocol.heartbeat.SubscriptionData;
import org.slf4j.Logger;

import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Base class for rebalance algorithm
 *
 * //selectOneMessageQueue  messageQueueList ��Ͷ����Ϣ��ʱ���Ӧ��topic���У�ÿ��Ͷ�ݵ�ʱ������ѡ�����Ͷ�ݣ���ROCKET�����ֲ�7.8��
 //rebalance��ص���������ѣ������ж������������ͬһ��topic����topic��10�����У���������1����1-5���У�������2����6-10���ˣ���ROCKETMQ�����ֲ�7-5
 *
 * //�� updateProcessQueueTableInRebalance �������Ķ�����Ϣת��Ϊrequest����뵽 PullMessageService.pullRequestQueue��Ȼ��
 //��PullMessageService.run ��ͨ���� pullRequestQueue �е�PullRequest����ȡ��Ϣ
 *
 * @author shijia.wxr    ������Ľӿ�ʵ���� RebalancePushImpl   RebalancePullImpl  ����ʹ����DefaultMQPushConsumerImpl
 */
public abstract class RebalanceImpl {
    protected static final Logger log = ClientLogger.getLog();
    ////MQClientInstance.start(this.rebalanceService.start();)�н��ж���rebalance�����ʱ�����¸�hashmap
    //ÿһ����Ϣ���У���Ӧһ��������С� RebalanceImpl.processQueueTable(MessageQueue----ProcessQueue) ͨ�� updateProcessQueueTableInRebalance ����
    protected final ConcurrentHashMap<MessageQueue, ProcessQueue> processQueueTable =
            new ConcurrentHashMap<MessageQueue, ProcessQueue>(64);
    //topic��Ӧ��queue��Ϣȫ���ڸ�hashmap�� RebalanceImpl.doRebalance ����ԓhashmap���������͵��Ǹ�����
    // DefaultMQPushConsumerImpl.updateTopicSubscribeInfo �н�����Ӹ�ֵ  ������洢��topic�����Ӧ��broker�ϵĶ�����Ϣ
    ////MQClientInstance.start(this.rebalanceService.start();)�н��ж���rebalance�����ʱ�����¸�hashmap
    protected final ConcurrentHashMap<String/* topic */, Set<MessageQueue>> topicSubscribeInfoTable =
            new ConcurrentHashMap<String, Set<MessageQueue>>();
    /* topic�� subscriptionData �����ݴ����map��  copySubscription ��subscribe�и�ֵ  RebalanceImpl.doRebalance ����ԓhashmap���������͵��Ǹ����� */
    protected final ConcurrentHashMap<String /* topic */, SubscriptionData> subscriptionInner =
            new ConcurrentHashMap<String, SubscriptionData>();
    protected String consumerGroup;
    protected MessageModel messageModel;
    protected AllocateMessageQueueStrategy allocateMessageQueueStrategy;
    protected MQClientInstance mQClientFactory;


    public RebalanceImpl(String consumerGroup, MessageModel messageModel,
                         AllocateMessageQueueStrategy allocateMessageQueueStrategy, MQClientInstance mQClientFactory) {
        this.consumerGroup = consumerGroup;
        this.messageModel = messageModel;
        this.allocateMessageQueueStrategy = allocateMessageQueueStrategy;
        this.mQClientFactory = mQClientFactory;
    }


    public abstract ConsumeType consumeType();


    public void unlock(final MessageQueue mq, final boolean oneway) {
        FindBrokerResult findBrokerResult =
                this.mQClientFactory.findBrokerAddressInSubscribe(mq.getBrokerName(), MixAll.MASTER_ID, true);
        if (findBrokerResult != null) {
            UnlockBatchRequestBody requestBody = new UnlockBatchRequestBody();
            requestBody.setConsumerGroup(this.consumerGroup);
            requestBody.setClientId(this.mQClientFactory.getClientId());
            requestBody.getMqSet().add(mq);

            try {
                this.mQClientFactory.getMQClientAPIImpl().unlockBatchMQ(findBrokerResult.getBrokerAddr(),
                        requestBody, 1000, oneway);
                log.warn("unlock messageQueue. group:{}, clientId:{}, mq:{}",//
                        this.consumerGroup, //
                        this.mQClientFactory.getClientId(), //
                        mq);
            } catch (Exception e) {
                log.error("unlockBatchMQ exception, " + mq, e);
            }
        }
    }


    public void unlockAll(final boolean oneway) {
        HashMap<String, Set<MessageQueue>> brokerMqs = this.buildProcessQueueTableByBrokerName();

        for (final Map.Entry<String, Set<MessageQueue>> entry : brokerMqs.entrySet()) {
            final String brokerName = entry.getKey();
            final Set<MessageQueue> mqs = entry.getValue();

            if (mqs.isEmpty())
                continue;

            FindBrokerResult findBrokerResult =
                    this.mQClientFactory.findBrokerAddressInSubscribe(brokerName, MixAll.MASTER_ID, true);
            if (findBrokerResult != null) {
                UnlockBatchRequestBody requestBody = new UnlockBatchRequestBody();
                requestBody.setConsumerGroup(this.consumerGroup);
                requestBody.setClientId(this.mQClientFactory.getClientId());
                requestBody.setMqSet(mqs);

                try {
                    this.mQClientFactory.getMQClientAPIImpl().unlockBatchMQ(findBrokerResult.getBrokerAddr(),
                            requestBody, 1000, oneway);

                    for (MessageQueue mq : mqs) {
                        ProcessQueue processQueue = this.processQueueTable.get(mq);
                        if (processQueue != null) {
                            processQueue.setLocked(false);
                            log.info("the message queue unlock OK, Group: {} {}", this.consumerGroup, mq);
                        }
                    }
                } catch (Exception e) {
                    log.error("unlockBatchMQ exception, " + mqs, e);
                }
            }
        }
    }


    private HashMap<String/* brokerName */, Set<MessageQueue>> buildProcessQueueTableByBrokerName() {
        HashMap<String, Set<MessageQueue>> result = new HashMap<String, Set<MessageQueue>>();
        for (MessageQueue mq : this.processQueueTable.keySet()) {
            Set<MessageQueue> mqs = result.get(mq.getBrokerName());
            if (null == mqs) {
                mqs = new HashSet<MessageQueue>();
                result.put(mq.getBrokerName(), mqs);
            }

            mqs.add(mq);
        }

        return result;
    }


    public boolean lock(final MessageQueue mq) {
        FindBrokerResult findBrokerResult =
                this.mQClientFactory.findBrokerAddressInSubscribe(mq.getBrokerName(), MixAll.MASTER_ID, true);
        if (findBrokerResult != null) {
            LockBatchRequestBody requestBody = new LockBatchRequestBody();
            requestBody.setConsumerGroup(this.consumerGroup);
            requestBody.setClientId(this.mQClientFactory.getClientId());
            requestBody.getMqSet().add(mq);

            try {
                Set<MessageQueue> lockedMq =
                        this.mQClientFactory.getMQClientAPIImpl().lockBatchMQ(
                                findBrokerResult.getBrokerAddr(), requestBody, 1000);
                for (MessageQueue mmqq : lockedMq) {
                    ProcessQueue processQueue = this.processQueueTable.get(mmqq);
                    if (processQueue != null) {
                        processQueue.setLocked(true);
                        processQueue.setLastLockTimestamp(System.currentTimeMillis());
                    }
                }

                boolean lockOK = lockedMq.contains(mq);
                log.info("the message queue lock {}, {} {}",//
                        (lockOK ? "OK" : "Failed"), //
                        this.consumerGroup, //
                        mq);
                return lockOK;
            } catch (Exception e) {
                log.error("lockBatchMQ exception, " + mq, e);
            }
        }

        return false;
    }


    public void lockAll() {
        HashMap<String, Set<MessageQueue>> brokerMqs = this.buildProcessQueueTableByBrokerName();

        Iterator<Entry<String, Set<MessageQueue>>> it = brokerMqs.entrySet().iterator();
        while (it.hasNext()) {
            Entry<String, Set<MessageQueue>> entry = it.next();
            final String brokerName = entry.getKey();
            final Set<MessageQueue> mqs = entry.getValue();

            if (mqs.isEmpty())
                continue;

            FindBrokerResult findBrokerResult =
                    this.mQClientFactory.findBrokerAddressInSubscribe(brokerName, MixAll.MASTER_ID, true);
            if (findBrokerResult != null) {
                LockBatchRequestBody requestBody = new LockBatchRequestBody();
                requestBody.setConsumerGroup(this.consumerGroup);
                requestBody.setClientId(this.mQClientFactory.getClientId());
                requestBody.setMqSet(mqs);

                try {
                    Set<MessageQueue> lockOKMQSet =
                            this.mQClientFactory.getMQClientAPIImpl().lockBatchMQ(
                                    findBrokerResult.getBrokerAddr(), requestBody, 1000);

                    for (MessageQueue mq : lockOKMQSet) {
                        ProcessQueue processQueue = this.processQueueTable.get(mq);
                        if (processQueue != null) {
                            if (!processQueue.isLocked()) {
                                log.info("the message queue locked OK, Group: {} {}", this.consumerGroup, mq);
                            }

                            processQueue.setLocked(true);
                            processQueue.setLastLockTimestamp(System.currentTimeMillis());
                        }
                    }
                    for (MessageQueue mq : mqs) {
                        if (!lockOKMQSet.contains(mq)) {
                            ProcessQueue processQueue = this.processQueueTable.get(mq);
                            if (processQueue != null) {
                                processQueue.setLocked(false);
                                log.warn("the message queue locked Failed, Group: {} {}", this.consumerGroup,
                                        mq);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.error("lockBatchMQ exception, " + mqs, e);
                }
            }
        }
    }
    //����Դͷ�� MQClientInstance.start(this.rebalanceService.start();)��ִ��
    //DefaultMQPushConsumerImpl.doRebalance����
    //������rebalance������ RebalanceImpl.doRebalance
    public void doRebalance() {
        Map<String, SubscriptionData> subTable = this.getSubscriptionInner();
        if (subTable != null) {
            for (final Map.Entry<String, SubscriptionData> entry : subTable.entrySet()) {
                //�������߶��ĵ�topic�ֽ�����rebalance���̣�
                final String topic = entry.getKey();
                try {
                    this.rebalanceByTopic(topic);
                } catch (Exception e) {
                    if (!topic.startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX)) {
                        log.warn("rebalanceByTopic Exception", e);
                    }
                }
            }
        }

        this.truncateMessageQueueNotMyTopic();
    }

    //�� updateProcessQueueTableInRebalance �������Ķ�����Ϣת��Ϊrequest����뵽 PullMessageService.pullRequestQueue��Ȼ��
    //��PullMessageService.run ��ͨ���� pullRequestQueue �е�PullRequest����ȡ��Ϣ
    private void rebalanceByTopic(final String topic) { //RebalanceImpl.rebalanceByTopic
        switch (messageModel) {
            case BROADCASTING: {
                Set<MessageQueue> mqSet = this.topicSubscribeInfoTable.get(topic);
                if (mqSet != null) {
                    boolean changed = this.updateProcessQueueTableInRebalance(topic, mqSet);
                    if (changed) {
                        this.messageQueueChanged(topic, mqSet, mqSet);
                        log.info("messageQueueChanged {} {} {} {}",//
                                consumerGroup,//
                                topic,//
                                mqSet,//
                                mqSet);
                    }
                } else {
                    log.warn("doRebalance, {}, but the topic[{}] not exist.", consumerGroup, topic);
                }
                break;
            }
            case CLUSTERING: { //
                //topic��Ӧ�����еĶ��м���
                Set<MessageQueue> mqSet = this.topicSubscribeInfoTable.get(topic);
                //�@ȡԓtopic���棬���M�߷ֽM��consumerGroup���������M��ID��
                List<String> cidAll = this.mQClientFactory.findConsumerIdList(topic, consumerGroup);
                if (null == mqSet) {
                    if (!topic.startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX)) {
                        log.warn("doRebalance, {}, but the topic[{}] not exist.", consumerGroup, topic);
                    }
                }

                if (null == cidAll) {
                    log.warn("doRebalance, {} {}, get consumer id list failed", consumerGroup, topic);
                }

                if (mqSet != null && cidAll != null) { //���Ѷ��к�������id����Ϊ�ա�
                    List<MessageQueue> mqAll = new ArrayList<MessageQueue>();
                    mqAll.addAll(mqSet);

                    Collections.sort(mqAll); //��topic�µ�����queue����
                    Collections.sort(cidAll); //��topic�������ѷ��鶼�Ǹ�consumerGroup�����пͻ���ID

                    AllocateMessageQueueStrategy strategy = this.allocateMessageQueueStrategy;

                    //��ȡ���ͻ���Ӧ��������Щ����
                    List<MessageQueue> allocateResult = null;
                    try { //AllocateMessageQueueAveragely  AllocateMessageQueueByMachineRoom  AllocateMessageQueueByConfig
                        allocateResult = strategy.allocate(
                                this.consumerGroup, //
                                this.mQClientFactory.getClientId(), //
                                mqAll,//
                                cidAll);
                    } catch (Throwable e) {
                        log.error(
                                "AllocateMessageQueueStrategy.allocate Exception. allocateMessageQueueStrategyName={}",
                                strategy.getName(), e);
                        return;
                    }

                    //����rebalance�㷨��ȡ����ǰ������Ӧ�ùҽӵ����Ѷ��С�
                    Set<MessageQueue> allocateResultSet = new HashSet<MessageQueue>();
                    if (allocateResult != null) {
                        allocateResultSet.addAll(allocateResult); //Ӧ������allocateResult�еĶ���
                    }

                    boolean changed = this.updateProcessQueueTableInRebalance(topic, allocateResultSet);
                    if (changed) {
                        log.info(
                                "rebalanced allocate source. allocateMessageQueueStrategyName={}, group={}, topic={}, mqAllSize={}, cidAllSize={}, mqAll={}, cidAll={}",
                                strategy.getName(), consumerGroup, topic, mqSet.size(), cidAll.size(), mqSet, cidAll);
                        log.info(
                                "rebalanced result changed. allocateMessageQueueStrategyName={}, group={}, topic={}, ConsumerId={}, rebalanceSize={}, rebalanceMqSet={}",
                                strategy.getName(), consumerGroup, topic, this.mQClientFactory.getClientId(),
                                allocateResultSet.size(), mqAll.size(), cidAll.size(), allocateResultSet);

                        //RebalancePushImpl   RebalancePullImpl
                        this.messageQueueChanged(topic, mqSet, allocateResultSet);
                    }
                }
                break;
            }
            default:
                break;
        }
    }


    public abstract void messageQueueChanged(final String topic, final Set<MessageQueue> mqAll,
                                             final Set<MessageQueue> mqDivided);


    public void removeProcessQueue(final MessageQueue mq) {
        ProcessQueue prev = this.processQueueTable.remove(mq);
        if (prev != null) {
            boolean droped = prev.isDropped();
            prev.setDropped(true);
            this.removeUnnecessaryMessageQueue(mq, prev);
            log.info("Fix Offset, {}, remove unnecessary mq, {} Droped: {}", consumerGroup, mq, droped);
        }
    }


    /**
     *�� updateProcessQueueTableInRebalance �������Ķ�����Ϣת��Ϊrequest����뵽 PullMessageService.pullRequestQueue��Ȼ��
    //��PullMessageService.run ��ͨ���� pullRequestQueue �е�PullRequest����ȡ��Ϣ
     * @param topic
     * @param mqSet
     * @return
     */
    private boolean updateProcessQueueTableInRebalance(final String topic, final Set<MessageQueue> mqSet) {
        boolean changed = false;

        Iterator<Entry<MessageQueue, ProcessQueue>> it = this.processQueueTable.entrySet().iterator();
        while (it.hasNext()) {
            //ÿһ����Ϣ���У���Ӧһ��������С�
            Entry<MessageQueue, ProcessQueue> next = it.next();
            MessageQueue mq = next.getKey();
            ProcessQueue pq = next.getValue();

            if (mq.getTopic().equals(topic)) { //˵����mq�Ѿ�����broker����
                if (!mqSet.contains(mq)) { //ԭ�ȵĶ��в���rebalance�����Ժ��µĶ����б�����Ҫ��ɾ��������
                    pq.setDropped(true); //��Ǹ�queue�����ˣ�����������ĳ��broker��ɾ��topic�ͻ��и�����
                    if (this.removeUnnecessaryMessageQueue(mq, pq)) { //�����������Ѷ����еĶ��У� ��Ҫͬ��λ�㵽broker�� �������broker�ϵ�queue ������
                        it.remove();
                        changed = true;
                        log.info("doRebalance, {}, remove unnecessary mq, {}", consumerGroup, mq);
                    }
                }
                else if (pq.isPullExpired()) { //������ȻҪ����,���Ǵ�������Ѿ����ڡ�
                    switch (this.consumeType()) {
                        case CONSUME_ACTIVELY: //�������ѣ������ ��
                            break;
                        case CONSUME_PASSIVELY: //�������� ����ɾ��������С�
                            pq.setDropped(true); //���Ϊdroped�����ProcessQueue��Ч
                            if (this.removeUnnecessaryMessageQueue(mq, pq)) {
                                it.remove();
                                changed = true;
                                log.error(
                                        "[BUG]doRebalance, {}, remove unnecessary mq, {}, because pull is pause, so try to fixed it",
                                        consumerGroup, mq);
                            }
                            break;
                        default:
                            break;
                    }
                }
            }
        }

        //�·��ֵ�queue��Ϣ���뵽 pullRequestList���ں���dispatchPullRequest�ӿڰ���Щ������PullRequest��ӵ� PullMessageService.pullRequestQueue
        List<PullRequest> pullRequestList = new ArrayList<PullRequest>();
        for (MessageQueue mq : mqSet) { //����֮ǰtopic��broker-A�ϣ�����������broker-B��������˸�topic����broker-B��Ҳ�ͻ���queue����
            if (!this.processQueueTable.containsKey(mq)) { //����Ҫ�����ӵĶ��С�
                //Ϊ�����ӵĶ��д�����Ӧ�� pullRequest �����뵽pullRequestList����Ȼ���������dispatchPullRequest�ַ����Ϳ���ͨ����Щ����topic�Ķ���Ͷ��������Ϣ��
                PullRequest pullRequest = new PullRequest();
                pullRequest.setConsumerGroup(consumerGroup);
                pullRequest.setMessageQueue(mq);
                pullRequest.setProcessQueue(new ProcessQueue());

                long nextOffset = this.computePullFromWhere(mq);
                if (nextOffset >= 0) {
                    pullRequest.setNextOffset(nextOffset);
                    pullRequestList.add(pullRequest);
                    changed = true;
                    this.processQueueTable.put(mq, pullRequest.getProcessQueue());
                    log.info("doRebalance, {}, add a new mq, {}", consumerGroup, mq);
                } else {
                    log.warn("doRebalance, {}, add new mq failed, {}", consumerGroup, mq);
                }
            }
        }

        //�� updateProcessQueueTableInRebalance �������Ķ�����Ϣת��Ϊrequest����뵽 PullMessageService.pullRequestQueue��Ȼ��
        //��PullMessageService.run ��ͨ���� pullRequestQueue �е�PullRequest����ȡ��Ϣ

        //�������ӵ����Ѷ��з��ɳ�ȥ��  updateProcessQueueTableInRebalance-> RebalancePushImpl.dispatchPullRequest
        this.dispatchPullRequest(pullRequestList);

        return changed;
    }


    public abstract boolean removeUnnecessaryMessageQueue(final MessageQueue mq, final ProcessQueue pq);


    public abstract void dispatchPullRequest(final List<PullRequest> pullRequestList);


    public abstract long computePullFromWhere(final MessageQueue mq);


    private void truncateMessageQueueNotMyTopic() {
        Map<String, SubscriptionData> subTable = this.getSubscriptionInner();

        for (MessageQueue mq : this.processQueueTable.keySet()) {
            if (!subTable.containsKey(mq.getTopic())) {
                ProcessQueue pq = this.processQueueTable.remove(mq);
                if (pq != null) {
                    pq.setDropped(true);
                    log.info("doRebalance, {}, truncateMessageQueueNotMyTopic remove unnecessary mq, {}",
                            consumerGroup, mq);
                }
            }
        }
    }


    public ConcurrentHashMap<String, SubscriptionData> getSubscriptionInner() {
        return subscriptionInner;
    }


    public ConcurrentHashMap<MessageQueue, ProcessQueue> getProcessQueueTable() {
        return processQueueTable;
    }


    public ConcurrentHashMap<String, Set<MessageQueue>> getTopicSubscribeInfoTable() {
        return topicSubscribeInfoTable;
    }


    public String getConsumerGroup() {
        return consumerGroup;
    }


    public void setConsumerGroup(String consumerGroup) {
        this.consumerGroup = consumerGroup;
    }


    public MessageModel getMessageModel() {
        return messageModel;
    }


    public void setMessageModel(MessageModel messageModel) {
        this.messageModel = messageModel;
    }


    public AllocateMessageQueueStrategy getAllocateMessageQueueStrategy() {
        return allocateMessageQueueStrategy;
    }


    public void setAllocateMessageQueueStrategy(AllocateMessageQueueStrategy allocateMessageQueueStrategy) {
        this.allocateMessageQueueStrategy = allocateMessageQueueStrategy;
    }


    public MQClientInstance getmQClientFactory() {
        return mQClientFactory;
    }


    public void setmQClientFactory(MQClientInstance mQClientFactory) {
        this.mQClientFactory = mQClientFactory;
    }


    public void destroy() {
        Iterator<Entry<MessageQueue, ProcessQueue>> it = this.processQueueTable.entrySet().iterator();
        while (it.hasNext()) {
            Entry<MessageQueue, ProcessQueue> next = it.next();
            next.getValue().setDropped(true);
        }

        this.processQueueTable.clear();
    }
}
