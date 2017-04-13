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
package com.alibaba.rocketmq.store;

import com.alibaba.rocketmq.common.constant.LoggerName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;


/**
 *ConsumeQueue ��commitlog��������Ҳ����MapfileΪ�洢��
 * ÿһ��Ԫ�ذ�����
 * 8�ֽ�commitlog offset + 4�ֽ�commitlog��С + 4�ֽ�msgtag hashcode .
 *
 * �߼��ϣ�һ�������Ǻ�ĳһ��Topic��صģ����Լ��Ķ���id .
 *
 *Consume Queue�洢��Ϣ��Commit Log�е�λ����Ϣ
 *
 [root@s10-2-s-5 topic-prod-xxxxxservice-xxxxx]# pwd
 /data/store/consumequeue/topic-prod-xxxxxservice-xxxxx
 [root@s10-2-s-5 topic-prod-xxxxxservice-xxxxx]# ls
 0  1  2  3  4  5  6  7  8  9
 [root@s10-2-s-5 topic-prod-xxxxxservice-xxxxx]# ls 0/
 00000000000048000000  00000000000054000000  00000000000060000000  00000000000066000000  00000000000072000000  00000000000078000000  00000000000084000000  00000000000090000000  00000000000096000000  00000000000102000000
 [root@s10-2-s-5 topic-prod-xxxxxservice-xxxxx]#
 *
 * ConsumeQueue�в�����Ҫ�洢��Ϣ�����ݣ����洢������Ϣ��CommitLog�е�offset��Ҳ����˵��ConsumeQuue��ʵ��CommitLog��һ�������ļ���
 *ConsumeQueue�Ƕ����Ľṹ��ÿ1����¼�̶���20���ֽڡ�����Ȼ��Consumer������Ϣ��ʱ��Ҫ��2�Σ��ȶ�ConsumeQueue�õ�offset���ٶ�CommitLog�õ���Ϣ���ݡ�
 * �ο�:http://blog.csdn.net/chunlongyu/article/details/54576649
 * http://blog.csdn.net/chunlongyu/article/details/54576649
 *
 *
 *DefaultMessageStore.loadConsumeQueue broker�����󣬴�/data/store/consumequeue·����ȡ��Ӧtopic�и������е�commit log������Ϣ
 * ����${user.home} \store\consumequeue\${topic}\${queueId}�������ļ�������topic�� queueId�� �ļ�������ConsueQueue����
 * @author shijia.wxr
 */
public class ConsumeQueue {
    //8�ֽ�commitlog offset + 4�ֽ�commit log item size + 8�ֽ�message tag hashcode.
    public static final int CQStoreUnitSize = 20; //��ROCKETMQ�����ֲ��7-1
    private static final Logger log = LoggerFactory.getLogger(LoggerName.StoreLoggerName);
    private static final Logger logError = LoggerFactory.getLogger(LoggerName.StoreErrorLoggerName);
    private final DefaultMessageStore defaultMessageStore;
    //ÿ�����Ѷ�������Ҳ��ͨ��Mapfile�������ġ� ConsumeQueue.ConsumeQueue �и�ֵ
    //����CommitLog������ConsumeQueue������һ����Ӧ��MappedFileQueue��Ҳ���Ƕ�Ӧ���ڴ�ӳ���ļ�������
    private final MapedFileQueue mapedFileQueue;
    private final String topic;
    private final int queueId;
    private final ByteBuffer byteBufferIndex;
    private final String storePath;
    private final int mapedFileSize; //ͨ�� MessageStoreConfig.getMapedFileSizeConsumeQueue ���㣬Ĭ��Ϊ30W�� CQStoreUnitSize��С
    /**
     * ���commitlog��λ�㡣
     */
    private long maxPhysicOffset = -1;
    /**
     * ���Ѷ��е�mapfile����С����λ��, ,���磺 ������ֵ��40 ������е��߼�λ��ʵ������40/20=2
     */
    private volatile long minLogicOffset = 0;


    public ConsumeQueue(//
            final String topic,//
            final int queueId,//
            final String storePath,//
            final int mapedFileSize,//
            final DefaultMessageStore defaultMessageStore) {
        this.storePath = storePath;
        this.mapedFileSize = mapedFileSize;
        this.defaultMessageStore = defaultMessageStore;

        this.topic = topic;
        this.queueId = queueId;

        ///data/store/consumequeue/topic-prod-xxxxxservice-xxxxx/0
        String queueDir = this.storePath//
                + File.separator + topic//
                + File.separator + queueId;//

        this.mapedFileQueue = new MapedFileQueue(queueDir, mapedFileSize, null);

        this.byteBufferIndex = ByteBuffer.allocate(CQStoreUnitSize);
    }

    //DefaultMessageStore.loadConsumeQueue �м���
    public boolean load() {
        boolean result = this.mapedFileQueue.load();
        log.info("load consume queue " + this.topic + "-" + this.queueId + " " + (result ? "OK" : "Failed"));
        return result;
    }

    public void recover() {
        final List<MapedFile> mapedFiles = this.mapedFileQueue.getMapedFiles();
        if (!mapedFiles.isEmpty()) {
            int index = mapedFiles.size() - 3; //��������ļ����ɿ���
            if (index < 0)
                index = 0;

            int mapedFileSizeLogics = this.mapedFileSize;
            MapedFile mapedFile = mapedFiles.get(index);
            ByteBuffer byteBuffer = mapedFile.sliceByteBuffer();
            long processOffset = mapedFile.getFileFromOffset();
            long mapedFileOffset = 0;
            while (true) {
                for (int i = 0; i < mapedFileSizeLogics; i += CQStoreUnitSize) {
                    long offset = byteBuffer.getLong();
                    int size = byteBuffer.getInt();
                    long tagsCode = byteBuffer.getLong();

                    if (offset >= 0 && size > 0) {
                        mapedFileOffset = i + CQStoreUnitSize;
                        this.maxPhysicOffset = offset;
                    }
                    else {
                        log.info("recover current consume queue file over,  " + mapedFile.getFileName() + " "
                                + offset + " " + size + " " + tagsCode);
                        break;
                    }
                }

                if (mapedFileOffset == mapedFileSizeLogics) {
                    index++;
                    if (index >= mapedFiles.size()) {
                        log.info("recover last consume queue file over, last maped file "
                                + mapedFile.getFileName());
                        break;
                    }
                    else {
                        mapedFile = mapedFiles.get(index);
                        byteBuffer = mapedFile.sliceByteBuffer();
                        processOffset = mapedFile.getFileFromOffset();
                        mapedFileOffset = 0;
                        log.info("recover next consume queue file, " + mapedFile.getFileName());
                    }
                }
                else {
                    log.info("recover current consume queue queue over " + mapedFile.getFileName() + " "
                            + (processOffset + mapedFileOffset));
                    break;
                }
            }

            processOffset += mapedFileOffset;
            this.mapedFileQueue.truncateDirtyFiles(processOffset);
        }
    }

    public long getOffsetInQueueByTime(final long timestamp) {
        MapedFile mapedFile = this.mapedFileQueue.getMapedFileByTime(timestamp);
        if (mapedFile != null) {
            long offset = 0;
            int low =
                    minLogicOffset > mapedFile.getFileFromOffset() ? (int) (minLogicOffset - mapedFile
                        .getFileFromOffset()) : 0;

            int high = 0;
            int midOffset = -1, targetOffset = -1, leftOffset = -1, rightOffset = -1;
            long leftIndexValue = -1L, rightIndexValue = -1L;

            SelectMapedBufferResult sbr = mapedFile.selectMapedBuffer(0);
            if (null != sbr) {
                ByteBuffer byteBuffer = sbr.getByteBuffer();
                high = byteBuffer.limit() - CQStoreUnitSize;
                try {
                    while (high >= low) { //���ֲ���
                        midOffset = (low + high) / (2 * CQStoreUnitSize) * CQStoreUnitSize;
                        byteBuffer.position(midOffset);
                        long phyOffset = byteBuffer.getLong();
                        int size = byteBuffer.getInt();

                        long storeTime =
                                this.defaultMessageStore.getCommitLog().pickupStoretimestamp(phyOffset, size);
                        if (storeTime < 0) {
                            return 0;
                        }
                        else if (storeTime == timestamp) { //��Ϣ�Ĵ洢ʱ������úͲ�ѯʱ����Ǻϡ�
                            targetOffset = midOffset;
                            break;
                        }
                        else if (storeTime > timestamp) {
                            high = midOffset - CQStoreUnitSize;
                            rightOffset = midOffset;
                            rightIndexValue = storeTime;
                        }
                        else {
                            low = midOffset + CQStoreUnitSize;
                            leftOffset = midOffset;
                            leftIndexValue = storeTime;
                        }
                    }

                    if (targetOffset != -1) {
                        offset = targetOffset;
                    }
                    else {
                        if (leftIndexValue == -1) {
                            offset = rightOffset;
                        }
                        else if (rightIndexValue == -1) {
                            offset = leftOffset;
                        }
                        else {
                            offset =
                                    Math.abs(timestamp - leftIndexValue) > Math.abs(timestamp
                                            - rightIndexValue) ? rightOffset : leftOffset;
                        }
                    }

                    return (mapedFile.getFileFromOffset() + offset) / CQStoreUnitSize;
                }
                finally {
                    sbr.release();
                }
            }
        }

        return 0;
    }

    public void truncateDirtyLogicFiles(long phyOffet) {
        int logicFileSize = this.mapedFileSize;

        this.maxPhysicOffset = phyOffet - 1;

        while (true) {
            MapedFile mapedFile = this.mapedFileQueue.getLastMapedFile2();
            if (mapedFile != null) {
                ByteBuffer byteBuffer = mapedFile.sliceByteBuffer();
                mapedFile.setWrotePostion(0);
                mapedFile.setCommittedPosition(0);

                for (int i = 0; i < logicFileSize; i += CQStoreUnitSize) {
                    long offset = byteBuffer.getLong();
                    int size = byteBuffer.getInt();
                    byteBuffer.getLong();

                    if (0 == i) {
                        if (offset >= phyOffet) {
                            this.mapedFileQueue.deleteLastMapedFile();
                            break;
                        }
                        else {
                            int pos = i + CQStoreUnitSize;
                            mapedFile.setWrotePostion(pos);
                            mapedFile.setCommittedPosition(pos);
                            this.maxPhysicOffset = offset;
                        }
                    }
                    else {
                        if (offset >= 0 && size > 0) {
                            if (offset >= phyOffet) {
                                return;
                            }

                            int pos = i + CQStoreUnitSize;
                            mapedFile.setWrotePostion(pos);
                            mapedFile.setCommittedPosition(pos);
                            this.maxPhysicOffset = offset;

                            if (pos == logicFileSize) {
                                return;
                            }
                        }
                        else {
                            return;
                        }
                    }
                }
            }
            else {
                break;
            }
        }
    }

    public long getLastOffset() {
        long lastOffset = -1;
        int logicFileSize = this.mapedFileSize;

        MapedFile mapedFile = this.mapedFileQueue.getLastMapedFile2();
        if (mapedFile != null) {
            int position = mapedFile.getWrotePostion() - CQStoreUnitSize;
            if (position < 0)
                position = 0;

            ByteBuffer byteBuffer = mapedFile.sliceByteBuffer();
            byteBuffer.position(position);
            for (int i = 0; i < logicFileSize; i += CQStoreUnitSize) {
                long offset = byteBuffer.getLong();
                int size = byteBuffer.getInt();
                byteBuffer.getLong();

                if (offset >= 0 && size > 0) {
                    lastOffset = offset + size;
                }
                else {
                    break;
                }
            }
        }

        return lastOffset;
    }


    public boolean commit(final int flushLeastPages) {
        return this.mapedFileQueue.commit(flushLeastPages);
    }


    public int deleteExpiredFile(long offset) {
        int cnt = this.mapedFileQueue.deleteExpiredFileByOffset(offset, CQStoreUnitSize);
        this.correctMinOffset(offset);
        return cnt;
    }

    public void correctMinOffset(long phyMinOffset) {
        MapedFile mapedFile = this.mapedFileQueue.getFirstMapedFileOnLock();
        if (mapedFile != null) {
            SelectMapedBufferResult result = mapedFile.selectMapedBuffer(0);
            if (result != null) {
                try {
                    for (int i = 0; i < result.getSize(); i += ConsumeQueue.CQStoreUnitSize) {
                        long offsetPy = result.getByteBuffer().getLong();//commitlog offset
                        result.getByteBuffer().getInt(); //commitlog item size .
                        result.getByteBuffer().getLong(); //commitlog msgtag hashcode;

                        if (offsetPy >= phyMinOffset) { //�ҵ����Ѷ����е�һ��item ,���item���õ�commitlog ��־���λ���phyMinOffset
                            //��ʶ��λ��Ҫ��
                            this.minLogicOffset = result.getMapedFile().getFileFromOffset() + i;
                            log.info("compute logics min offset: " + this.getMinOffsetInQuque() + ", topic: "
                                    + this.topic + ", queueId: " + this.queueId);
                            break;
                        }
                    }
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
                finally {
                    result.release();
                }
            }
        }
    }


    public long getMinOffsetInQuque() {
        return this.minLogicOffset / CQStoreUnitSize;
    }


    /**
     * �����Ѷ���(CQ)��д�������
     * @param offset commitlog offset
     * @param size commitlog����Ϣitem�ĳ��ȡ�
     * @param tagsCode ����tag��hashcode.
     * @param storeTimestamp ��Ϣ�洢ʱ��
     * @param logicOffset  CQ���߼�λ�㡣
     */
    public void putMessagePostionInfoWrapper(long offset, int size, long tagsCode, long storeTimestamp,
            long logicOffset) {
        final int MaxRetries = 30;
        boolean canWrite = this.defaultMessageStore.getRunningFlags().isWriteable();
        for (int i = 0; i < MaxRetries && canWrite; i++) {
            boolean result = this.putMessagePostionInfo(offset, size, tagsCode, logicOffset);
            if (result) {
                this.defaultMessageStore.getStoreCheckpoint().setLogicsMsgTimestamp(storeTimestamp);
                return;
            }
            else {
                log.warn("[BUG]put commit log postion info to " + topic + ":" + queueId + " " + offset
                        + " failed, retry " + i + " times");

                try {
                    Thread.sleep(1000);
                }
                catch (InterruptedException e) {
                    log.warn("", e);
                }
            }
        }

        log.error("[BUG]consume queue can not write, {} {}", this.topic, this.queueId);
        this.defaultMessageStore.getRunningFlags().makeLogicsQueueError();
    }


    private boolean putMessagePostionInfo(final long offset, final int size, final long tagsCode,
            final long cqOffset) {
        if (offset <= this.maxPhysicOffset) {
            return true;
        }

        this.byteBufferIndex.flip();
        this.byteBufferIndex.limit(CQStoreUnitSize);
        this.byteBufferIndex.putLong(offset);
        this.byteBufferIndex.putInt(size);
        this.byteBufferIndex.putLong(tagsCode);

        //CQ item ��mapfile�е�����λ�� (�߼�λ�� ���� CQ��item�ĳ��ȡ�)
        final long expectLogicOffset = cqOffset * CQStoreUnitSize;

        //
        MapedFile mapedFile = this.mapedFileQueue.getLastMapedFile(expectLogicOffset);
        if (mapedFile != null) {
            if (mapedFile.isFirstCreateInQueue() && cqOffset != 0 && mapedFile.getWrotePostion() == 0) {
                this.minLogicOffset = expectLogicOffset;
                this.fillPreBlank(mapedFile, expectLogicOffset);
                log.info("fill pre blank space " + mapedFile.getFileName() + " " + expectLogicOffset + " "
                        + mapedFile.getWrotePostion());
            }

            if (cqOffset != 0) {
                //currentLogicOffset��CQ item ��mapfile�е�����λ�㡣
                long currentLogicOffset = mapedFile.getWrotePostion() + mapedFile.getFileFromOffset();
                if (expectLogicOffset != currentLogicOffset) {
                    logError
                        .warn(
                            "[BUG]logic queue order maybe wrong, expectLogicOffset: {} currentLogicOffset: {} Topic: {} QID: {} Diff: {}",//
                            expectLogicOffset, //
                            currentLogicOffset,//
                            this.topic,//
                            this.queueId,//
                            expectLogicOffset - currentLogicOffset//
                        );
                }
            }

            this.maxPhysicOffset = offset;
            return mapedFile.appendMessage(this.byteBufferIndex.array());
        }

        return false;
    }


    private void fillPreBlank(final MapedFile mapedFile, final long untilWhere) {
        ByteBuffer byteBuffer = ByteBuffer.allocate(CQStoreUnitSize);
        byteBuffer.putLong(0L);
        byteBuffer.putInt(Integer.MAX_VALUE);
        byteBuffer.putLong(0L);

        int until = (int) (untilWhere % this.mapedFileQueue.getMapedFileSize());
        for (int i = 0; i < until; i += CQStoreUnitSize) {
            mapedFile.appendMessage(byteBuffer.array());
        }
    }

    /**
     * @param startIndex ���Ѷ��е��߼�λ�㡣
     * @return
     */
    public SelectMapedBufferResult getIndexBuffer(final long startIndex) {
        int mapedFileSize = this.mapedFileSize;
        long offset = startIndex * CQStoreUnitSize;
        if (offset >= this.getMinLogicOffset()) {
            MapedFile mapedFile = this.mapedFileQueue.findMapedFileByOffset(offset);
            if (mapedFile != null) {
                SelectMapedBufferResult result = mapedFile.selectMapedBuffer((int) (offset % mapedFileSize));
                return result;
            }
        }
        return null;
    }

    public long rollNextFile(final long index) {
        int mapedFileSize = this.mapedFileSize;
        int totalUnitsInFile = mapedFileSize / CQStoreUnitSize;
        return (index + totalUnitsInFile - index % totalUnitsInFile);
    }

    public String getTopic() {
        return topic;
    }

    public int getQueueId() {
        return queueId;
    }

    public long getMaxPhysicOffset() {
        return maxPhysicOffset;
    }

    public void setMaxPhysicOffset(long maxPhysicOffset) {
        this.maxPhysicOffset = maxPhysicOffset;
    }

    public void destroy() {
        this.maxPhysicOffset = -1;
        this.minLogicOffset = 0;
        this.mapedFileQueue.destroy();
    }

    public long getMinLogicOffset() {
        return minLogicOffset;
    }

    public void setMinLogicOffset(long minLogicOffset) {
        this.minLogicOffset = minLogicOffset;
    }

    public long getMessageTotalInQueue() {
        return this.getMaxOffsetInQuque() - this.getMinOffsetInQuque();
    }

    public long getMaxOffsetInQuque() {
        return this.mapedFileQueue.getMaxOffset() / CQStoreUnitSize;
    }

    public void checkSelf() {
        mapedFileQueue.checkSelf();
    }
}
