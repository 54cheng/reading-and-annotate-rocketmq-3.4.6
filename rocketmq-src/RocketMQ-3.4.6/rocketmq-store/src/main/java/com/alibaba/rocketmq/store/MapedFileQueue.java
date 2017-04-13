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

import com.alibaba.rocketmq.common.UtilAll;
import com.alibaba.rocketmq.common.constant.LoggerName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;


/**
 *MapedFileQueue�������˺ܶ�MapedFile���Լ�ÿ��MapedFile����ʵ��С��
 MapedFile�������˾�����ļ���Ϣ�������ļ�·�����ļ������ļ���ʼƫ�ƣ�дλ�ƣ���λ�Ƶȵ���Ϣ��ͬ��ʹ���������ڴ�ӳ�������IOЧ�ʣ�
 * @author shijia.wxr
 * ����CommitLog������ ConsumeQueue������һ����Ӧ��MappedFileQueue��Ҳ���Ƕ�Ӧ���ڴ�ӳ���ļ�������
 * ��дʱ������offset��λ�������У���Ӧ��MappedFile�����ж�д��ͨ��MappedFile���ͺܺõĽ���˴��ļ���������������⡣
 */
public class MapedFileQueue {
    private static final Logger log = LoggerFactory.getLogger(LoggerName.StoreLoggerName);
    private static final Logger logError = LoggerFactory.getLogger(LoggerName.StoreErrorLoggerName);
    private static final int DeleteFilesBatchMax = 10;
    private final String storePath;
    //commitlog Ĭ��Ϊ1024 * 1024 * 1024   consumequeueĬ��Ϊ30w*20
    private final int mapedFileSize;
    //consumequeueĿ¼�µĸ����洢commitlog���ļ���Ϣ�������list�У���MapedFileQueue
    //�ļ���������Ϣ�ڴ��ļ����г�ʼƫ�������ź���������һ����������Ϣ��
    private final List<MapedFile> mapedFiles = new ArrayList<MapedFile>();
    private final ReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final AllocateMapedFileService allocateMapedFileService;
    private long committedWhere = 0;
    private volatile long storeTimestamp = 0;

    /***
     *
     * @param storePath �ڴ�ӳ���ļ��Ĵ洢·����
     * @param mapedFileSize n�ڴ�ӳ���ļ��Ĵ�С ��
     * @param allocateMapedFileService �����ڴ�ӳ���ļ��ķ���
     */
    public MapedFileQueue(final String storePath, int mapedFileSize,
            AllocateMapedFileService allocateMapedFileService) {
        this.storePath = storePath;
        this.mapedFileSize = mapedFileSize;
        this.allocateMapedFileService = allocateMapedFileService;
    }


    public void checkSelf() {
        this.readWriteLock.readLock().lock();
        try {
            if (!this.mapedFiles.isEmpty()) {
                MapedFile first = this.mapedFiles.get(0);
                MapedFile last = this.mapedFiles.get(this.mapedFiles.size() - 1);

                int sizeCompute =
                        (int) ((last.getFileFromOffset() - first.getFileFromOffset()) / this.mapedFileSize) + 1;
                int sizeReal = this.mapedFiles.size();
                if (sizeCompute != sizeReal) {
                    logError
                        .error(
                            "[BUG]The mapedfile queue's data is damaged, {} mapedFileSize={} sizeCompute={} sizeReal={}\n{}", //
                            this.storePath,//
                            this.mapedFileSize,//
                            sizeCompute,//
                            sizeReal,//
                            this.mapedFiles.toString()//
                        );
                }
            }
        }
        finally {
            this.readWriteLock.readLock().unlock();
        }
    }


    public MapedFile getMapedFileByTime(final long timestamp) {
        Object[] mfs = this.copyMapedFiles(0);

        if (null == mfs)
            return null;

        for (int i = 0; i < mfs.length; i++) {
            MapedFile mapedFile = (MapedFile) mfs[i];
            if (mapedFile.getLastModifiedTimestamp() >= timestamp) {
                return mapedFile;
            }
        }

        return (MapedFile) mfs[mfs.length - 1];
    }


    private Object[] copyMapedFiles(final int reservedMapedFiles) {
        Object[] mfs = null;

        try {
            this.readWriteLock.readLock().lock();
            if (this.mapedFiles.size() <= reservedMapedFiles) {
                return null;
            }

            mfs = this.mapedFiles.toArray();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        finally {
            this.readWriteLock.readLock().unlock();
        }
        return mfs;
    }

    public void truncateDirtyFiles(long offset) {
        List<MapedFile> willRemoveFiles = new ArrayList<MapedFile>();

        for (MapedFile file : this.mapedFiles) {
            long fileTailOffset = file.getFileFromOffset() + this.mapedFileSize;
            if (fileTailOffset > offset) {
                if (offset >= file.getFileFromOffset()) {
                    file.setWrotePostion((int) (offset % this.mapedFileSize));
                    file.setCommittedPosition((int) (offset % this.mapedFileSize));
                }
                else {
                    file.destroy(1000);
                    willRemoveFiles.add(file);
                }
            }
        }

        this.deleteExpiredFile(willRemoveFiles);
    }

    private void deleteExpiredFile(List<MapedFile> files) {
        if (!files.isEmpty()) {
            try {
                this.readWriteLock.writeLock().lock();
                for (MapedFile file : files) {
                    if (!this.mapedFiles.remove(file)) {
                        log.error("deleteExpiredFile remove failed.");
                        break;
                    }
                }
            }
            catch (Exception e) {
                log.error("deleteExpiredFile has exception.", e);
            }
            finally {
                this.readWriteLock.writeLock().unlock();
            }
        }
    }


    /**
     [root@s10-2-x-5 0]# ls
     00000000000048000000  00000000000054000000  00000000000060000000  00000000000066000000  00000000000072000000  00000000000078000000  00000000000084000000  00000000000090000000  00000000000096000000  00000000000102000000
     [root@s10-2-x-5 0]# du -sh *
     5.8M    00000000000048000000
     5.8M    00000000000054000000
     5.8M    00000000000060000000
     5.8M    00000000000066000000
     5.8M    00000000000072000000
     5.8M    00000000000078000000
     5.8M    00000000000084000000
     5.8M    00000000000090000000
     5.8M    00000000000096000000
     3.6M    00000000000102000000
     *  �Ѵ����ļ����ص��ڴ档
     * @return  CommitLog.load��ִ��
     */
    public boolean load() {
        File dir = new File(this.storePath); //commitlog·��/root/store/commitlog �������������Ѽ�¼����¼��Ϣ�Ƿ����ѵ���Ϣ
        File[] files = dir.listFiles();
        if (files != null) {
            // ascending order
            Arrays.sort(files); //��consume�ļ���(Ҳ����offsetƫ��)����
            for (File file : files) {
                //??????? ����� consumequeue�� 00000000000102000000Ҳ��û��3.6M����Ҳ��������
                if (file.length() != this.mapedFileSize) { //���ļ�������Ԥ���mapfile��С ��ֱ�Ӻ��Ե�������ļ���
                    log.warn(file + "\t" + file.length()
                            + " length not matched message store config value, ignore it");
                    return true;
                }

                try {
                    MapedFile mapedFile = new MapedFile(file.getPath(), mapedFileSize);
                    //�ļ���С����mapfileд���flush��λ�á�
                    mapedFile.setWrotePostion(this.mapedFileSize);
                    mapedFile.setCommittedPosition(this.mapedFileSize);
                    this.mapedFiles.add(mapedFile);
                    log.info("load " + file.getPath() + " OK");
                }
                catch (IOException e) {
                    log.error("load file " + file + " error", e);
                    return false;
                }
            }
        }

        return true;
    }

    public long howMuchFallBehind() {
        if (this.mapedFiles.isEmpty())
            return 0;

        long committed = this.committedWhere;
        if (committed != 0) {
            MapedFile mapedFile = this.getLastMapedFile(0, false);
            if (mapedFile != null) {
                return (mapedFile.getFileFromOffset() + mapedFile.getWrotePostion()) - committed;
            }
        }

        return 0;
    }


    /**
     * ��ʼλ���0 ��ʼ����һ��Mapfile .
     * @return
     */
    public MapedFile getLastMapedFile() {
        return this.getLastMapedFile(0);
    }

    /**
     * �ö�����ȡ���һ��Mapfile ..
     * û��mapfile �򷵻�null .
     * @return
     */
    public MapedFile getLastMapedFileWithLock() {
        MapedFile mapedFileLast = null;
        this.readWriteLock.readLock().lock();
        if (!this.mapedFiles.isEmpty()) { // �Ѿ���mapfile.  ��ȡ���һ����
            mapedFileLast = this.mapedFiles.get(this.mapedFiles.size() -  1);
        }
        this.readWriteLock.readLock().unlock();

        return mapedFileLast;
    }


    public MapedFile getLastMapedFile(final long startOffset) {
        return getLastMapedFile(startOffset, true);
    }

    /**
     * �Ƿ��Դ����µ�MapFile�ķ�ʽ��ȡһ���µ��ļ���
     * @param startOffset ��ʼ����ʼλ�㣬
     * @param needCreate �Ƿ���Ҫ�����µ�mapfile .
     * ����Ϣ����brokerʱ����Ҫ��ȡ���µ�MapedFileд�����ݣ�����MapedFileQueue��getLastMapedFile��ȡ���˺������������һ��
     * Ҳû�д���һ����������һ��д����Ҳ����һ���µġ�
     * @return
     */
    public MapedFile getLastMapedFile(final long startOffset, boolean needCreate) {
        long createOffset = -1;
        MapedFile mapedFileLast = null;
        {
            this.readWriteLock.readLock().lock();
            if (this.mapedFiles.isEmpty()) { // mapfileΪ�յ���������startOffset��Ϊcreate offset .
                createOffset = startOffset - (startOffset % this.mapedFileSize);
            }
            else {  //�õ������һ��mapfile .
                mapedFileLast = this.mapedFiles.get(this.mapedFiles.size() - 1);
            }
            this.readWriteLock.readLock().unlock();
        }

        if (mapedFileLast != null && mapedFileLast.isFull()) { //�õ������һ��mapfile �����Ѿ�д��,  ��ôCreateoffset
            // �������һ��mapfile���ļ�����offset + �ļ���С�� ��
            createOffset = mapedFileLast.getFileFromOffset() + this.mapedFileSize;
        }

        if (createOffset != -1 && needCreate) {
            //������һ��mapfile�����¸�mapfile��·����
            String nextFilePath = this.storePath + File.separator + UtilAll.offset2FileName(createOffset);
            String nextNextFilePath =
                    this.storePath + File.separator
                            + UtilAll.offset2FileName(createOffset + this.mapedFileSize);
            MapedFile mapedFile = null;

            if (this.allocateMapedFileService != null) { //���첽����Mapfile�ķ���
                mapedFile =
                        this.allocateMapedFileService.putRequestAndReturnMapedFile(nextFilePath,
                            nextNextFilePath, this.mapedFileSize);
            }
            else {//  û���첽����Mapfile�ķ��� �ʹ���һ����
                try {
                    mapedFile = new MapedFile(nextFilePath, this.mapedFileSize);
                }
                catch (IOException e) {
                    log.error("create mapedfile exception", e);
                }
            }

            if (mapedFile != null) { //��д�������뵽mapfile���С�
                this.readWriteLock.writeLock().lock();
                if (this.mapedFiles.isEmpty()) {
                    mapedFile.setFirstCreateInQueue(true);
                }
                this.mapedFiles.add(mapedFile);
                this.readWriteLock.writeLock().unlock();
            }

            return mapedFile;
        }

        return mapedFileLast;
    }

    public long getMinOffset() {
        try {
            this.readWriteLock.readLock().lock();
            if (!this.mapedFiles.isEmpty()) {
                return this.mapedFiles.get(0).getFileFromOffset();
            }
        }
        catch (Exception e) {
            log.error("getMinOffset has exception.", e);
        }
        finally {
            this.readWriteLock.readLock().unlock();
        }

        return -1;
    }


    public long getMaxOffset() {
        try {
            this.readWriteLock.readLock().lock();
            if (!this.mapedFiles.isEmpty()) {
                int lastIndex = this.mapedFiles.size() - 1;
                MapedFile mapedFile = this.mapedFiles.get(lastIndex);
                return mapedFile.getFileFromOffset() + mapedFile.getWrotePostion();
            }
        }
        catch (Exception e) {
            log.error("getMinOffset has exception.", e);
        }
        finally {
            this.readWriteLock.readLock().unlock();
        }

        return 0;
    }

    public void deleteLastMapedFile() {
        if (!this.mapedFiles.isEmpty()) {
            int lastIndex = this.mapedFiles.size() - 1;
            MapedFile mapedFile = this.mapedFiles.get(lastIndex);
            mapedFile.destroy(1000);
            this.mapedFiles.remove(mapedFile);
            log.info("on recover, destroy a logic maped file " + mapedFile.getFileName());
        }
    }


    public int deleteExpiredFileByTime(//
            final long expiredTime, //
            final int deleteFilesInterval, //
            final long intervalForcibly,//
            final boolean cleanImmediately//
    ) {
        Object[] mfs = this.copyMapedFiles(0);

        if (null == mfs)
            return 0;

        int mfsLength = mfs.length - 1;
        int deleteCount = 0;
        List<MapedFile> files = new ArrayList<MapedFile>();
        if (null != mfs) {
            for (int i = 0; i < mfsLength; i++) {
                MapedFile mapedFile = (MapedFile) mfs[i];
                long liveMaxTimestamp = mapedFile.getLastModifiedTimestamp() + expiredTime;
                if (System.currentTimeMillis() >= liveMaxTimestamp//
                        || cleanImmediately) { //�ļ���������ʱ����ߴ��̿ռ䳬���趨��ֵ��Ĭ��75%�� ����Ҫ��ʼ�����ļ�����
                    if (mapedFile.destroy(intervalForcibly)) { //�����ļ�ʱ�� ����ļ����ڱ��߳����ã� ��ͨ��intervalForcibly
                        //����������һ������ɾ���ļ���Ҫ����೤ʱ�䣬Ĭ��120s��
                        files.add(mapedFile);
                        deleteCount++;

                        if (files.size() >= DeleteFilesBatchMax) { //һ������ɾ�����ļ���������10
                            break;
                        }

                        if (deleteFilesInterval > 0 && (i + 1) < mfsLength) { //����mapfileɾ����������100ms .
                            try {
                                Thread.sleep(deleteFilesInterval);
                            }
                            catch (InterruptedException e) {
                            }
                        }
                    }
                    else {
                        break;
                    }
                }
            }
        }

        deleteExpiredFile(files);

        return deleteCount;
    }


    public int deleteExpiredFileByOffset(long offset, int unitSize) {
        Object[] mfs = this.copyMapedFiles(0);

        List<MapedFile> files = new ArrayList<MapedFile>();
        int deleteCount = 0;
        if (null != mfs) {
            int mfsLength = mfs.length - 1;

            for (int i = 0; i < mfsLength; i++) {
                boolean destroy = true;
                MapedFile mapedFile = (MapedFile) mfs[i];
                //�ҵ����Ѷ��е����һ��item��
                SelectMapedBufferResult result = mapedFile.selectMapedBuffer(this.mapedFileSize - unitSize);
                if (result != null) {
                    //���Ѷ������һ��itemָ���commitlog������λ��� offset������commitlog����λ�㻹С��˵��������Ѷ����Ѿ�û���ˡ�����ɾ����
                    long maxOffsetInLogicQueue = result.getByteBuffer().getLong();
                    result.release();
                    destroy = (maxOffsetInLogicQueue < offset);
                    if (destroy) {
                        log.info("physic min offset " + offset + ", logics in current mapedfile max offset "
                                + maxOffsetInLogicQueue + ", delete it");
                    }
                }
                else {
                    log.warn("this being not excuted forever.");
                    break;
                }

                if (destroy && mapedFile.destroy(1000 * 60)) {
                    files.add(mapedFile);
                    deleteCount++;
                }
                else {
                    break;
                }
            }
        }

        deleteExpiredFile(files);

        return deleteCount;
    }

    /**
     * һ������ˢ������ҳ.
     * @param flushLeastPages
     * @return
     */
    public boolean commit(final int flushLeastPages) {
        boolean result = true;
        //��commitlog�Ѿ�д���λ���ҵ�mapfile .������û���ҵ�ʱ�� �õ���һ��Mapfile��
        MapedFile mapedFile = this.findMapedFileByOffset(this.committedWhere, true);
        if (mapedFile != null) {
            long tmpTimeStamp = mapedFile.getStoreTimestamp();
            int offset = mapedFile.commit(flushLeastPages);
            //commit��ˢ�̴����Ժ�where�ͱ�����Ѿ�ˢ�̵��Ķ���λ�á�
            long where = mapedFile.getFileFromOffset() + offset;
            result = (where == this.committedWhere);
            this.committedWhere = where;
            if (0 == flushLeastPages) {
                this.storeTimestamp = tmpTimeStamp;
            }
        }

        return result;
    }


    /**
     * �͵�һ��Mapfile��ʼ����λ��Ĳ�ֵ����mapfile�ļ���С,�͵õ��˵�ǰҪд���mapfile.
     * @param offset
     * @param returnFirstOnNotFound
     * @return
     */  //����offset���Ǹ�MapedFile��
    public MapedFile findMapedFileByOffset(final long offset, final boolean returnFirstOnNotFound) {
        try {
            this.readWriteLock.readLock().lock();
            MapedFile mapedFile = this.getFirstMapedFile();

            if (mapedFile != null) {
                int index =
                        (int) ((offset / this.mapedFileSize) - (mapedFile.getFileFromOffset() / this.mapedFileSize));
                if (index < 0 || index >= this.mapedFiles.size()) {
                    logError
                        .warn(
                            "findMapedFileByOffset offset not matched, request Offset: {}, index: {}, mapedFileSize: {}, mapedFiles count: {}, StackTrace: {}",//
                            offset,//
                            index,//
                            this.mapedFileSize,//
                            this.mapedFiles.size(),//
                            UtilAll.currentStackTrace());
                }

                try {
                    return this.mapedFiles.get(index);
                }
                catch (Exception e) {
                    if (returnFirstOnNotFound) {
                        return mapedFile;
                    }
                }
            }
        }
        catch (Exception e) {
            log.error("findMapedFileByOffset Exception", e);
        }
        finally {
            this.readWriteLock.readLock().unlock();
        }

        return null;
    }


    private MapedFile getFirstMapedFile() {
        if (this.mapedFiles.isEmpty()) {
            return null;
        }

        return this.mapedFiles.get(0);
    }


    public MapedFile getLastMapedFile2() {
        if (this.mapedFiles.isEmpty()) {
            return null;
        }
        return this.mapedFiles.get(this.mapedFiles.size() - 1);
    }


    public MapedFile findMapedFileByOffset(final long offset) {
        return findMapedFileByOffset(offset, false);
    }


    public long getMapedMemorySize() {
        long size = 0;

        Object[] mfs = this.copyMapedFiles(0);
        if (mfs != null) {
            for (Object mf : mfs) {
                if (((ReferenceResource) mf).isAvailable()) {
                    size += this.mapedFileSize;
                }
            }
        }

        return size;
    }


    public boolean retryDeleteFirstFile(final long intervalForcibly) {
        MapedFile mapedFile = this.getFirstMapedFileOnLock();
        if (mapedFile != null) {
            if (!mapedFile.isAvailable()) {
                log.warn("the mapedfile was destroyed once, but still alive, " + mapedFile.getFileName());
                boolean result = mapedFile.destroy(intervalForcibly);
                if (result) {
                    log.warn("the mapedfile redelete OK, " + mapedFile.getFileName());
                    List<MapedFile> tmps = new ArrayList<MapedFile>();
                    tmps.add(mapedFile);
                    this.deleteExpiredFile(tmps);
                }
                else {
                    log.warn("the mapedfile redelete Failed, " + mapedFile.getFileName());
                }

                return result;
            }
        }

        return false;
    }


    public MapedFile getFirstMapedFileOnLock() {
        try {
            this.readWriteLock.readLock().lock();
            return this.getFirstMapedFile();
        }
        finally {
            this.readWriteLock.readLock().unlock();
        }
    }


    public void shutdown(final long intervalForcibly) {
        this.readWriteLock.readLock().lock();
        for (MapedFile mf : this.mapedFiles) {
            mf.shutdown(intervalForcibly);
        }
        this.readWriteLock.readLock().unlock();
    }


    public void destroy() {
        this.readWriteLock.writeLock().lock();
        for (MapedFile mf : this.mapedFiles) {
            mf.destroy(1000 * 3);
        }
        this.mapedFiles.clear();
        this.committedWhere = 0;

        // delete parent directory
        File file = new File(storePath);
        if (file.isDirectory()) {
            file.delete();
        }
        this.readWriteLock.writeLock().unlock();
    }


    public long getCommittedWhere() {
        return committedWhere;
    }


    public void setCommittedWhere(long committedWhere) {
        this.committedWhere = committedWhere;
    }


    public long getStoreTimestamp() {
        return storeTimestamp;
    }


    public List<MapedFile> getMapedFiles() {
        return mapedFiles;
    }


    public int getMapedFileSize() {
        return mapedFileSize;
    }
}
