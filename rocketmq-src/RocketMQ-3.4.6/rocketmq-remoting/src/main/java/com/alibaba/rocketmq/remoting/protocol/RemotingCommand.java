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
package com.alibaba.rocketmq.remoting.protocol;

import com.alibaba.fastjson.annotation.JSONField;
import com.alibaba.rocketmq.remoting.CommandCustomHeader;
import com.alibaba.rocketmq.remoting.annotation.CFNotNull;
import com.alibaba.rocketmq.remoting.common.RemotingHelper;
import com.alibaba.rocketmq.remoting.exception.RemotingCommandException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;


/**  Э���ʽ:<length> <header length> <header data> <bodydata>
 *���е�ͨ��Э���б�� RequestCode��ͨ�� createRequestCommand ������ͨ�����ݣ�Ȼ��ͨ�� NettyEncoder.encode �������л���Ȼ����
 *������յ���ͨ�� NettyDecoder.decode�����кţ�Ȼ��NettyServerHandler��ȡ�����кź�ı��ģ�
 * �����շ� ���� Ӧ���Ӧ�ķ�֧�� RemotingCommandType��NettyRemotingAbstract.processMessageReceived��
 * ���յ� RemotingCommand �� NettyDecoder.decode ������
 *
 * @author shijia.wxr  RocketMq��������ͻ���ͨ������RemotingCommand��������ͨ�� NettyDecoder����RemotingCommand����Э��ı��������
 *
 */ //�����encode��decode���ͨ�ű��ĵ����л��ͷ����л�
public class RemotingCommand {
    private static final Logger log = LoggerFactory.getLogger(RemotingHelper.RemotingLogName);
    public static String RemotingVersionKey = "rocketmq.remoting.version";
    public static final String SERIALIZE_TYPE_PROPERTY = "rocketmq.serialize.type";
    public static final String SERIALIZE_TYPE_ENV = "ROCKETMQ_SERIALIZE_TYPE";
    private static volatile int ConfigVersion = -1;
    private static AtomicInteger RequestId = new AtomicInteger(0);

    private static final int RPC_TYPE = 0; // 0, REQUEST_COMMAND
    // 1, RESPONSE_COMMAND

    private static final int RPC_ONEWAY = 1; // 0, RPC
    // 1, Oneway

    //��rocketMQProtocolEncode�н���encode��Ȼ������Է�
    private int code; //RequestCode.CONSUMER_SEND_MSG_BACK ��
    private LanguageCode language = LanguageCode.JAVA;
    private int version = 0;
    //��tcp�����ϵ��̸߳��á�
    private int opaque = RequestId.getAndIncrement();
    //�ñ���������Ӧ��  ���շ�֧��processMessageReceived  �жϼ� isResponseType
    private int flag = 0; //header data��flag:x ָ��  �� getType ת��Ϊ RemotingCommandType.RESPONSE_COMMAND ����  REQUEST_COMMAND
    private String remark;
    //������customHeader�е���Ϣ��䵽����� makeCustomHeaderToNet
    //"extFields":{"topic":"yyztest2","queueId":"3","consumerGroup":"yyzGroup2","commitOffset":"28"}
    private HashMap<String, String> extFields;

    //����CONSUMER_SEND_MSG_BACK��Ϣ��customHeaderΪConsumerSendMsgBackRequestHeader ����consumerSendMessageBack
    //header data
    private transient CommandCustomHeader customHeader; //����CONSUMER_SEND_MSG_BACK��Ϣ��customHeader ���� MQClientAPIImpl.consumerSendMessageBack

    private static final Map<Class<? extends CommandCustomHeader>, Field[]> clazzFieldsCache =
            new HashMap<Class<? extends CommandCustomHeader>, Field[]>();
    private static final Map<Class, String> canonicalNameCache = new HashMap<Class, String>();
    private static final Map<Field, Annotation> notNullAnnotationCache = new HashMap<Field, Annotation>();

    private static SerializeType SerializeTypeConfigInThisServer = SerializeType.JSON;
    static {
        final String protocol = System.getProperty(SERIALIZE_TYPE_PROPERTY, System.getenv(SERIALIZE_TYPE_ENV));
        if (!isBlank(protocol)) {
            try {
                SerializeTypeConfigInThisServer = SerializeType.valueOf(protocol);
            }
            catch (IllegalArgumentException e) {
                throw new RuntimeException("parser specified protocol error. protocol=" + protocol, e);
            }
        }
    }

    private SerializeType serializeTypeCurrentRPC = SerializeTypeConfigInThisServer;

    private Field[] getClazzFields(Class<? extends CommandCustomHeader> classHeader) {
        Field[] field = clazzFieldsCache.get(classHeader);

        if (field == null) {
            field = classHeader.getDeclaredFields();
            synchronized (clazzFieldsCache) {
                clazzFieldsCache.put(classHeader, field);
            }
        }
        return field;
    }


    private String getCanonicalName(Class clazz) {
        String name = canonicalNameCache.get(clazz);

        if (name == null) {
            name = clazz.getCanonicalName();
            synchronized (canonicalNameCache) {
                canonicalNameCache.put(clazz, name);
            }
        }
        return name;
    }


    private Annotation getNotNullAnnotation(Field field) {
        Annotation annotation = notNullAnnotationCache.get(field);

        if (annotation == null) {
            annotation = field.getAnnotation(CFNotNull.class);
            synchronized (notNullAnnotationCache) {
                notNullAnnotationCache.put(field, annotation);
            }
        }
        return annotation;
    }

    private transient byte[] body;

    protected RemotingCommand() {
    }


    public static RemotingCommand createRequestCommand(int code, CommandCustomHeader customHeader) {
        RemotingCommand cmd = new RemotingCommand();
        cmd.setCode(code);
        cmd.customHeader = customHeader;
        setCmdVersion(cmd);
        return cmd;
    }

    //Class<? extends CommandCustomHeader> ���ͣ���ʾclassHeader���������Ǽ̳� CommandCustomHeader �����������
    public static RemotingCommand createResponseCommand(Class<? extends CommandCustomHeader> classHeader) {
        RemotingCommand cmd = createResponseCommand(RemotingSysResponseCode.SYSTEM_ERROR, "not set any response code", classHeader);

        return cmd;
    }


    public static RemotingCommand createResponseCommand(int code, String remark) {
        return createResponseCommand(code, remark, null);
    }

    public static RemotingCommand createResponseCommand(int code, String remark, Class<? extends CommandCustomHeader> classHeader) {
        RemotingCommand cmd = new RemotingCommand();
        cmd.markResponseType();
        cmd.setCode(code);
        cmd.setRemark(remark);
        setCmdVersion(cmd);

        if (classHeader != null) {
            try {
                CommandCustomHeader objectHeader = classHeader.newInstance();
                cmd.customHeader = objectHeader;
            }
            catch (InstantiationException e) {
                return null;
            }
            catch (IllegalAccessException e) {
                return null;
            }
        }

        return cmd;
    }


    private static void setCmdVersion(RemotingCommand cmd) {
        if (ConfigVersion >= 0) {
            cmd.setVersion(ConfigVersion);
        }
        else {
            String v = System.getProperty(RemotingVersionKey);
            if (v != null) {
                int value = Integer.parseInt(v);
                cmd.setVersion(value);
                ConfigVersion = value;
            }
        }
    }


    public void makeCustomHeaderToNet() {
        if (this.customHeader != null) {
            //��header��ȡ�����Key class��Ӧ������filed
            Field[] fields = getClazzFields(customHeader.getClass());
            if (null == this.extFields) {
                this.extFields = new HashMap<String, String>();
            }

            for (Field field : fields) { //����fileds����
                if (!Modifier.isStatic(field.getModifiers())) {
                    String name = field.getName(); //�õ�filed��name
                    if (!name.startsWith("this")) {
                        Object value = null;
                        try {
                            field.setAccessible(true);
                            //�õ�filed��value
                            value = field.get(this.customHeader);
                        }
                        catch (IllegalArgumentException e) {
                        }
                        catch (IllegalAccessException e) {
                        }

                        if (value != null) { //д�뵱ǰRPC��extFields MAp��
                            this.extFields.put(name, value.toString());
                        }
                    }
                }
            }
        }
    }


    public CommandCustomHeader readCustomHeader() {
        return customHeader;
    }


    public void writeCustomHeader(CommandCustomHeader customHeader) {
        this.customHeader = customHeader;
    }

    private static final String StringCanonicalName = String.class.getCanonicalName();//

    private static final String DoubleCanonicalName1 = Double.class.getCanonicalName();//
    private static final String DoubleCanonicalName2 = double.class.getCanonicalName();//

    private static final String IntegerCanonicalName1 = Integer.class.getCanonicalName();//
    private static final String IntegerCanonicalName2 = int.class.getCanonicalName();//

    private static final String LongCanonicalName1 = Long.class.getCanonicalName();//
    private static final String LongCanonicalName2 = long.class.getCanonicalName();//

    private static final String BooleanCanonicalName1 = Boolean.class.getCanonicalName();//
    private static final String BooleanCanonicalName2 = boolean.class.getCanonicalName();//


    // header data ����:{"code":15,"extFields":{"topic":"yyztest2","queueId":"3","consumerGroup":"yyzGroup2","commitOffset":"28"},"flag":2,
    // "language":"JAVA","opaque":726,"serializeTypeCurrentRPC":"JSON","version":115}

    //����header data��extFields��Ϣ��extFields
    public CommandCustomHeader decodeCommandCustomHeader(Class<? extends CommandCustomHeader> classHeader) throws RemotingCommandException {
        CommandCustomHeader objectHeader;
        try {
            //��������������Ĳ����� ConsumerSendMsgBackRequestHeader.class ��������newһ�� ConsumerSendMsgBackRequestHeader ����
            objectHeader = classHeader.newInstance();
        }
        catch (InstantiationException e) {
            return null;
        }
        catch (IllegalAccessException e) {
            return null;
        }

        if (this.extFields != null) {
            Field[] fields = getClazzFields(classHeader);
            for (Field field : fields) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    String fieldName = field.getName();
                    if (!fieldName.startsWith("this")) {
                        try {
                            String value = this.extFields.get(fieldName);
                            if (null == value) {
                                Annotation annotation = getNotNullAnnotation(field);
                                if (annotation != null) {
                                    throw new RemotingCommandException("the custom field <" + fieldName + "> is null");
                                }

                                continue;
                            }

                            field.setAccessible(true);
                            String type = getCanonicalName(field.getType());
                            Object valueParsed;

                            if (type.equals(StringCanonicalName)) {
                                valueParsed = value;
                            }
                            else if (type.equals(IntegerCanonicalName1) || type.equals(IntegerCanonicalName2)) {
                                valueParsed = Integer.parseInt(value);
                            }
                            else if (type.equals(LongCanonicalName1) || type.equals(LongCanonicalName2)) {
                                valueParsed = Long.parseLong(value);
                            }
                            else if (type.equals(BooleanCanonicalName1) || type.equals(BooleanCanonicalName2)) {
                                valueParsed = Boolean.parseBoolean(value);
                            }
                            else if (type.equals(DoubleCanonicalName1) || type.equals(DoubleCanonicalName2)) {
                                valueParsed = Double.parseDouble(value);
                            }
                            else {
                                throw new RemotingCommandException("the custom field <" + fieldName + "> type is not supported");
                            }

                            field.set(objectHeader, valueParsed);

                        }
                        catch (Throwable e) {
                        }
                    }
                }
            }

            objectHeader.checkFields();
        }

        return objectHeader;
    }


    private byte[] headerEncode() {
        //length + header length + header data  + body data�е�header dataд��extFields MAp��
        this.makeCustomHeaderToNet();
        if (SerializeType.ROCKETMQ == serializeTypeCurrentRPC) {
            //���Ű�
            return RocketMQSerializable.rocketMQProtocolEncode(this);
        }
        else {
            return RemotingSerializable.encode(this); //
        }
    }

    //header data���л�����
    private static RemotingCommand headerDecode(byte[] headerData, SerializeType type) {
        switch (type) {
        case JSON: //JSON���л���ʽ
            //�����л� header data �� ��RemotingCommand
            RemotingCommand resultJson = RemotingSerializable.decode(headerData, RemotingCommand.class);
            resultJson.setSerializeTypeCurrentRPC(type);
            return resultJson;
        case ROCKETMQ: //��JSON��ʽ����һ��
            RemotingCommand resultRMQ = RocketMQSerializable.rocketMQProtocolDecode(headerData);
            resultRMQ.setSerializeTypeCurrentRPC(type);
            return resultRMQ;
        default:
            break;
        }

        return null;
    }

    /*
1��length(�ܳ��ȣ���4���ֽڴ洢)
2��header length ����ͷ���ȣ� //header lengthһ�����ֽڣ����еĵ�1�ֽ������洢code��ʶ(SerializeType.JSON ���� SerializeType.ROCKETMQ)���� markProtocolType
3��header data(��ͷ����)
4��body data(���ݰ�����)
    * */
    public ByteBuffer encode() {
        // 1> header length size
        int length = 4;

        // 2> header data length
        byte[] headerData = this.headerEncode();
        length += headerData.length;

        // 3> body data length
        if (this.body != null) {
            length += body.length;
        }

        ByteBuffer result = ByteBuffer.allocate(4 + length);

        // length
        result.putInt(length);

        // header length //header lengthһ�����ֽڣ����еĵ�1�ֽ������洢code��ʶ(SerializeType.JSON ���� SerializeType.ROCKETMQ)����markProtocolType
        result.put(markProtocolType(headerData.length, serializeTypeCurrentRPC));

        // header data
        result.put(headerData);

        // body data;
        if (this.body != null) {
            result.put(this.body);
        }

        result.flip();

        return result;
    }

    public ByteBuffer encodeHeader() {
        return encodeHeader(this.body != null ? this.body.length : 0);
    }

    //header lengthһ�����ֽڣ����еĵ�1�ֽ������洢code��ʶ(SerializeType.JSON ���� SerializeType.ROCKETMQ)����markProtocolType
    //length + header length + header data  + body data
    //������ͷ length + header length + header data
    public ByteBuffer encodeHeader(final int bodyLength) {
        // 1> header length size
        int length = 4;

        // 2> header data length
        byte[] headerData;
        headerData = this.headerEncode(); //header dataת��Ϊ�����ֽ���

        length += headerData.length;

        // 3> body data length
        length += bodyLength;

        ByteBuffer result = ByteBuffer.allocate(4 + length - bodyLength); //length + header length + header data ������body

        // length
        result.putInt(length);

        // header length
        result.put(markProtocolType(headerData.length, serializeTypeCurrentRPC));

        // header data
        result.put(headerData);

        result.flip();

        return result;
    }

    /*
1��length(�ܳ��ȣ���4���ֽڴ洢)
2��header length ����ͷ���ȣ� //header lengthһ�����ֽڣ����еĵ�1�ֽ������洢code��ʶ(SerializeType.JSON ���� SerializeType.ROCKETMQ)����markProtocolType
3��header data(��ͷ����)
4��body data(���ݰ�����)
    * */
    public static RemotingCommand decode(final byte[] array) {
        ByteBuffer byteBuffer = ByteBuffer.wrap(array);
        return decode(byteBuffer);
    }

    /*
1��length(�ܳ��ȣ���4���ֽڴ洢)
2��header length ����ͷ���ȣ�//header lengthһ�����ֽڣ����еĵ�1�ֽ������洢code��ʶ(SerializeType.JSON ���� SerializeType.ROCKETMQ)���� markProtocolType
3��header data(��ͷ����)
4��body data(���ݰ�����)
    * */
    public static RemotingCommand decode(final ByteBuffer byteBuffer) {
        int length = byteBuffer.limit();
        int oriHeaderLen = byteBuffer.getInt();
        int headerLength = getHeaderLength(oriHeaderLen);

        byte[] headerData = new byte[headerLength];
        byteBuffer.get(headerData);

        //�����л�����header data��RemotingCommand��
        RemotingCommand cmd = headerDecode(headerData, getProtocolType(oriHeaderLen));

        int bodyLength = length - 4 - headerLength;
        byte[] bodyData = null;
        if (bodyLength > 0) {
            bodyData = new byte[bodyLength];
            byteBuffer.get(bodyData);
        }
        cmd.body = bodyData; //��body���ֻ�ԭ������Ҳ���ǰ���Ϣ����

        return cmd;
    }

    //header lengthһ�����ֽڣ����еĵ�1�ֽ������洢code��ʶ(SerializeType.JSON ���� SerializeType.ROCKETMQ)����markProtocolType
    public static byte[] markProtocolType(int source, SerializeType type) {
        byte[] result = new byte[4];
        result[0] = type.getCode();
        result[1] = (byte) ((source >> 16) & 0xFF);
        result[2] = (byte) ((source >> 8) & 0xFF);
        result[3] = (byte) (source & 0xFF);
        return result;
    }


    public static SerializeType getProtocolType(int source) {
        return SerializeType.valueOf((byte) ((source >> 24) & 0xFF));
    }


    public static int getHeaderLength(int length) {
        return length & 0xFFFFFF;
    }


    public void markResponseType() {
        int bits = 1 << RPC_TYPE;
        this.flag |= bits;
    }


    @JSONField(serialize = false)
    public boolean isResponseType() {
        int bits = 1 << RPC_TYPE;
        return (this.flag & bits) == bits;
    }


    public void markOnewayRPC() {
        int bits = 1 << RPC_ONEWAY;
        this.flag |= bits;
    }


    @JSONField(serialize = false)
    public boolean isOnewayRPC() {
        int bits = 1 << RPC_ONEWAY;
        return (this.flag & bits) == bits;
    }


    public int getCode() {
        return code;
    }


    public void setCode(int code) {
        this.code = code;
    }


    @JSONField(serialize = false)
    public RemotingCommandType getType() {
        if (this.isResponseType()) {
            return RemotingCommandType.RESPONSE_COMMAND;
        }

        return RemotingCommandType.REQUEST_COMMAND;
    }


    public LanguageCode getLanguage() {
        return language;
    }


    public void setLanguage(LanguageCode language) {
        this.language = language;
    }


    public int getVersion() {
        return version;
    }


    public void setVersion(int version) {
        this.version = version;
    }


    public int getOpaque() {
        return opaque;
    }


    public void setOpaque(int opaque) {
        this.opaque = opaque;
    }


    public int getFlag() {
        return flag;
    }


    public void setFlag(int flag) {
        this.flag = flag;
    }


    public String getRemark() {
        return remark;
    }


    public void setRemark(String remark) {
        this.remark = remark;
    }


    public byte[] getBody() {
        return body;
    }


    public void setBody(byte[] body) {
        this.body = body;
    }


    public HashMap<String, String> getExtFields() {
        return extFields;
    }


    public void setExtFields(HashMap<String, String> extFields) {
        this.extFields = extFields;
    }


    public static int createNewRequestId() {
        return RequestId.incrementAndGet();
    }


    public void addExtField(String key, String value) {
        if (null == extFields) {
            extFields = new HashMap<String, String>();
        }
        extFields.put(key, value);
    }


    public static SerializeType getSerializeTypeConfigInThisServer() {
        return SerializeTypeConfigInThisServer;
    }


    private static boolean isBlank(String str) {
        int strLen;
        if (str == null || (strLen = str.length()) == 0) {
            return true;
        }
        for (int i = 0; i < strLen; i++) {
            if ((Character.isWhitespace(str.charAt(i)) == false)) {
                return false;
            }
        }
        return true;
    }


    @Override
    public String toString() {
        return "RemotingCommand [code=" + code + ", language=" + language + ", version=" + version + ", opaque=" + opaque + ", flag(B)="
                + Integer.toBinaryString(flag) + ", remark=" + remark + ", extFields=" + extFields + ", serializeTypeCurrentRPC="
                + serializeTypeCurrentRPC + "]";
    }


    public SerializeType getSerializeTypeCurrentRPC() {
        return serializeTypeCurrentRPC;
    }


    public void setSerializeTypeCurrentRPC(SerializeType serializeTypeCurrentRPC) {
        this.serializeTypeCurrentRPC = serializeTypeCurrentRPC;
    }
}