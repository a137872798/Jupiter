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
package org.jupiter.transport.netty.handler.connector;

import java.net.SocketAddress;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.util.Timeout;
import io.netty.util.Timer;
import io.netty.util.TimerTask;

import org.jupiter.common.util.internal.logging.InternalLogger;
import org.jupiter.common.util.internal.logging.InternalLoggerFactory;
import org.jupiter.transport.channel.JChannelGroup;
import org.jupiter.transport.netty.channel.NettyChannel;
import org.jupiter.transport.netty.handler.ChannelHandlerHolder;

/**
 * Connections watchdog.
 *
 * jupiter
 * org.jupiter.transport.netty.handler.connector
 *
 * 重连狗 具备定时重连功能
 * @author jiachun.fjc
 */
@ChannelHandler.Sharable
public abstract class ConnectionWatchdog extends ChannelInboundHandlerAdapter implements TimerTask, ChannelHandlerHolder {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(ConnectionWatchdog.class);

    /**
     * 当前重连狗的状态
     */
    private static final int ST_STARTED = 1;
    private static final int ST_STOPPED = 2;

    /**
     * 包含 bootstrap的引用才能进行连接
     */
    private final Bootstrap bootstrap;
    private final Timer timer;
    /**
     * 对端地址 有了它才能发起连接
     */
    private final SocketAddress remoteAddress;
    /**
     * channel 组
     */
    private final JChannelGroup group;

    /**
     * 默认处于开启状态
     */
    private volatile int state = ST_STARTED;
    /**
     * 重连次数
     */
    private int attempts;

    public ConnectionWatchdog(Bootstrap bootstrap, Timer timer, SocketAddress remoteAddress, JChannelGroup group) {
        this.bootstrap = bootstrap;
        this.timer = timer;
        this.remoteAddress = remoteAddress;
        this.group = group;
    }

    public boolean isStarted() {
        return state == ST_STARTED;
    }

    public void start() {
        state = ST_STARTED;
    }

    public void stop() {
        state = ST_STOPPED;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        Channel ch = ctx.channel();

        // 当连接成功时 将连接存入到连接组中
        if (group != null) {
            group.add(NettyChannel.attachChannel(ch));
        }

        attempts = 0;

        logger.info("Connects with {}.", ch);

        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        boolean doReconnect = isReconnectNeeded();
        if (doReconnect) {
            if (attempts < 12) {
                attempts++;
            }
            long timeout = 2 << attempts;
            timer.newTimeout(this, timeout, TimeUnit.MILLISECONDS);
        }

        logger.warn("Disconnects with {}, address: {}, reconnect: {}.", ctx.channel(), remoteAddress, doReconnect);

        ctx.fireChannelInactive();
    }

    /**
     * 重连逻辑
     * @param timeout
     * @throws Exception
     */
    @Override
    public void run(Timeout timeout) throws Exception {
        if (!isReconnectNeeded()) {
            logger.warn("Cancel reconnecting with {}.", remoteAddress);
            return;
        }

        ChannelFuture future;
        synchronized (bootstrap) {
            bootstrap.handler(new ChannelInitializer<Channel>() {

                @Override
                protected void initChannel(Channel ch) throws Exception {
                    ch.pipeline().addLast(handlers());
                }
            });
            future = bootstrap.connect(remoteAddress);
        }

        future.addListener((ChannelFutureListener) f -> {
            boolean succeed = f.isSuccess();

            logger.warn("Reconnects with {}, {}.", remoteAddress, succeed ? "succeed" : "failed");

            if (!succeed) {
                // 失败时传播失活 同时又再次设置定时重连任务
                f.channel().pipeline().fireChannelInactive();
            }
        });
    }

    private boolean isReconnectNeeded() {
        return isStarted() && (group == null || (group.size() < group.getCapacity()));
    }
}
