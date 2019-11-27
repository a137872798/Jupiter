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
package org.jupiter.registry;

import java.util.Collection;
import java.util.concurrent.ConcurrentMap;

import org.jupiter.common.util.Maps;
import org.jupiter.common.util.Requires;
import org.jupiter.common.util.SpiMetadata;
import org.jupiter.common.util.Strings;
import org.jupiter.common.util.internal.logging.InternalLogger;
import org.jupiter.common.util.internal.logging.InternalLoggerFactory;
import org.jupiter.transport.JConnection;
import org.jupiter.transport.UnresolvedAddress;
import org.jupiter.transport.UnresolvedSocketAddress;

/**
 * Default registry service.
 *
 * jupiter
 * org.jupiter.registry.jupiter
 *
 * 该对象本身相当于 server/client 与 注册中心的桥梁 通过该对象可以将需要的数据发送到注册中心
 * @author jiachun.fjc
 */
@SpiMetadata(name = "default")
public class DefaultRegistryService extends AbstractRegistryService {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultRegistryService.class);

    /**
     * 维护所有注册过信息的客户端
     */
    private final ConcurrentMap<UnresolvedAddress, DefaultRegistry> clients = Maps.newConcurrentMap();

    /**
     * 该对象作为 consumer/provider 与注册中心交互的桥梁 根据不同需要发起订阅/发布 等动作
     * @param serviceMeta
     */
    @Override
    protected void doSubscribe(RegisterMeta.ServiceMeta serviceMeta) {
        // 将信息注册到所有注册中心  代表基于 AP 如果是基于 CP的注册中心只需要注册到leader就可以
        Collection<DefaultRegistry> allClients = clients.values();
        Requires.requireTrue(!allClients.isEmpty(), "init needed");

        logger.info("Subscribe: {}.", serviceMeta);

        for (DefaultRegistry c : allClients) {
            c.doSubscribe(serviceMeta);
        }
    }

    @Override
    protected void doRegister(RegisterMeta meta) {
        // 本次数据要注册到全部的注册中心 这是一种基于 AP 的注册中心 (简化版 并不能保证分布式一致性)
        Collection<DefaultRegistry> allClients = clients.values();
        Requires.requireTrue(!allClients.isEmpty(), "init needed");

        logger.info("Register: {}.", meta);

        for (DefaultRegistry c : allClients) {
            // 通过与注册中心交互的客户端发送元数据进行注册
            c.doRegister(meta);
        }
        // 将状态更改成注册完成
        getRegisterMetaMap().put(meta, RegisterState.DONE);
    }

    @Override
    protected void doUnregister(RegisterMeta meta) {
        Collection<DefaultRegistry> allClients = clients.values();
        Requires.requireTrue(!allClients.isEmpty(), "init needed");

        logger.info("Unregister: {}.", meta);

        for (DefaultRegistry c : allClients) {
            c.doUnregister(meta);
        }
    }

    @Override
    protected void doCheckRegisterNodeStatus() {
        // the default registry service does nothing
    }

    @Override
    public void connectToRegistryServer(String connectString) {
        Requires.requireNotNull(connectString, "connectString");

        // 可能是多个注册中心
        String[] array = Strings.split(connectString, ',');
        for (String s : array) {
            String[] addressStr = Strings.split(s, ':');
            String host = addressStr[0];
            int port = Integer.parseInt(addressStr[1]);
            UnresolvedAddress address = new UnresolvedSocketAddress(host, port);
            DefaultRegistry client = clients.get(address);
            if (client == null) {
                // 代表连接到对应注册中心的客户端还没有生成
                DefaultRegistry newClient = new DefaultRegistry(this);
                // 维护客户端 避免重复创建
                client = clients.putIfAbsent(address, newClient);
                if (client == null) {
                    client = newClient;
                    // 开始连接到指定地址
                    JConnection connection = client.connect(address);
                    // 在client 中也维护一份 地址与conn 的映射关系
                    client.connectionManager().manage(connection);
                } else {
                    // 如果 client 已经创建 那么就销毁
                    newClient.shutdownGracefully();
                }
            }
        }
    }

    @Override
    public void destroy() {
        for (DefaultRegistry c : clients.values()) {
            c.shutdownGracefully();
        }
    }
}