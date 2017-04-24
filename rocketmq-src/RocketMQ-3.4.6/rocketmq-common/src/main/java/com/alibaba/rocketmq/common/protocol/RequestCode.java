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

package com.alibaba.rocketmq.common.protocol;
 /*
 所有的通信协议列表见 RequestCode，通过 createRequestCommand 来构建通信内容，然后通过NettyEncoder进行序列化，然后发送
 服务端收到后通过 NettyDecoder.decode反序列号，然后 NettyServerHandler 读取反序列号后的报文，
 数据收发 请求 应答对应的分支在 RemotingCommandType（NettyRemotingAbstract.processMessageReceived）
 //NettyRemotingClient 和 NettyRemotingServer 中的initChannel执行各种命令回调
 */

 //通信协议注册见 registerProcessor  NettyRemotingClient 和 NettyRemotingServer 中的initChannel执行各种命令回调
public class RequestCode { //报文头部header data部分的code:  代表这些标识
     //客户端producer在 MQClientAPIImpl.sendMessage 该类型中发送消息
    public static final int SEND_MESSAGE = 10; //SEND_MESSAGE_V2 和 SEND_MESSAGE都是发送消息
    public static final int PULL_MESSAGE = 11; //客户端拉取消息 PullMessageProcessor.processRequest 中执行
    public static final int QUERY_MESSAGE = 12;
    public static final int QUERY_BROKER_OFFSET = 13;
    public static final int QUERY_CONSUMER_OFFSET = 14;
    public static final int UPDATE_CONSUMER_OFFSET = 15;
    public static final int UPDATE_AND_CREATE_TOPIC = 17;
    public static final int GET_ALL_TOPIC_CONFIG = 21;
    /**
     * 增加获取topic配置列表
     */
    public static final int GET_TOPIC_CONFIG_LIST = 22;
    public static final int GET_TOPIC_NAME_LIST = 23;
    public static final int UPDATE_BROKER_CONFIG = 25;
    public static final int GET_BROKER_CONFIG = 26;
    public static final int TRIGGER_DELETE_FILES = 27;
    //sh mqadmin brokerStatus 获取broker运行信息
    public static final int GET_BROKER_RUNTIME_INFO = 28;
    public static final int SEARCH_OFFSET_BY_TIMESTAMP = 29;
    public static final int GET_MAX_OFFSET = 30;
    public static final int GET_MIN_OFFSET = 31;
    public static final int GET_EARLIEST_MSG_STORETIME = 32;
    public static final int VIEW_MESSAGE_BY_ID = 33;
    public static final int HEART_BEAT = 34;
    public static final int UNREGISTER_CLIENT = 35;
    //消费失败，把消息重新打回broker中
    public static final int CONSUMER_SEND_MSG_BACK = 36;
    public static final int END_TRANSACTION = 37;
    public static final int GET_CONSUMER_LIST_BY_GROUP = 38;
    public static final int CHECK_TRANSACTION_STATE = 39;
    public static final int NOTIFY_CONSUMER_IDS_CHANGED = 40;
    public static final int LOCK_BATCH_MQ = 41;
    public static final int UNLOCK_BATCH_MQ = 42;
    public static final int GET_ALL_CONSUMER_OFFSET = 43;
    public static final int GET_ALL_DELAY_OFFSET = 45;
    public static final int PUT_KV_CONFIG = 100;
    public static final int GET_KV_CONFIG = 101;
    public static final int DELETE_KV_CONFIG = 102;
    public static final int REGISTER_BROKER = 103;
    public static final int UNREGISTER_BROKER = 104;
    public static final int GET_ROUTEINTO_BY_TOPIC = 105;
    public static final int GET_BROKER_CLUSTER_INFO = 106;
    public static final int UPDATE_AND_CREATE_SUBSCRIPTIONGROUP = 200;
    public static final int GET_ALL_SUBSCRIPTIONGROUP_CONFIG = 201;
    public static final int GET_TOPIC_STATS_INFO = 202;
    public static final int GET_CONSUMER_CONNECTION_LIST = 203;
    public static final int GET_PRODUCER_CONNECTION_LIST = 204;
    public static final int WIPE_WRITE_PERM_OF_BROKER = 205;

    public static final int GET_ALL_TOPIC_LIST_FROM_NAMESERVER = 206;
    public static final int DELETE_SUBSCRIPTIONGROUP = 207;
    public static final int GET_CONSUME_STATS = 208;
    public static final int SUSPEND_CONSUMER = 209;
    public static final int RESUME_CONSUMER = 210;
    public static final int RESET_CONSUMER_OFFSET_IN_CONSUMER = 211;
    public static final int RESET_CONSUMER_OFFSET_IN_BROKER = 212;
    public static final int ADJUST_CONSUMER_THREAD_POOL = 213;
    public static final int WHO_CONSUME_THE_MESSAGE = 214;

    public static final int DELETE_TOPIC_IN_BROKER = 215;
    public static final int DELETE_TOPIC_IN_NAMESRV = 216;
    public static final int GET_KVLIST_BY_NAMESPACE = 219;

    public static final int RESET_CONSUMER_CLIENT_OFFSET = 220;
    public static final int GET_CONSUMER_STATUS_FROM_CLIENT = 221;
    public static final int INVOKE_BROKER_TO_RESET_OFFSET = 222;
    public static final int INVOKE_BROKER_TO_GET_CONSUMER_STATUS = 223;
     //sh mqadmin statsAll -n xxx  一个topic一个topic的获取信息
    public static final int QUERY_TOPIC_CONSUME_BY_WHO = 300;

    public static final int GET_TOPICS_BY_CLUSTER = 224;

    public static final int REGISTER_FILTER_SERVER = 301;
    public static final int REGISTER_MESSAGE_FILTER_CLASS = 302;
    public static final int QUERY_CONSUME_TIME_SPAN = 303;
    public static final int GET_SYSTEM_TOPIC_LIST_FROM_NS = 304;
    public static final int GET_SYSTEM_TOPIC_LIST_FROM_BROKER = 305;

    public static final int CLEAN_EXPIRED_CONSUMEQUEUE = 306;

    public static final int GET_CONSUMER_RUNNING_INFO = 307;

    public static final int QUERY_CORRECTION_OFFSET = 308;

    public static final int CONSUME_MESSAGE_DIRECTLY = 309;

    public static final int SEND_MESSAGE_V2 = 310;

    public static final int GET_UNIT_TOPIC_LIST = 311;
    public static final int GET_HAS_UNIT_SUB_TOPIC_LIST = 312;
    public static final int GET_HAS_UNIT_SUB_UNUNIT_TOPIC_LIST = 313;
    public static final int CLONE_GROUP_OFFSET = 314;
     //sh mqadmin statsAll -获取 #InTPS     #OutTPS   #InMsg24Hour  #OutMsg24Hour信息
    public static final int VIEW_BROKER_STATS_DATA = 315;

    public static final int CLEAN_UNUSED_TOPIC = 316;

    public static final int GET_BROKER_CONSUME_STATS = 317;

}
