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

package com.alibaba.rocketmq.common.protocol.header;

import com.alibaba.rocketmq.remoting.CommandCustomHeader;
import com.alibaba.rocketmq.remoting.annotation.CFNotNull;
import com.alibaba.rocketmq.remoting.annotation.CFNullable;
import com.alibaba.rocketmq.remoting.exception.RemotingCommandException;


/**
 * @author shijia.wxr  һ���header data�н����������Ϣ: "extFields":{"topic":"yyztest2","queueId":"3","consumerGroup":"yyzGroup2","commitOffset":"28"}
 */ //CONSUMER_SEND_MSG_BACK��Ϣ��extFields����Я����س�Ա����
public class ConsumerSendMsgBackRequestHeader implements CommandCustomHeader {
    @CFNotNull
    private Long offset; //offsetһ����ͨ�ű����е� extFields Я�����Զ�
    @CFNotNull
    private String group;
    @CFNotNull //С��0��������Ŷ��У���consumerSendMsgBack
    private Integer delayLevel; //�ӳ���Ϣ�ȼ���0����Ϣ���ӳ�   1���ӳ�1s  2:�ӳ�5s 3:�ӳ�10s ...... 50:�ӳ�30��
    private String originMsgId;
    private String originTopic;
    @CFNullable
    private boolean unitMode = false;


    @Override
    public void checkFields() throws RemotingCommandException {

    }


    public Long getOffset() {
        return offset;
    }


    public void setOffset(Long offset) {
        this.offset = offset;
    }


    public String getGroup() {
        return group;
    }


    public void setGroup(String group) {
        this.group = group;
    }


    public Integer getDelayLevel() {
        return delayLevel;
    }


    public void setDelayLevel(Integer delayLevel) {
        this.delayLevel = delayLevel;
    }


    public String getOriginMsgId() {
        return originMsgId;
    }


    public void setOriginMsgId(String originMsgId) {
        this.originMsgId = originMsgId;
    }


    public String getOriginTopic() {
        return originTopic;
    }


    public void setOriginTopic(String originTopic) {
        this.originTopic = originTopic;
    }


    public boolean isUnitMode() {
        return unitMode;
    }


    public void setUnitMode(boolean unitMode) {
        this.unitMode = unitMode;
    }


    @Override
    public String toString() {
        return "ConsumerSendMsgBackRequestHeader [group=" + group + ", originTopic=" + originTopic
                + ", originMsgId=" + originMsgId + ", delayLevel=" + delayLevel + ", unitMode=" + unitMode
                + "]";
    }
}
