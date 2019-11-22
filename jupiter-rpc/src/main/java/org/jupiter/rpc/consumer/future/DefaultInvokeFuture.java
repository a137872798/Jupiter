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
package org.jupiter.rpc.consumer.future;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.jupiter.common.concurrent.NamedThreadFactory;
import org.jupiter.common.util.JConstants;
import org.jupiter.common.util.Maps;
import org.jupiter.common.util.SystemPropertyUtil;
import org.jupiter.common.util.internal.logging.InternalLogger;
import org.jupiter.common.util.internal.logging.InternalLoggerFactory;
import org.jupiter.common.util.timer.HashedWheelTimer;
import org.jupiter.common.util.timer.Timeout;
import org.jupiter.common.util.timer.TimerTask;
import org.jupiter.rpc.DispatchType;
import org.jupiter.rpc.JResponse;
import org.jupiter.rpc.consumer.ConsumerInterceptor;
import org.jupiter.rpc.exception.JupiterBizException;
import org.jupiter.rpc.exception.JupiterRemoteException;
import org.jupiter.rpc.exception.JupiterSerializationException;
import org.jupiter.rpc.exception.JupiterTimeoutException;
import org.jupiter.rpc.model.metadata.ResultWrapper;
import org.jupiter.transport.Status;
import org.jupiter.transport.channel.JChannel;

/**
 * jupiter
 * org.jupiter.rpc.consumer.future
 *
 * 进行RPC 调用时对应的 future 对象 实现异步调用
 * @author jiachun.fjc
 */
public class DefaultInvokeFuture<V> extends CompletableFuture<V> implements InvokeFuture<V> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultInvokeFuture.class);

    /**
     * 等待响应结果的超时时间 超过该时间后会从 池中移除
     */
    private static final long DEFAULT_TIMEOUT_NANOSECONDS = TimeUnit.MILLISECONDS.toNanos(JConstants.DEFAULT_TIMEOUT);

    /**
     * 响应池的最大长度
     */
    private static final int FUTURES_CONTAINER_INITIAL_CAPACITY =
            SystemPropertyUtil.getInt("jupiter.rpc.invoke.futures_container_initial_capacity", 1024);
    /**
     * 定时扫描响应池结果的时间间隔
     */
    private static final long TIMEOUT_SCANNER_INTERVAL_MILLIS =
            SystemPropertyUtil.getLong("jupiter.rpc.invoke.timeout_scanner_interval_millis", 50);

    // 下面2个容器 是全局范围 而每个DefaultInvokeFuture 都是对应一次请求的

    /**
     * 无锁并发容器  用于存放 invokeId 与 等待对端设置结果的future 对象
     */
    private static final ConcurrentMap<Long, DefaultInvokeFuture<?>> roundFutures =
            Maps.newConcurrentMapLong(FUTURES_CONTAINER_INITIAL_CAPACITY);
    /**
     * 针对广播的容器
     */
    private static final ConcurrentMap<String, DefaultInvokeFuture<?>> broadcastFutures =
            Maps.newConcurrentMap(FUTURES_CONTAINER_INITIAL_CAPACITY);

    /**
     * 使用复杂度  O(1) 的数据结构
     */
    private static final HashedWheelTimer timeoutScanner =
            new HashedWheelTimer(
                    new NamedThreadFactory("futures.timeout.scanner", true),
                    TIMEOUT_SCANNER_INTERVAL_MILLIS, TimeUnit.MILLISECONDS,
                    4096
            );

    /**
     * 本次调用id
     */
    private final long invokeId; // request.invokeId, 广播的场景可以重复

    /**
     * 本次调用使用channel
     */
    private final JChannel channel;
    /**
     * 本次执行返回结果类型
     */
    private final Class<V> returnType;
    /**
     * 本次调用的超时时间
     */
    private final long timeout;
    private final long startTime = System.nanoTime();

    /**
     * 辨别本次future 是 client/server发出的
     */
    private volatile boolean sent = false;

    /**
     * 结果对象内部维护的一组拦截器
     */
    private ConsumerInterceptor[] interceptors;

    public static <T> DefaultInvokeFuture<T> with(
            long invokeId, JChannel channel, long timeoutMillis, Class<T> returnType, DispatchType dispatchType) {

        return new DefaultInvokeFuture<>(invokeId, channel, timeoutMillis, returnType, dispatchType);
    }

    private DefaultInvokeFuture(
            long invokeId, JChannel channel, long timeoutMillis, Class<V> returnType, DispatchType dispatchType) {

        this.invokeId = invokeId;
        this.channel = channel;
        this.timeout = timeoutMillis > 0 ? TimeUnit.MILLISECONDS.toNanos(timeoutMillis) : DEFAULT_TIMEOUT_NANOSECONDS;
        this.returnType = returnType;

        TimeoutTask timeoutTask;

        switch (dispatchType) {
            // 不同的类型 存入不同的响应池
            case ROUND:
                // round 直接以 invokeId 作为key
                roundFutures.put(invokeId, this);
                timeoutTask = new TimeoutTask(invokeId);
                break;
            case BROADCAST:
                String channelId = channel.id();
                // 该对象始终以单个channel 为单位 如果是 broadcast 那么就是多存一些对象 通过 invokeId相同来判别是统一批调用
                broadcastFutures.put(subInvokeId(channelId, invokeId), this);
                timeoutTask = new TimeoutTask(channelId, invokeId);
                break;
            default:
                throw new IllegalArgumentException("Unsupported " + dispatchType);
        }

        timeoutScanner.newTimeout(timeoutTask, timeout, TimeUnit.NANOSECONDS);
    }

    public JChannel channel() {
        return channel;
    }

    @Override
    public Class<V> returnType() {
        return returnType;
    }

    /**
     * 当调用 getResult 时阻塞线程 等待结果设置
     * @return
     * @throws Throwable
     */
    @Override
    public V getResult() throws Throwable {
        try {
            return get(timeout, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            throw new JupiterTimeoutException(e, channel.remoteAddress(),
                    sent ? Status.SERVER_TIMEOUT : Status.CLIENT_TIMEOUT);
        }
    }

    public void markSent() {
        sent = true;
    }

    public ConsumerInterceptor[] interceptors() {
        return interceptors;
    }

    /**
     * 为该future 对象追加拦截器
     * @param interceptors
     * @return
     */
    public DefaultInvokeFuture<V> interceptors(ConsumerInterceptor[] interceptors) {
        this.interceptors = interceptors;
        return this;
    }

    /**
     * 当收到一个结果后 进行处理
     * @param response
     */
    @SuppressWarnings("all")
    private void doReceived(JResponse response) {
        byte status = response.status();

        if (status == Status.OK.value()) {
            ResultWrapper wrapper = response.result();
            // 以成功方式设置结果
            complete((V) wrapper.getResult());
        } else {
            setException(status, response);
        }

        // 后置处理
        ConsumerInterceptor[] interceptors = this.interceptors; // snapshot
        if (interceptors != null) {
            for (int i = interceptors.length - 1; i >= 0; i--) {
                interceptors[i].afterInvoke(response, channel);
            }
        }
    }

    /**
     * 以异常方式设置 CompletableFuture
     * @param status
     * @param response
     */
    private void setException(byte status, JResponse response) {
        Throwable cause;
        if (status == Status.SERVER_TIMEOUT.value()) {
            cause = new JupiterTimeoutException(channel.remoteAddress(), Status.SERVER_TIMEOUT);
        } else if (status == Status.CLIENT_TIMEOUT.value()) {
            cause = new JupiterTimeoutException(channel.remoteAddress(), Status.CLIENT_TIMEOUT);
        } else if (status == Status.DESERIALIZATION_FAIL.value()) {
            ResultWrapper wrapper = response.result();
            cause = (JupiterSerializationException) wrapper.getResult();
        } else if (status == Status.SERVICE_EXPECTED_ERROR.value()) {
            ResultWrapper wrapper = response.result();
            cause = (Throwable) wrapper.getResult();
        } else if (status == Status.SERVICE_UNEXPECTED_ERROR.value()) {
            ResultWrapper wrapper = response.result();
            String message = String.valueOf(wrapper.getResult());
            cause = new JupiterBizException(message, channel.remoteAddress());
        } else {
            ResultWrapper wrapper = response.result();
            Object result = wrapper.getResult();
            if (result instanceof JupiterRemoteException) {
                cause = (JupiterRemoteException) result;
            } else {
                cause = new JupiterRemoteException(response.toString(), channel.remoteAddress());
            }
        }
        completeExceptionally(cause);
    }

    /**
     * 代表future 对象收到了一个结果
     * @param channel
     * @param response
     */
    public static void received(JChannel channel, JResponse response) {
        long invokeId = response.id();

        DefaultInvokeFuture<?> future = roundFutures.remove(invokeId);

        if (future == null) {
            // 广播场景下做出了一点让步, 多查询了一次roundFutures
            future = broadcastFutures.remove(subInvokeId(channel.id(), invokeId));
        }

        if (future == null) {
            logger.warn("A timeout response [{}] finally returned on {}.", response, channel);
            return;
        }

        future.doReceived(response);
    }

    public static void fakeReceived(JChannel channel, JResponse response, DispatchType dispatchType) {
        long invokeId = response.id();

        DefaultInvokeFuture<?> future = null;

        if (dispatchType == DispatchType.ROUND) {
            future = roundFutures.remove(invokeId);
        } else if (dispatchType == DispatchType.BROADCAST) {
            future = broadcastFutures.remove(subInvokeId(channel.id(), invokeId));
        }

        if (future == null) {
            return; // 正确结果在超时被处理之前返回
        }

        future.doReceived(response);
    }

    private static String subInvokeId(String channelId, long invokeId) {
        return channelId + invokeId;
    }

    /**
     * 清除 future池的定时任务
     */
    static final class TimeoutTask implements TimerTask {

        private final String channelId;
        private final long invokeId;

        public TimeoutTask(long invokeId) {
            this.channelId = null;
            this.invokeId = invokeId;
        }

        public TimeoutTask(String channelId, long invokeId) {
            this.channelId = channelId;
            this.invokeId = invokeId;
        }

        @Override
        public void run(Timeout timeout) throws Exception {
            DefaultInvokeFuture<?> future;

            if (channelId == null) {
                // round
                future = roundFutures.remove(invokeId);
            } else {
                // broadcast
                future = broadcastFutures.remove(subInvokeId(channelId, invokeId));
            }

            if (future != null) {
                processTimeout(future);
            }
        }

        /**
         * 返回超时任务
         * @param future
         */
        private void processTimeout(DefaultInvokeFuture<?> future) {
            if (System.nanoTime() - future.startTime > future.timeout) {
                JResponse response = new JResponse(future.invokeId);
                response.status(future.sent ? Status.SERVER_TIMEOUT : Status.CLIENT_TIMEOUT);

                future.doReceived(response);
            }
        }
    }
}
