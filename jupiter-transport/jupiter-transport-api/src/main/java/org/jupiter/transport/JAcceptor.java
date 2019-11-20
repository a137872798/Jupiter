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
package org.jupiter.transport;

import java.net.SocketAddress;

import org.jupiter.transport.processor.ProviderProcessor;

/**
 * Server acceptor.
 *
 * 注意 JAcceptor 单例即可, 不要创建多个实例.
 *
 * jupiter
 * org.jupiter.transport
 *
 * 作为服务端接受请求的对象
 * @author jiachun.fjc
 */
public interface JAcceptor extends Transporter {

    /**
     * Local address. 返回本地地址
     */
    SocketAddress localAddress();

    /**
     * Returns bound port.    本服务器绑定的端口
     */
    int boundPort();

    /**
     * Acceptor options [parent, child].
     * 配置组  返回该服务器相关的配置
     */
    JConfigGroup configGroup();

    /**
     * Returns the rpc processor. 返回本服务器上的服务提供者处理器
     */
    ProviderProcessor processor();

    /**
     * Binds the rpc processor.
     * 注册处理器
     */
    void withProcessor(ProviderProcessor processor);

    /**
     * Start the server and wait until the server socket is closed.
     */
    void start() throws InterruptedException;

    /**
     * Start the server.
     */
    void start(boolean sync) throws InterruptedException;

    /**
     * Shutdown the server gracefully.
     */
    void shutdownGracefully();
}
