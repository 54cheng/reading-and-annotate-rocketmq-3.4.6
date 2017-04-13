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
package com.alibaba.rocketmq.store.ha;

import com.alibaba.rocketmq.common.ServiceThread;
import com.alibaba.rocketmq.common.constant.LoggerName;
import com.alibaba.rocketmq.remoting.common.RemotingUtil;
import com.alibaba.rocketmq.store.SelectMapedBufferResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;


/**
 * @author shijia.wxr
 */
public class HAConnection {
    private static final Logger log = LoggerFactory.getLogger(LoggerName.StoreLoggerName);
    private final HAService haService;
    private final SocketChannel socketChannel;
    private final String clientAddr;
    //master��slaveд��
    private WriteSocketService writeSocketService;
    //master��slave����
    private ReadSocketService readSocketService;
    private volatile long slaveRequestOffset = -1;
    private volatile long slaveAckOffset = -1;


    public HAConnection(final HAService haService, final SocketChannel socketChannel) throws IOException {
        this.haService = haService;
        this.socketChannel = socketChannel;
        this.clientAddr = this.socketChannel.socket().getRemoteSocketAddress().toString();
        this.socketChannel.configureBlocking(false);
        this.socketChannel.socket().setSoLinger(false, -1);
        this.socketChannel.socket().setTcpNoDelay(true);
        this.socketChannel.socket().setReceiveBufferSize(1024 * 64);
        this.socketChannel.socket().setSendBufferSize(1024 * 64);
        //��slave��дsocketͨ��
        this.writeSocketService = new WriteSocketService(this.socketChannel);
        //��slave����socketͨ����
        this.readSocketService = new ReadSocketService(this.socketChannel);
        this.haService.getConnectionCount().incrementAndGet();
    }


    public void start() {
        this.readSocketService.start();
        this.writeSocketService.start();
    }


    public void shutdown() {
        this.writeSocketService.shutdown(true);
        this.readSocketService.shutdown(true);
        this.close();
    }


    public void close() {
        if (this.socketChannel != null) {
            try {
                this.socketChannel.close();
            }
            catch (IOException e) {
                HAConnection.log.error("", e);
            }
        }
    }


    public SocketChannel getSocketChannel() {
        return socketChannel;
    }

    //master��slave����
    class ReadSocketService extends ServiceThread {
        private static final int ReadMaxBufferSize = 1024 * 1024;
        private final Selector selector;
        private final SocketChannel socketChannel;
        private final ByteBuffer byteBufferRead = ByteBuffer.allocate(ReadMaxBufferSize);
        private int processPostion = 0;
        private volatile long lastReadTimestamp = System.currentTimeMillis();


        public ReadSocketService(final SocketChannel socketChannel) throws IOException {
            this.selector = RemotingUtil.openSelector();
            this.socketChannel = socketChannel;
            this.socketChannel.register(this.selector, SelectionKey.OP_READ);
            this.thread.setDaemon(true);
        }


        @Override
        public void run() {
            HAConnection.log.info(this.getServiceName() + " service started");

            while (!this.isStoped()) {
                try {
                    this.selector.select(1000);
                    boolean ok = this.processReadEvent();
                    if (!ok) {
                        HAConnection.log.error("processReadEvent error");
                        break;
                    }
                    //��ָ���ĳ�ʱʱ����processReadEvent û���յ����ݡ�
                    long interval = HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now() - this.lastReadTimestamp;
                    if (interval > HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig().getHaHousekeepingInterval()) {
                        log.warn("ha housekeeping, found this connection[" + HAConnection.this.clientAddr + "] expired, " + interval);
                        break;
                    }
                }
                catch (Exception e) {
                    HAConnection.log.error(this.getServiceName() + " service has exception.", e);
                    break;
                }
            }

            this.makeStop();

            writeSocketService.makeStop();

            haService.removeConnection(HAConnection.this);

            HAConnection.this.haService.getConnectionCount().decrementAndGet();

            SelectionKey sk = this.socketChannel.keyFor(this.selector);
            if (sk != null) {
                sk.cancel();
            }

            try {
                this.selector.close();
                this.socketChannel.close();
            }
            catch (IOException e) {
                HAConnection.log.error("", e);
            }

            HAConnection.log.info(this.getServiceName() + " service end");
        }



        private boolean processReadEvent() {
            int readSizeZeroTimes = 0;

            if (!this.byteBufferRead.hasRemaining()) {
                this.byteBufferRead.flip();
                this.processPostion = 0;
            }

            while (this.byteBufferRead.hasRemaining()) {
                try {
                    int readSize = this.socketChannel.read(this.byteBufferRead);
                    if (readSize > 0) {
                        readSizeZeroTimes = 0;
                        this.lastReadTimestamp = HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now();
                        if ((this.byteBufferRead.position() - this.processPostion) >= 8) { //Ӧ�û�����δ������ֽ�������8 ��
                            //����û����netty�Ľ�������������  �����Լ����ģ�������������ݴ��������� slaveÿ�θ�master�ص�ack
                            //offset ����һ��8�ֽڵ�long���ͱ�����

                            //(this.byteBufferRead.position() % 8) �����û�ж���8���ֽڵļ����ֽڡ� pos������1��Long�����һ���ֽڡ�
                            int pos = this.byteBufferRead.position() - (this.byteBufferRead.position() % 8);
                            long readOffset = this.byteBufferRead.getLong(pos - 8); //����һ��8�ֽڵ����һ�ֽڿ�ʼ��ǰ��8�ֽڡ�
                            this.processPostion = pos;

                            HAConnection.this.slaveAckOffset = readOffset; //ack��λ�����readOffset��ʶ��Long������
                            if (HAConnection.this.slaveRequestOffset < 0) { //HAConnection��ʼ��slaveRequestOffset�� -1, һ��
                                //���� HAService��578��slave�㱨������slave ack offset ,�����HaConnection��slaveRequestOffset ��
                                HAConnection.this.slaveRequestOffset = readOffset;
                                log.info("slave[" + HAConnection.this.clientAddr + "] request offset " + readOffset);
                            }
                            //�յ�slave����commit log ��ͬ����ack offset�Ժ�֪ͨ��Щ�ȴ�����ͬ���������ߣ�����ͬ����commitlog
                            //offset�Ѿ���ǰ���ˡ�
                            HAConnection.this.haService.notifyTransferSome(HAConnection.this.slaveAckOffset);
                        }
                    }
                    else if (readSize == 0) {
                        if (++readSizeZeroTimes >= 3) {
                            break;
                        }
                    }
                    else {
                        log.error("read socket[" + HAConnection.this.clientAddr + "] < 0");
                        return false;
                    }
                }
                catch (IOException e) {
                    log.error("processReadEvent exception", e);
                    return false;
                }
            }

            return true;
        }


        @Override
        public String getServiceName() {
            return ReadSocketService.class.getSimpleName();
        }
    }

    //master��slaveд��
    class WriteSocketService extends ServiceThread {
        private final Selector selector;
        private final SocketChannel socketChannel;
        private final int HEADER_SIZE = 8 + 4;
        private final ByteBuffer byteBufferHeader = ByteBuffer.allocate(HEADER_SIZE);
        private long nextTransferFromWhere = -1;
        private SelectMapedBufferResult selectMapedBufferResult;
        private boolean lastWriteOver = true;
        private long lastWriteTimestamp = System.currentTimeMillis();


        public WriteSocketService(final SocketChannel socketChannel) throws IOException {
            this.selector = RemotingUtil.openSelector();
            this.socketChannel = socketChannel;
            this.socketChannel.register(this.selector, SelectionKey.OP_WRITE);
            this.thread.setDaemon(true);
        }


        @Override
        public void run() {
            HAConnection.log.info(this.getServiceName() + " service started");

            while (!this.isStoped()) {
                try {
                    this.selector.select(1000);

                    if (-1 == HAConnection.this.slaveRequestOffset) {
                        Thread.sleep(10);
                        continue;
                    }
                    //189�л�ȡ��slave �ύ��slaveRequestOffset ��
                    if (-1 == this.nextTransferFromWhere) {
                        if (0 == HAConnection.this.slaveRequestOffset) { //slave�����ͬ��λ��Ϊ0 ��������һ��Mapfile��ʼͬ����
                            //����mapfileĬ��һ��G��ֻҪû��д��1G, ��ʵ���Ǵ�0 ��ʼͬ����
                            long masterOffset = HAConnection.this.haService.getDefaultMessageStore().getCommitLog().getMaxOffset();
                            masterOffset =
                                    masterOffset
                                            - (masterOffset % HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig()
                                                .getMapedFileSizeCommitLog());

                            if (masterOffset < 0) {
                                masterOffset = 0;
                            }

                            this.nextTransferFromWhere = masterOffset;
                        }
                        else { //��slave�յ���ָ��λ�㿪ʼ����commitlog .
                            this.nextTransferFromWhere = HAConnection.this.slaveRequestOffset;
                        }

                        log.info("master transfer data from " + this.nextTransferFromWhere + " to slave[" + HAConnection.this.clientAddr
                                + "], and slave request " + HAConnection.this.slaveRequestOffset);
                    }

                    if (this.lastWriteOver) {
                        long interval =
                                HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now() - this.lastWriteTimestamp;

                        if (interval > HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig()
                            .getHaSendHeartbeatInterval()) {
                            this.byteBufferHeader.position(0);
                            this.byteBufferHeader.limit(HEADER_SIZE);
                            this.byteBufferHeader.putLong(this.nextTransferFromWhere); //��commitlog���ĸ�λ�㿪ʼ��
                            this.byteBufferHeader.putInt(0); 
                            this.byteBufferHeader.flip();

                            this.lastWriteOver = this.transferData();
                            if (!this.lastWriteOver)
                                continue;
                        }
                    }
                    else {
                        this.lastWriteOver = this.transferData();
                        if (!this.lastWriteOver)
                            continue;
                    }

                    SelectMapedBufferResult selectResult =
                            HAConnection.this.haService.getDefaultMessageStore().getCommitLogData(this.nextTransferFromWhere);
                    if (selectResult != null) { //��commitlogָ����λ�㿪ʼ��ȡ���ݡ�
                        int size = selectResult.getSize();
                        if (size > HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig().getHaTransferBatchSize()) {
                            //������һ�� ����ͬ��������ֽ�����
                            size = HAConnection.this.haService.getDefaultMessageStore().getMessageStoreConfig().getHaTransferBatchSize();
                        }

                        long thisOffset = this.nextTransferFromWhere;
                        this.nextTransferFromWhere += size;

                        selectResult.getByteBuffer().limit(size);
                        this.selectMapedBufferResult = selectResult;

                        this.byteBufferHeader.position(0);
                        this.byteBufferHeader.limit(HEADER_SIZE);//
                        this.byteBufferHeader.putLong(thisOffset); //���ݴ��ĸ�λ�㿪ʼ//
                        this.byteBufferHeader.putInt(size);//commitlog�ж೤��
                        this.byteBufferHeader.flip();

                        this.lastWriteOver = this.transferData();
                    }
                    else {
                        HAConnection.this.haService.getWaitNotifyObject().allWaitForRunning(100);
                    }
                }
                catch (Exception e) {
                    HAConnection.log.error(this.getServiceName() + " service has exception.", e);
                    break;
                }
            }

            if (this.selectMapedBufferResult != null) {
                this.selectMapedBufferResult.release();
            }

            this.makeStop();

            readSocketService.makeStop();

            haService.removeConnection(HAConnection.this);

            SelectionKey sk = this.socketChannel.keyFor(this.selector);
            if (sk != null) {
                sk.cancel();
            }

            try {
                this.selector.close();
                this.socketChannel.close();
            }
            catch (IOException e) {
                HAConnection.log.error("", e);
            }

            HAConnection.log.info(this.getServiceName() + " service end");
        }

        /**
         *  masterдcommit log sync request ��slave .
         * @return
         * @throws Exception
         */
        private boolean transferData() throws Exception {
            int writeSizeZeroTimes = 0;
            // Write Header
            while (this.byteBufferHeader.hasRemaining()) {
                int writeSize = this.socketChannel.write(this.byteBufferHeader);
                if (writeSize > 0) {
                    writeSizeZeroTimes = 0;
                    this.lastWriteTimestamp = HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now();
                }
                else if (writeSize == 0) {
                    if (++writeSizeZeroTimes >= 3) { //����3��д���ݵ��ֽ�������0.
                        break;
                    }
                }
                else {
                    throw new Exception("ha master write header error < 0");
                }
            }

            if (null == this.selectMapedBufferResult) {
                return !this.byteBufferHeader.hasRemaining();
            }

            writeSizeZeroTimes = 0;

            // Write Body
            if (!this.byteBufferHeader.hasRemaining()) {
                while (this.selectMapedBufferResult.getByteBuffer().hasRemaining()) {
                    int writeSize = this.socketChannel.write(this.selectMapedBufferResult.getByteBuffer());
                    if (writeSize > 0) {
                        writeSizeZeroTimes = 0;
                        this.lastWriteTimestamp = HAConnection.this.haService.getDefaultMessageStore().getSystemClock().now();
                    }
                    else if (writeSize == 0) {
                        if (++writeSizeZeroTimes >= 3) {
                            break;
                        }
                    }
                    else {
                        throw new Exception("ha master write body error < 0");
                    }
                }
            }

            boolean result = !this.byteBufferHeader.hasRemaining() && !this.selectMapedBufferResult.getByteBuffer().hasRemaining();

            if (!this.selectMapedBufferResult.getByteBuffer().hasRemaining()) {
                this.selectMapedBufferResult.release();
                this.selectMapedBufferResult = null;
            }

            return result;
        }


        @Override
        public String getServiceName() {
            return WriteSocketService.class.getSimpleName();
        }


        @Override
        public void shutdown() {
            super.shutdown();
        }
    }
}
