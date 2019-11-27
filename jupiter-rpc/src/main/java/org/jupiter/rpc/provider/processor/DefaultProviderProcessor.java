/*
 * Copyright (c) 2015 The Jupiter Project
 *
 * Licensed under the Apache License, version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jupiter.rpc.provider.processor;

import org.jupiter.common.util.StackTraceUtil;
import org.jupiter.common.util.ThrowUtil;
import org.jupiter.common.util.internal.logging.InternalLogger;
import org.jupiter.common.util.internal.logging.InternalLoggerFactory;
import org.jupiter.rpc.JRequest;
import org.jupiter.rpc.executor.CloseableExecutor;
import org.jupiter.rpc.flow.control.FlowController;
import org.jupiter.rpc.model.metadata.ResultWrapper;
import org.jupiter.rpc.provider.LookupService;
import org.jupiter.rpc.provider.processor.task.MessageTask;
import org.jupiter.serialization.Serializer;
import org.jupiter.serialization.SerializerFactory;
import org.jupiter.serialization.io.OutputBuf;
import org.jupiter.transport.CodecConfig;
import org.jupiter.transport.Status;
import org.jupiter.transport.channel.JChannel;
import org.jupiter.transport.channel.JFutureListener;
import org.jupiter.transport.payload.JRequestPayload;
import org.jupiter.transport.payload.JResponsePayload;
import org.jupiter.transport.processor.ProviderProcessor;

/**
 * jupiter
 * org.jupiter.rpc.provider.processor
 *
 * 提供者端请求处理器
 * @author jiachun.fjc
 */
public abstract class DefaultProviderProcessor implements ProviderProcessor, LookupService, FlowController<JRequest> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultProviderProcessor.class);

    private final CloseableExecutor executor;

    public DefaultProviderProcessor() {
        this(ProviderExecutors.executor());
    }

    public DefaultProviderProcessor(CloseableExecutor executor) {
        this.executor = executor;
    }

    @Override
    public void handleRequest(JChannel channel, JRequestPayload requestPayload) throws Exception {
        MessageTask task = new MessageTask(this, channel, new JRequest(requestPayload));
        if (executor == null) {
            // 使用I/O 线程处理
            channel.addTask(task);
        } else {
            // 一般该对象都会被设置 一种较好的实现是 disruptor
            executor.execute(task);
        }
    }

    /**
     * @param channel
     * @param request
     * @param status
     * @param cause
     */
    @Override
    public void handleException(JChannel channel, JRequestPayload request, Status status, Throwable cause) {
        logger.error("An exception was caught while processing request: {}, {}.",
                channel.remoteAddress(), StackTraceUtil.stackTrace(cause));

        doHandleException(
                channel, request.invokeId(), request.serializerCode(), status.value(), cause, false);
    }

    @Override
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
        }
    }

    /**
     * 针对调用provider 本身发出的异常是不需要关闭channel的 (业务异常)
     * @param channel
     * @param request
     * @param status
     * @param cause
     */
    public void handleException(JChannel channel, JRequest request, Status status, Throwable cause) {
        logger.error("An exception was caught while processing request: {}, {}.",
                channel.remoteAddress(), StackTraceUtil.stackTrace(cause));

        doHandleException(
                channel, request.invokeId(), request.serializerCode(), status.value(), cause, false);
    }

    /**
     * 处理请求被拒绝的情况 比如某个请求进来 发现被flowController阻挡了
     * @param channel
     * @param request
     * @param status
     * @param cause
     */
    public void handleRejected(JChannel channel, JRequest request, Status status, Throwable cause) {
        if (logger.isWarnEnabled()) {
            logger.warn("Service rejected: {}, {}.", channel.remoteAddress(), StackTraceUtil.stackTrace(cause));
        }

        doHandleException(
                channel, request.invokeId(), request.serializerCode(), // 传入req的序列化方式并以相同方式序列化res对象
                status.value(), cause,
                true // 当请求被拒时 选择重新创建channel  这样在均衡负载时就会重新预热 那么被分派的请求也会少
        );
    }

    /**
     * 处理异常状态
     * @param channel
     * @param invokeId
     * @param s_code
     * @param status
     * @param cause
     * @param closeChannel
     */
    private void doHandleException(
            JChannel channel, long invokeId, byte s_code, byte status, Throwable cause, boolean closeChannel) {

        ResultWrapper result = new ResultWrapper();
        // 截断cause, 避免客户端无法找到cause类型而无法序列化
        result.setError(ThrowUtil.cutCause(cause));

        Serializer serializer = SerializerFactory.getSerializer(s_code);

        JResponsePayload response = new JResponsePayload(invokeId);
        response.status(status);
        // 如果只是进行了低拷贝 此时只是做一个较低层的序列化
        if (CodecConfig.isCodecLowCopy()) {
            OutputBuf outputBuf =
                    serializer.writeObject(channel.allocOutputBuf(), result);
            response.outputBuf(s_code, outputBuf);
        } else {
            // 完整的序列化成比特流
            byte[] bytes = serializer.writeObject(result);
            response.bytes(s_code, bytes);
        }

        // 代表该异常是否需要关闭连接
        if (closeChannel) {
            channel.write(response, JChannel.CLOSE);
        } else {
            // 否则打印日志
            channel.write(response, new JFutureListener<JChannel>() {

                @Override
                public void operationSuccess(JChannel channel) throws Exception {
                    logger.debug("Service error message sent out: {}.", channel);
                }

                @Override
                public void operationFailure(JChannel channel, Throwable cause) throws Exception {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Service error message sent failed: {}, {}.", channel,
                                StackTraceUtil.stackTrace(cause));
                    }
                }
            });
        }
    }
}
