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

import java.util.concurrent.CompletableFuture;

import org.jupiter.common.util.SystemPropertyUtil;
import org.jupiter.example.AsyncUserService;
import org.jupiter.example.User;
import org.jupiter.example.UserService;
import org.jupiter.rpc.DefaultClient;
import org.jupiter.rpc.InvokeType;
import org.jupiter.rpc.JClient;
import org.jupiter.rpc.consumer.ProxyFactory;
import org.jupiter.serialization.SerializerType;
import org.jupiter.transport.JConnector;
import org.jupiter.transport.exception.ConnectFailedException;
import org.jupiter.transport.netty.JNettyTcpConnector;

/**
 * jupiter
 * org.jupiter.example.round
 * 同步请求客户端
 * @author jiachun.fjc
 */
public class SyncJupiterClient {

    static {
        SystemPropertyUtil.setProperty("jupiter.io.codec.low_copy", "true");
        SystemPropertyUtil.setProperty("io.netty.allocator.type", "pooled");
//        SystemPropertyUtil.setProperty("io.netty.noPreferDirect", "true");
    }

    public static void main(String[] args) {
        // connector 对应消费者的处理 DefaultClient 内部包含了 connector 以及 与注册中心的通信逻辑
        JClient client = new DefaultClient().withConnector(new JNettyTcpConnector());

        // 连接RegistryServer
        // 同样先将consumer 连接到注册中心
        client.connectToRegistryServer("127.0.0.1:20001");
        // 自动管理可用连接  这里指定了服务 和 版本信息
        JConnector.ConnectionWatcher watcher = client.watchConnections(UserService.class, "1.0.0.daily");
        // 等待连接可用 内部会创建对应某个service 的channelGroup 只有当注册中心触发监听器的时候才会通知到consumer对provider进行连接
        if (!watcher.waitForAvailable(3000)) {
            throw new ConnectFailedException();
        }

        // 如果上面没有抛出异常也就代表已经连接上provider了 同时consumer会保持对provider的心跳
        // 开始生成动态代理对象 隐藏RPC调用
        UserService userService = ProxyFactory.factory(UserService.class)
                .version("1.0.0.daily")
                //这里还要设置客户端对象
                .client(client)
                .serializerType(SerializerType.PROTO_STUFF)
                .failoverRetries(5)
                .newProxyInstance();

        AsyncUserService asyncUserService = ProxyFactory.factory(AsyncUserService.class)
                .version("1.0.0.daily")
                .client(client)
                .invokeType(InvokeType.SYNC)
                .newProxyInstance();

        try {
            for (int i = 0; i < 5; i++) {
                // 通过动态代理对象开始调用请求
                User user = userService.createUser();
                System.out.println(user);
            }

            for (int i = 0; i < 5; i++) {
                CompletableFuture<User> user = asyncUserService.createUser();
                System.out.println(user.get());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
