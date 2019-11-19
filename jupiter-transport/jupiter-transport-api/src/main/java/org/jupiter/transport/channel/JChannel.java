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
package org.jupiter.transport.channel;

import java.net.SocketAddress;

import org.jupiter.serialization.io.OutputBuf;

/**
 * A nexus to a network socket or a component which is capable of I/O
 * operations such as read, write.
 *
 * jupiter
 * org.jupiter.transport.channel
 * 用于I/O操作的管道对象
 * @author jiachun.fjc
 */
public interface JChannel {

    /**
     * A {@link JFutureListener} that closes the {@link JChannel}.
     * 静态类 用于关闭channel  跟 netty的 CLOSE 监听器一样
     */
    JFutureListener<JChannel> CLOSE = new JFutureListener<JChannel>() {

        // 无论本次操作成功还是失败 都会关闭channel

        @Override
        public void operationSuccess(JChannel channel) throws Exception {
            channel.close();
        }

        @Override
        public void operationFailure(JChannel channel, Throwable cause) throws Exception {
            channel.close();
        }
    };

    /**
     * Returns the identifier of this {@link JChannel}.
     * 每个channel 有自己的唯一标识
     */
    String id();

    /**
     * Return {@code true} if the {@link JChannel} is active and so connected.
     * 当前channel 是否还存活
     */
    boolean isActive();

    /**
     * Return {@code true} if the current {@link Thread} is executed in the
     * IO thread, {@code false} otherwise.
     * 当前是否在I/O线程中
     */
    boolean inIoThread();

    /**
     * Returns the local address where this channel is bound to.
     * 返回该channel 绑定的本地地址
     */
    SocketAddress localAddress();

    /**
     * Returns the remote address where this channel is connected to.
     * 返回channel 绑定的对端地址
     */
    SocketAddress remoteAddress();

    /**
     * Returns {@code true} if and only if the I/O thread will perform the
     * requested write operation immediately.
     * Any write requests made when this method returns {@code false} are
     * queued until the I/O thread is ready to process the queued write requests.
     * 当前channel 是否可写  (网络缓冲区是否有足够空间)
     */
    boolean isWritable();

    /**
     * Is set up automatic reconnection.
     * 该channel 是否具备自动重连功能
     */
    boolean isMarkedReconnect();

    /**
     * Returns {@code true} if and only if read(socket) will be invoked
     * automatically so that a user application doesn't need to call it
     * at all. The default value is {@code true}.
     * 是否开启自动读取  这样就不用客户端自主的发起读取了
     */
    boolean isAutoRead();

    /**
     * Sets if read(socket) will be invoked automatically so that a user
     * application doesn't need to call it at all. The default value is
     * {@code true}.
     */
    void setAutoRead(boolean autoRead);

    /**
     * Requests to close this {@link JChannel}.
     * 关闭本channel
     */
    JChannel close();

    /**
     * Requests to close this {@link JChannel}.
     * 关闭channel 并根据结果触发 listener
     */
    JChannel close(JFutureListener<JChannel> listener);

    /**
     * Requests to write a message on the channel.
     * 将某个数据通过缓冲区写到对端
     */
    JChannel write(Object msg);

    /**
     * Requests to write a message on the channel.
     */
    JChannel write(Object msg, JFutureListener<JChannel> listener);

    /**
     * Add a task will execute in the io thread later.
     * 为channel 设置任务 并在之后的时间执行 应该是跟 netty的类似 一部分时间用于I/O操作一部分用于执行普通任务
     */
    void addTask(Runnable task);

    /**
     * Allocate a {@link OutputBuf}.
     * 分配一个用于写到对端的缓冲区
     */
    OutputBuf allocOutputBuf();
}
