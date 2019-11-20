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
package org.jupiter.transport.netty.channel;

import java.io.OutputStream;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.Queue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufOutputStream;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoop;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.netty.util.internal.PlatformDependent;

import org.jupiter.serialization.io.OutputBuf;
import org.jupiter.transport.JProtocolHeader;
import org.jupiter.transport.channel.JChannel;
import org.jupiter.transport.channel.JFutureListener;
import org.jupiter.transport.netty.alloc.AdaptiveOutputBufAllocator;
import org.jupiter.transport.netty.handler.connector.ConnectionWatchdog;

/**
 * 对Netty {@link Channel} 的包装, 通过静态方法 {@link #attachChannel(Channel)} 获取一个实例,
 * {@link NettyChannel} 实例构造后会attach到对应 {@link Channel} 上, 不需要每次创建.
 *
 * jupiter
 * org.jupiter.transport.netty.channel
 *
 * JChannel 实际上是对  netty.channel 的包装
 * @author jiachun.fjc
 */
public class NettyChannel implements JChannel {

    /**
     * 也是继承了 ConstantPool 的类 实际上就是有一个 ConcurrentHashMap 维护这些对象
     */
    private static final AttributeKey<NettyChannel> NETTY_CHANNEL_KEY = AttributeKey.valueOf("netty.channel");

    /**
     * Returns the {@link NettyChannel} for given {@link Channel}, this method never return null.
     */
    public static NettyChannel attachChannel(Channel channel) {
        Attribute<NettyChannel> attr = channel.attr(NETTY_CHANNEL_KEY);
        NettyChannel nChannel = attr.get();
        if (nChannel == null) {
            // 将本对象 连接到 channel 上
            NettyChannel newNChannel = new NettyChannel(channel);
            nChannel = attr.setIfAbsent(newNChannel);
            if (nChannel == null) {
                nChannel = newNChannel;
            }
        }
        return nChannel;
    }

    /**
     * netty 内置的channel
     */
    private final Channel channel;
    /**
     * buf 分配器
     */
    private final AdaptiveOutputBufAllocator.Handle allocHandle = AdaptiveOutputBufAllocator.DEFAULT.newHandle();

    /**
     * 返回 mpsc 对象 内部用于存放 CPU 密集型任务
     */
    private final Queue<Runnable> taskQueue = PlatformDependent.newMpscQueue(1024);
    /**
     * 用于执行 taskQueue 中的所有任务
     */
    private final Runnable runAllTasks = this::runAllTasks;

    private NettyChannel(Channel channel) {
        this.channel = channel;
    }

    public Channel channel() {
        return channel;
    }

    @Override
    public String id() {
        return channel.id().asShortText(); // 注意这里的id并不是全局唯一, 单节点中是唯一的
    }

    @Override
    public boolean isActive() {
        // jdk 底层有api 可以判断
        return channel.isActive();
    }

    @Override
    public boolean inIoThread() {
        // 当前线程是否是 eventLoop 绑定的线程
        return channel.eventLoop().inEventLoop();
    }

    @Override
    public SocketAddress localAddress() {
        return channel.localAddress();
    }

    @Override
    public SocketAddress remoteAddress() {
        return channel.remoteAddress();
    }

    @Override
    public boolean isWritable() {
        // 配合 ChannelOutboundBuffer 的高低水位
        return channel.isWritable();
    }

    /**
     * 开启重连狗
     * @return
     */
    @Override
    public boolean isMarkedReconnect() {
        ConnectionWatchdog watchdog = channel.pipeline().get(ConnectionWatchdog.class);
        return watchdog != null && watchdog.isStarted();
    }

    @Override
    public boolean isAutoRead() {
        return channel.config().isAutoRead();
    }

    @Override
    public void setAutoRead(boolean autoRead) {
        channel.config().setAutoRead(autoRead);
    }

    @Override
    public JChannel close() {
        // 内部调用jdk  channel.close() 关闭连接
        channel.close();
        return this;
    }

    /**
     * 关闭 同时根据结果触发监听器
     * @param listener
     * @return
     */
    @Override
    public JChannel close(final JFutureListener<JChannel> listener) {
        final JChannel jChannel = this;
        channel.close().addListener((ChannelFutureListener) future -> {
            if (future.isSuccess()) {
                listener.operationSuccess(jChannel);
            } else {
                listener.operationFailure(jChannel, future.cause());
            }
        });
        return jChannel;
    }

    @Override
    public JChannel write(Object msg) {
        channel.writeAndFlush(msg, channel.voidPromise());
        return this;
    }

    @Override
    public JChannel write(Object msg, final JFutureListener<JChannel> listener) {
        final JChannel jChannel = this;
        channel.writeAndFlush(msg)
                .addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        listener.operationSuccess(jChannel);
                    } else {
                        listener.operationFailure(jChannel, future.cause());
                    }
                });
        return jChannel;
    }

    @Override
    public void addTask(Runnable task) {
        EventLoop eventLoop = channel.eventLoop();

        while (!taskQueue.offer(task)) {
            if (eventLoop.inEventLoop()) {
                runAllTasks.run();
            } else {
                // TODO await?
                eventLoop.execute(runAllTasks);
            }
        }

        if (!taskQueue.isEmpty()) {
            eventLoop.execute(runAllTasks);
        }
    }

    /**
     * 执行 所有任务队列中的任务
     */
    private void runAllTasks() {
        if (taskQueue.isEmpty()) {
            return;
        }

        for (;;) {
            Runnable task = taskQueue.poll();
            if (task == null) {
                return;
            }
            task.run();
        }
    }

    /**
     * 创建 jupiter 使用的输出缓冲区
     * @return
     */
    @Override
    public OutputBuf allocOutputBuf() {
        return new NettyOutputBuf(allocHandle, channel.alloc());
    }

    @Override
    public boolean equals(Object obj) {
        return this == obj || (obj instanceof NettyChannel && channel.equals(((NettyChannel) obj).channel));
    }

    @Override
    public int hashCode() {
        return channel.hashCode();
    }

    @Override
    public String toString() {
        return channel.toString();
    }

    /**
     * jupiter 内部用的输出缓冲区
     */
    static final class NettyOutputBuf implements OutputBuf {

        /**
         * 内存分配器
         */
        private final AdaptiveOutputBufAllocator.Handle allocHandle;
        private final ByteBuf byteBuf;
        private ByteBuffer nioByteBuffer;

        public NettyOutputBuf(AdaptiveOutputBufAllocator.Handle allocHandle, ByteBufAllocator alloc) {
            this.allocHandle = allocHandle;
            byteBuf = allocHandle.allocate(alloc);

            // 确保buf有最小的空间 同时设置起始偏移量
            byteBuf.ensureWritable(JProtocolHeader.HEADER_SIZE)
                    // reserved 16-byte protocol header location
                    .writerIndex(byteBuf.writerIndex() + JProtocolHeader.HEADER_SIZE);
        }

        @Override
        public OutputStream outputStream() {
            return new ByteBufOutputStream(byteBuf); // should not be called more than once
        }

        @Override
        public ByteBuffer nioByteBuffer(int minWritableBytes) {
            if (minWritableBytes < 0) {
                minWritableBytes = byteBuf.writableBytes();
            }

            if (nioByteBuffer == null) {
                nioByteBuffer = newNioByteBuffer(byteBuf, minWritableBytes);
            }

            if (nioByteBuffer.remaining() >= minWritableBytes) {
                return nioByteBuffer;
            }

            int position = nioByteBuffer.position();
            nioByteBuffer = newNioByteBuffer(byteBuf, position + minWritableBytes);
            nioByteBuffer.position(position);
            return nioByteBuffer;
        }

        @Override
        public int size() {
            if (nioByteBuffer == null) {
                return byteBuf.readableBytes();
            }
            return Math.max(byteBuf.readableBytes(), nioByteBuffer.position());
        }

        @Override
        public boolean hasMemoryAddress() {
            return byteBuf.hasMemoryAddress();
        }

        @Override
        public Object backingObject() {
            int actualWroteBytes = byteBuf.writerIndex();
            if (nioByteBuffer != null) {
                actualWroteBytes += nioByteBuffer.position();
            }

            allocHandle.record(actualWroteBytes);

            return byteBuf.writerIndex(actualWroteBytes);
        }

        private static ByteBuffer newNioByteBuffer(ByteBuf byteBuf, int writableBytes) {
            return byteBuf
                    .ensureWritable(writableBytes)
                    .nioBuffer(byteBuf.writerIndex(), byteBuf.writableBytes());
        }
    }
}
