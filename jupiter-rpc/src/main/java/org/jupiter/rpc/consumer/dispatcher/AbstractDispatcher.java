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
package org.jupiter.rpc.consumer.dispatcher;

import java.util.List;
import java.util.Map;

import org.jupiter.common.util.JConstants;
import org.jupiter.common.util.Maps;
import org.jupiter.common.util.StackTraceUtil;
import org.jupiter.common.util.SystemClock;
import org.jupiter.common.util.internal.logging.InternalLogger;
import org.jupiter.common.util.internal.logging.InternalLoggerFactory;
import org.jupiter.rpc.DispatchType;
import org.jupiter.rpc.JClient;
import org.jupiter.rpc.JRequest;
import org.jupiter.rpc.JResponse;
import org.jupiter.rpc.consumer.ConsumerInterceptor;
import org.jupiter.rpc.consumer.future.DefaultInvokeFuture;
import org.jupiter.rpc.exception.JupiterRemoteException;
import org.jupiter.rpc.load.balance.LoadBalancer;
import org.jupiter.rpc.model.metadata.MessageWrapper;
import org.jupiter.rpc.model.metadata.MethodSpecialConfig;
import org.jupiter.rpc.model.metadata.ResultWrapper;
import org.jupiter.rpc.model.metadata.ServiceMetadata;
import org.jupiter.serialization.Serializer;
import org.jupiter.serialization.SerializerFactory;
import org.jupiter.serialization.SerializerType;
import org.jupiter.transport.Status;
import org.jupiter.transport.channel.CopyOnWriteGroupList;
import org.jupiter.transport.channel.JChannel;
import org.jupiter.transport.channel.JChannelGroup;
import org.jupiter.transport.channel.JFutureListener;
import org.jupiter.transport.payload.JRequestPayload;

/**
 * jupiter
 * org.jupiter.rpc.consumer.dispatcher
 *
 * 用于在集群范围内分发请求的骨架类
 * @author jiachun.fjc
 */
abstract class AbstractDispatcher implements Dispatcher {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractDispatcher.class);

    /**
     * 用于向provider 发起请求的客户端对象
     */
    private final JClient client;
    /**
     * 均衡负载对象
     */
    private final LoadBalancer loadBalancer;                    // 软负载均衡
    private final Serializer serializerImpl;                    // 序列化/反序列化impl
    /**
     * 设置在消费者端的拦截器
     */
    private ConsumerInterceptor[] interceptors;                 // 消费者端拦截器
    private long timeoutMillis = JConstants.DEFAULT_TIMEOUT;    // 调用超时时间设置
    // 针对指定方法单独设置的超时时间, 方法名为key, 方法参数类型不做区别对待
    private Map<String, Long> methodSpecialTimeoutMapping = Maps.newHashMap();

    public AbstractDispatcher(JClient client, SerializerType serializerType) {
        this(client, null, serializerType);
    }

    public AbstractDispatcher(JClient client, LoadBalancer loadBalancer, SerializerType serializerType) {
        this.client = client;
        this.loadBalancer = loadBalancer;
        this.serializerImpl = SerializerFactory.getSerializer(serializerType.value());
    }

    public Serializer serializer() {
        return serializerImpl;
    }

    public ConsumerInterceptor[] interceptors() {
        return interceptors;
    }

    @Override
    public Dispatcher interceptors(List<ConsumerInterceptor> interceptors) {
        if (interceptors != null && !interceptors.isEmpty()) {
            this.interceptors = interceptors.toArray(new ConsumerInterceptor[0]);
        }
        return this;
    }

    @Override
    public Dispatcher timeoutMillis(long timeoutMillis) {
        if (timeoutMillis > 0) {
            this.timeoutMillis = timeoutMillis;
        }
        return this;
    }

    @Override
    public Dispatcher methodSpecialConfigs(List<MethodSpecialConfig> methodSpecialConfigs) {
        if (!methodSpecialConfigs.isEmpty()) {
            for (MethodSpecialConfig config : methodSpecialConfigs) {
                long timeoutMillis = config.getTimeoutMillis();
                if (timeoutMillis > 0) {
                    methodSpecialTimeoutMapping.put(config.getMethodName(), timeoutMillis);
                }
            }
        }
        return this;
    }

    protected long getMethodSpecialTimeoutMillis(String methodName) {
        Long methodTimeoutMillis = methodSpecialTimeoutMapping.get(methodName);
        if (methodTimeoutMillis != null && methodTimeoutMillis > 0) {
            return methodTimeoutMillis;
        }
        return timeoutMillis;
    }

    /**
     * 设置某组服务调用参数 选择连接向合适服务器的channel
     * 存在2个维度
     * 第一级 找寻某个服务下 所有的服务提供节点
     * 第二级 从连接到这个节点的所有channel 中选择一个
     * @param metadata
     * @return
     */
    protected JChannel select(ServiceMetadata metadata) {
        // 代表连接到目标服务的所有channel
        CopyOnWriteGroupList groups = client
                .connector()
                .directory(metadata);
        // 这里有2个均衡负载级别 一个是多个机器提供某个服务  选择合适的机器
        // 第二个是针对某个机器的多个连接中轮询返回一条连接

        // 该group 代表某一provider 节点下的多个channel
        JChannelGroup group = loadBalancer.select(groups, metadata);

        if (group != null) {
            if (group.isAvailable()) {
                // 通过这种方式均衡负载
                return group.next();
            }

            // to the deadline (no available channel), the time exceeded the predetermined limit
            long deadline = group.deadlineMillis();
            // 代表当前group 中不存在连接 会设置一个超时时间 在时间内 如果重新创建了连接 就可以不移除该group 如果deadline 也超时 那么就移除该group
            if (deadline > 0 && SystemClock.millisClock().now() > deadline) {
                // 直接从服务提供者中移除掉该组
                boolean removed = groups.remove(group);
                if (removed) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Removed channel group: {} in directory: {} on [select].",
                                group, metadata.directoryString());
                    }
                }
            }
        } else {
            // for 3 seconds, expired not wait
            // 阻塞直到生成新的连接
            if (!client.awaitConnections(metadata, 3000)) {
                throw new IllegalStateException("No connections");
            }
        }

        // 如果均衡负载找到的group 不允许使用 那么就从所有group 中随机找一个
        JChannelGroup[] snapshot = groups.getSnapshot();
        for (JChannelGroup g : snapshot) {
            if (g.isAvailable()) {
                return g.next();
            }
        }

        throw new IllegalStateException("No channel");
    }

    /**
     * 返回某一服务的 所有服务提供者对应的channelGroup
     * @param metadata
     * @return
     */
    protected JChannelGroup[] groups(ServiceMetadata metadata) {
        return client.connector()
                .directory(metadata)
                .getSnapshot();
    }

    /**
     * 将请求发送到 provider  在指定channel 后调用
     * @param channel
     * @param request
     * @param returnType
     * @param dispatchType
     * @param <T>
     * @return
     */
    @SuppressWarnings("all")
    protected <T> DefaultInvokeFuture<T> write(
            final JChannel channel, final JRequest request, final Class<T> returnType, final DispatchType dispatchType) {
        final MessageWrapper message = request.message();
        // 获取该方法级别的 特殊超时时间 没有的话使用默认的超时时间
        final long timeoutMillis = getMethodSpecialTimeoutMillis(message.getMethodName());
        // 返回该client 所有的拦截器
        final ConsumerInterceptor[] interceptors = interceptors();
        // invokeId 是用于链路追踪的  将参数封装成以一个future 对象 并设置拦截器
        final DefaultInvokeFuture<T> future = DefaultInvokeFuture
                .with(request.invokeId(), channel, timeoutMillis, returnType, dispatchType)
                .interceptors(interceptors);

        if (interceptors != null) {
            for (int i = 0; i < interceptors.length; i++) {
                // 执行前置拦截
                interceptors[i].beforeInvoke(request, channel);
            }
        }

        // 获取请求对象的 消息体载体
        final JRequestPayload payload = request.payload();

        // 写入数据体 并设置结果监听器
        channel.write(payload, new JFutureListener<JChannel>() {

            @Override
            public void operationSuccess(JChannel channel) throws Exception {
                // 标记已发送  用户应该是通过该标识确认结果是否完成
                future.markSent();

                // 如果是单播 那么清除本次消息体 释放内存
                if (dispatchType == DispatchType.ROUND) {
                    payload.clear();
                }
            }

            @Override
            public void operationFailure(JChannel channel, Throwable cause) throws Exception {
                if (dispatchType == DispatchType.ROUND) {
                    payload.clear();
                }

                if (logger.isWarnEnabled()) {
                    logger.warn("Writes {} fail on {}, {}.", request, channel, StackTraceUtil.stackTrace(cause));
                }

                // 操作失败时生成一个异常包装对象 并设置到response中
                ResultWrapper result = new ResultWrapper();
                result.setError(new JupiterRemoteException(cause));

                JResponse response = new JResponse(payload.invokeId());
                response.status(Status.CLIENT_ERROR);
                response.result(result);

                // 触发 拦截器后置逻辑
                DefaultInvokeFuture.fakeReceived(channel, response, dispatchType);
            }
        });

        return future;
    }
}
