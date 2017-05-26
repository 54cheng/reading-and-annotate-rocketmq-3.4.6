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
 * //selectOneMessageQueue  messageQueueList 是投递消息的时候对应的topic队列，每次投递的时候乱序选择队列投递，见ROCKET开发手册7.8节
 //rebalance  updateProcessQueueTableInRebalance 相关的是针对消费，例如有多个消费者消费同一个topic，该topic有10个队列，则消费者1消费1-5队列，消费者2消费6-10对了，见ROCKETMQ开发手册7-5  7-9
 *
 * //把 updateProcessQueueTableInRebalance 中新增的队列信息转换为request后加入到 PullMessageService.pullRequestQueue，然后
 //在PullMessageService.run 中通过该 pullRequestQueue 中的PullRequest来拉取消息
 *
 * @author shijia.wxr    抽象类的接口实现在 RebalancePushImpl   RebalancePullImpl  真正使用在 DefaultMQPushConsumerImpl
 */
public abstract class RebalanceImpl {
    protected static final Logger log = ClientLogger.getLog();
    ////MQClientInstance.start(this.rebalanceService.start();)中进行队列rebalance处理的时候会更新该hashmap
    //每一个消息队列，对应一个处理队列。 RebalanceImpl.processQueueTable(MessageQueue----ProcessQueue) 通过 updateProcessQueueTableInRebalance 更新
    protected final ConcurrentHashMap<MessageQueue, ProcessQueue> processQueueTable =
            new ConcurrentHashMap<MessageQueue, ProcessQueue>(64);
    //topic对应的queue信息全部在该hashmap中 RebalanceImpl.doRebalance 中用該hashmap来决定发送到那个队列
    // DefaultMQPushConsumerImpl.updateTopicSubscribeInfo 中进行入队赋值  这里面存储了topic及其对应的broker上的队列信息
    ////MQClientInstance.start(this.rebalanceService.start();)中进行队列rebalance处理的时候会更新该hashmap
    protected final ConcurrentHashMap<String/* topic */, Set<MessageQueue>> topicSubscribeInfoTable =
            new ConcurrentHashMap<String, Set<MessageQueue>>();
    /* topic和 subscriptionData 的数据存入该map中  copySubscription 和subscribe中赋值  RebalanceImpl.doRebalance 中用該hashmap来决定发送到那个队列 */
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
    //调用源头在 MQClientInstance.start(this.rebalanceService.start();)中执行
    //DefaultMQPushConsumerImpl.doRebalance调用
    //真正做rebalance处理在 RebalanceImpl.doRebalance
    public void doRebalance() {
        Map<String, SubscriptionData> subTable = this.getSubscriptionInner();
        if (subTable != null) {
            for (final Map.Entry<String, SubscriptionData> entry : subTable.entrySet()) {
                //按消费者订阅的topic分解启动rebalance过程，
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

    //把 updateProcessQueueTableInRebalance 中新增的队列信息转换为request后加入到 PullMessageService.pullRequestQueue，然后
    //在PullMessageService.run 中通过该 pullRequestQueue 中的PullRequest来拉取消息
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
                //topic对应的所有的队列集合
                Set<MessageQueue> mqSet = this.topicSubscribeInfoTable.get(topic);
                //獲取該topic下面，消費者分組是consumerGroup的所有消費者ID，
                List<String> cidAll = this.mQClientFactory.findConsumerIdList(topic, consumerGroup);
                if (null == mqSet) {
                    if (!topic.startsWith(MixAll.RETRY_GROUP_TOPIC_PREFIX)) {
                        log.warn("doRebalance, {}, but the topic[{}] not exist.", consumerGroup, topic);
                    }
                }

                if (null == cidAll) {
                    log.warn("doRebalance, {} {}, get consumer id list failed", consumerGroup, topic);
                }

                if (mqSet != null && cidAll != null) { //消费队列和消费者id都不为空。
                    List<MessageQueue> mqAll = new ArrayList<MessageQueue>();
                    mqAll.addAll(mqSet);

                    Collections.sort(mqAll); //该topic下的所有queue队列
                    Collections.sort(cidAll); //该topic下面消费分组都是该consumerGroup的所有客户端ID

                    AllocateMessageQueueStrategy strategy = this.allocateMessageQueueStrategy;

                    //获取本客户端应该消费那些队列
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

                    //根据rebalance算法获取到当前消费者应该挂接的消费队列。
                    Set<MessageQueue> allocateResultSet = new HashSet<MessageQueue>();
                    if (allocateResult != null) {
                        allocateResultSet.addAll(allocateResult); //应该消费allocateResult中的队列
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
     *把 updateProcessQueueTableInRebalance 中新增的队列信息转换为request后加入到 PullMessageService.pullRequestQueue，然后
    //在PullMessageService.run 中通过该 pullRequestQueue 中的PullRequest来拉取消息
     * @param topic
     * @param mqSet
     * @return
     */
    private boolean updateProcessQueueTableInRebalance(final String topic, final Set<MessageQueue> mqSet) {
        boolean changed = false;

        Iterator<Entry<MessageQueue, ProcessQueue>> it = this.processQueueTable.entrySet().iterator();
        while (it.hasNext()) {
            //每一个消息队列，对应一个处理队列。
            Entry<MessageQueue, ProcessQueue> next = it.next();
            MessageQueue mq = next.getKey();
            ProcessQueue pq = next.getValue();

            if (mq.getTopic().equals(topic)) { //说明该mq已经不再broker中了
                if (!mqSet.contains(mq)) { //原先的队列不在rebalance操作以后新的队列列表，则需要做删除操作。
                    pq.setDropped(true); //标记该queue下线了，例如我们在某个broker上删除topic就会有改现象
                    if (this.removeUnnecessaryMessageQueue(mq, pq)) { //不在最新消费队列中的队列， 需要同步位点到broker， 并解除掉broker上的queue 的锁。
                        it.remove();
                        changed = true;
                        log.info("doRebalance, {}, remove unnecessary mq, {}", consumerGroup, mq);
                    }
                }
                else if (pq.isPullExpired()) { //队列仍然要消费,但是处理队列已经过期。
                    switch (this.consumeType()) {
                        case CONSUME_ACTIVELY: //主动消费，则继续 。
                            break;
                        case CONSUME_PASSIVELY: //被动消费 ，则删除处理队列。
                            pq.setDropped(true); //标记为droped，则该ProcessQueue无效
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

        //新发现的queue信息加入到 pullRequestList，在后面dispatchPullRequest接口把这些新增的PullRequest添加到 PullMessageService.pullRequestQueue
        List<PullRequest> pullRequestList = new ArrayList<PullRequest>();
        for (MessageQueue mq : mqSet) { //例如之前topic在broker-A上，我现在又在broker-B上面添加了该topic，则broker-B上也就会有queue队列
            if (!this.processQueueTable.containsKey(mq)) { //处理要新增加的队列。
                //为新增加的队列创建对应的 pullRequest 并加入到pullRequestList链表，然后在下面的dispatchPullRequest分发，就可以通过这些新增topic的队列投递消费消息了
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

        //把 updateProcessQueueTableInRebalance 中新增的队列信息转换为request后加入到 PullMessageService.pullRequestQueue，然后
        //在PullMessageService.run 中通过该 pullRequestQueue 中的PullRequest来拉取消息

        //把新增加的消费队列分派出去。  updateProcessQueueTableInRebalance-> RebalancePushImpl.dispatchPullRequest
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
