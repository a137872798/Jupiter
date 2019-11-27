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
package org.jupiter.example.round;

import org.jupiter.common.util.SystemPropertyUtil;
import org.jupiter.example.AsyncUserServiceImpl;
import org.jupiter.example.UserServiceImpl;
import org.jupiter.rpc.DefaultServer;
import org.jupiter.rpc.JServer;
import org.jupiter.rpc.model.metadata.ServiceWrapper;
import org.jupiter.transport.netty.JNettyTcpAcceptor;

/**
 * jupiter
 * org.jupiter.example.round
 *
 * @author jiachun.fjc
 */
public class JupiterServer {

    static {
        SystemPropertyUtil.setProperty("jupiter.rpc.suggest.connection.count", "1");
        SystemPropertyUtil.setProperty("jupiter.io.codec.low_copy", "true");
        SystemPropertyUtil.setProperty("io.netty.allocator.type", "pooled");
//        SystemPropertyUtil.setProperty("io.netty.noPreferDirect", "true");
    }

    public static void main(String[] args) {
        JServer server = new DefaultServer().withAcceptor(new JNettyTcpAcceptor(18090));
        try {
            // 将提供者信息包装成后 保存到 container中 这样当接收consumer的请求时 就可以通过service信息匹配对应的 serviceWrapper
            ServiceWrapper provider1 = server.serviceRegistry()
                    .provider(new UserServiceImpl())
                    .register();

            ServiceWrapper provider2 = server.serviceRegistry()
                    .provider(new AsyncUserServiceImpl())
                    .register();

            // 连接到注册中心 此时还没有发布服务到注册中心上
            server.connectToRegistryServer("127.0.0.1:20001");
            // 开始发布服务  服务下线是怎么通知到注册中心的???
            server.publish(provider1, provider2);
            // 启动服务提供者
            server.start();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
