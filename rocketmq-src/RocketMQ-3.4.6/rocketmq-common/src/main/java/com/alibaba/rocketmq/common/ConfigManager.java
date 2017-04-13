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
package com.alibaba.rocketmq.common;

import com.alibaba.rocketmq.common.constant.LoggerName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;


/**
 * @author shijia.wxr
 */
public abstract class ConfigManager {
    private static final Logger plog = LoggerFactory.getLogger(LoggerName.CommonLoggerName);


    public abstract String encode();


    public abstract String encode(final boolean prettyFormat);


    public abstract void decode(final String jsonString);


    public abstract String configFilePath();

    /*
    * topicConfigManager.load();
      consumerOffsetManager.load();
      subscriptionGroupManager.load();
      ScheduleMessageService.load()
    * */ //����consumerOffset.json  delayOffset.json  subscriptionGroup.json  topics.json ����Ӧ�ĵط�
    public boolean load() {
        String fileName = null;
        try {
            /* topicConfigManager.configFilePath(); consumerOffsetManager.configFilePath(); subscriptionGroupManager.configFilePath();
            * ScheduleMessageService.configFilePath()
            * */
            fileName = this.configFilePath();
            String jsonString = MixAll.file2String(fileName); //��ȡ�ļ����ݴ���jsonString
            if (null == jsonString || jsonString.length() == 0) {
                return this.loadBak();
            }
            else {
                /* topicConfigManager.decode(); consumerOffsetManager.decode(); subscriptionGroupManager.decode();
                * ScheduleMessageService.decode()
                * */
                this.decode(jsonString); //��������������ļ��ж�ȡ��������Ϣ��Ȼ�����Щ�����ļ��е��������л�����Ӧ�ĵط��洢
                plog.info("load {} OK", fileName);
                return true;
            }
        }
        catch (Exception e) {
            plog.error("load " + fileName + " Failed, and try to load backup file", e);
            return this.loadBak();
        }
    }


    private boolean loadBak() {
        String fileName = null;
        try {
            fileName = this.configFilePath();
            String jsonString = MixAll.file2String(fileName + ".bak");
            if (jsonString != null && jsonString.length() > 0) {
                this.decode(jsonString);
                plog.info("load " + fileName + " OK");
                return true;
            }
        }
        catch (Exception e) {
            plog.error("load " + fileName + " Failed", e);
            return false;
        }

        return true;
    }


    public synchronized void persist() {
        //���紴��topic��Ӧ��this����TopicConfigManager, TopicConfigManager.encode���� topicConfigTable �������л����string,�� ConfigManager.createTopicInSendMessageBackMethod
        String jsonString = this.encode(true);
        if (jsonString != null) {
            String fileName = this.configFilePath();
            try {
                MixAll.string2File(jsonString, fileName); //�����Ӧ���ļ�
            }
            catch (IOException e) {
                plog.error("persist file Exception, " + fileName, e);
            }
        }
    }
}
