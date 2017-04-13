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
package com.alibaba.rocketmq.broker.client;

import com.alibaba.rocketmq.common.constant.LoggerName;
import com.alibaba.rocketmq.common.consumer.ConsumeFromWhere;
import com.alibaba.rocketmq.common.protocol.heartbeat.ConsumeType;
import com.alibaba.rocketmq.common.protocol.heartbeat.MessageModel;
import com.alibaba.rocketmq.common.protocol.heartbeat.SubscriptionData;
import com.alibaba.rocketmq.remoting.common.RemotingHelper;
import com.alibaba.rocketmq.remoting.common.RemotingUtil;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


/**
 *  ���������߷�����Ϣ��
 * @author shijia.wxr
 */
public class ConsumerManager {
    private static final Logger log = LoggerFactory.getLogger(LoggerName.BrokerLoggerName);
    private final ConcurrentHashMap<String/* Group */, ConsumerGroupInfo> consumerTable =
            new ConcurrentHashMap<String, ConsumerGroupInfo>(1024);

    private final ConsumerIdsChangeListener consumerIdsChangeListener;
    private static final long ChannelExpiredTimeout = 1000 * 120;


    public ConsumerManager(final ConsumerIdsChangeListener consumerIdsChangeListener) {
        this.consumerIdsChangeListener = consumerIdsChangeListener;
    }


    public ClientChannelInfo findChannel(final String group, final String clientId) {
        ConsumerGroupInfo consumerGroupInfo = this.consumerTable.get(group);
        if (consumerGroupInfo != null) {
            return consumerGroupInfo.findChannel(clientId);
        }

        return null;
    }


    public ConsumerGroupInfo getConsumerGroupInfo(final String group) {
        return this.consumerTable.get(group);
    }


    public SubscriptionData findSubscriptionData(final String group, final String topic) {
        ConsumerGroupInfo consumerGroupInfo = this.getConsumerGroupInfo(group);
        if (consumerGroupInfo != null) {
            return consumerGroupInfo.findSubscriptionData(topic);
        }

        return null;
    }


    public int findSubscriptionDataCount(final String group) {
        ConsumerGroupInfo consumerGroupInfo = this.getConsumerGroupInfo(group);
        if (consumerGroupInfo != null) {
            return consumerGroupInfo.getSubscriptionTable().size();
        }

        return 0;
    }


    /**
     * �������˳�ʱҪ��rebalance .
     * @param remoteAddr
     * @param channel
     */
    public void doChannelCloseEvent(final String remoteAddr, final Channel channel) {
        Iterator<Entry<String, ConsumerGroupInfo>> it = this.consumerTable.entrySet().iterator();
        while (it.hasNext()) {
            Entry<String, ConsumerGroupInfo> next = it.next();
            ConsumerGroupInfo info = next.getValue();
            boolean removed = info.doChannelCloseEvent(remoteAddr, channel);
            if (removed) {
                if (info.getChannelInfoTable().isEmpty()) {
                    ConsumerGroupInfo remove = this.consumerTable.remove(next.getKey());
                    if (remove != null) {
                        log.info("ungister consumer ok, no any connection, and remove consumer group, {}",
                            next.getKey());
                    }
                }

                this.consumerIdsChangeListener.consumerIdsChanged(next.getKey(), info.getAllChannel());
            }
        }
    }

    /**
     * �� �����ߣ���clientChannelInfo ��ʶ�� ע�ᵽָ���������߷���(group)��, ���Ұ�������Ԫ���ݡ�
     * ���������ͣ� ����ģʽ�� λ�����ͣ����ĵ�topic�б� ����
     * @param group
     * @param clientChannelInfo
     * @param consumeType
     * @param messageModel
     * @param consumeFromWhere
     * @param subList
     * @return
     */
    public boolean registerConsumer(final String group, final ClientChannelInfo clientChannelInfo,
            ConsumeType consumeType, MessageModel messageModel, ConsumeFromWhere consumeFromWhere,
            final Set<SubscriptionData> subList) {
        ConsumerGroupInfo consumerGroupInfo = this.consumerTable.get(group);
        if (null == consumerGroupInfo) {
            ConsumerGroupInfo tmp = new ConsumerGroupInfo(group, consumeType, messageModel, consumeFromWhere);
            ConsumerGroupInfo prev = this.consumerTable.putIfAbsent(group, tmp);
            consumerGroupInfo = prev != null ? prev : tmp;
        }

        //��������ͨ��(��Ӧһ��clientid ,Ĭ��clientip@processid.)ע�ᵽ�����߷����¡�
        boolean r1 =
                consumerGroupInfo.updateChannel(clientChannelInfo, consumeType, messageModel,
                    consumeFromWhere);
        boolean r2 = consumerGroupInfo.updateSubscription(subList);

        if (r1 || r2) { //�����߱����뵽�����߷���(r1) || �����߷������ӻ���ɾ���˶�topic�Ķ��� �� ��������е����������߷���
            //������id�ı��֪ͨ�� ����client��rebalance (��������һ�����ʣ� ����������˳������߷��飬ҲӦ������������id �ı��֪ͨ�ġ�
            // ��ϸ��һ�£���Ϊ������������ע������� ���Ե�Ȼ�������߼��������߷��飡�������������˳���Ӧ��ͨ�����������������������

            //��νrebalance ,�Լ�Ⱥ����Ϊ������ʵ����һ�������߷����е�������Ҫ��̯����topic�µ�������Ϣ��
            //�������rebalance�㷨�ǰ���topic������ �������ǣ�
            //ÿһ��������client �յ�reblanceָ���Ժ󣬿��Լ���������Щtopic ,Ȼ��topic�ֱ���rebalance .

            //��ÿһ��topic�ڵ�rebalance�������£�
            //�ȴ�client���ػ����·�ɱ��л�ȡtopic������brokername�µ�broker master .
            //Ȼ����broker masterҪ������clientid�б� Ȼ���topic�µ����Ѷ��а���̯�㷨���¾��ȷ��������clientid ,
            //�˳�ĳһ�����е������߷��֣���λ����µ�broker)  �� ��������е������߽��֣��ѷ��ֵ��Ǹ�������λ���ù��������ķ�ʽ���ѣ���
            // )
            this.consumerIdsChangeListener.consumerIdsChanged(group, consumerGroupInfo.getAllChannel());
        }

        return r1 || r2;
    }


    public void unregisterConsumer(final String group, final ClientChannelInfo clientChannelInfo) {
        ConsumerGroupInfo consumerGroupInfo = this.consumerTable.get(group);
        if (null != consumerGroupInfo) {
            consumerGroupInfo.unregisterChannel(clientChannelInfo);
            if (consumerGroupInfo.getChannelInfoTable().isEmpty()) {
                ConsumerGroupInfo remove = this.consumerTable.remove(group);
                if (remove != null) {
                    log.info("ungister consumer ok, no any connection, and remove consumer group, {}", group);
                }
            }
            this.consumerIdsChangeListener.consumerIdsChanged(group, consumerGroupInfo.getAllChannel());
        }
    }


    public void scanNotActiveChannel() {
        Iterator<Entry<String, ConsumerGroupInfo>> it = this.consumerTable.entrySet().iterator();
        while (it.hasNext()) {
            Entry<String, ConsumerGroupInfo> next = it.next();
            String group = next.getKey();
            ConsumerGroupInfo consumerGroupInfo = next.getValue();
            ConcurrentHashMap<Channel, ClientChannelInfo> channelInfoTable =
                    consumerGroupInfo.getChannelInfoTable();

            Iterator<Entry<Channel, ClientChannelInfo>> itChannel = channelInfoTable.entrySet().iterator();
            while (itChannel.hasNext()) {
                Entry<Channel, ClientChannelInfo> nextChannel = itChannel.next();
                ClientChannelInfo clientChannelInfo = nextChannel.getValue();
                long diff = System.currentTimeMillis() - clientChannelInfo.getLastUpdateTimestamp();
                if (diff > ChannelExpiredTimeout) {
                    log.warn(
                        "SCAN: remove expired channel from ConsumerManager consumerTable. channel={}, consumerGroup={}",
                        RemotingHelper.parseChannelRemoteAddr(clientChannelInfo.getChannel()), group);
                    RemotingUtil.closeChannel(clientChannelInfo.getChannel());
                    itChannel.remove();
                }
            }

            if (channelInfoTable.isEmpty()) {
                log.warn(
                    "SCAN: remove expired channel from ConsumerManager consumerTable, all clear, consumerGroup={}",
                    group);
                it.remove();
            }
        }
    }


    public HashSet<String> queryTopicConsumeByWho(final String topic) {
        HashSet<String> groups = new HashSet<String>();
        Iterator<Entry<String, ConsumerGroupInfo>> it = this.consumerTable.entrySet().iterator();
        while (it.hasNext()) {
            Entry<String, ConsumerGroupInfo> entry = it.next();
            ConcurrentHashMap<String, SubscriptionData> subscriptionTable =
                    entry.getValue().getSubscriptionTable();
            if (subscriptionTable.containsKey(topic)) {
                groups.add(entry.getKey());
            }
        }

        return groups;
    }
}
