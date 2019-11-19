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
package org.jupiter.monitor.handler;

import java.util.Map;

import io.netty.channel.Channel;

import org.jupiter.common.util.JConstants;
import org.jupiter.monitor.Command;
import org.jupiter.registry.RegisterMeta;
import org.jupiter.registry.RegisterMeta.ServiceMeta;
import org.jupiter.registry.RegistryService;
import org.jupiter.registry.RegistryService.RegisterState;

/**
 * 本地查询发布和订阅的服务信息
 *
 * Jupiter
 * org.jupiter.monitor.handler
 *
 * 查询本地发布的所有消费者和提供者
 * @author jiachun.fjc
 */
public class LsHandler implements CommandHandler {

    /**
     * RegistryService 统一为 注册中心服务 这里划分为针对 服务提供者的注册中心 和针对消费者的注册中心
     * 前提是 注册中心与监控中心部署在同一台机器上
     */
    private volatile RegistryService serverRegisterService;
    private volatile RegistryService clientRegisterService;

    public RegistryService getServerRegisterService() {
        return serverRegisterService;
    }

    public void setServerRegisterService(RegistryService serverRegisterService) {
        this.serverRegisterService = serverRegisterService;
    }

    public RegistryService getClientRegisterService() {
        return clientRegisterService;
    }

    public void setClientRegisterService(RegistryService clientRegisterService) {
        this.clientRegisterService = clientRegisterService;
    }

    /**
     * 处理某个命令
     * @param channel 该对象内部携带了对端地址信息 通过它可以将数据返回给对端
     * @param command 本次客户端发起的命令类型
     * @param args  commandHandler执行任务需要的参数
     */
    @Override
    public void handle(Channel channel, Command command, String... args) {
        if (AuthHandler.checkAuth(channel)) {
            // provider side
            if (serverRegisterService != null) {
                channel.writeAndFlush("Provider side: " + JConstants.NEWLINE);
                channel.writeAndFlush("--------------------------------------------------------------------------------"
                        + JConstants.NEWLINE);
                // 将注册在本地的服务提供者信息全部返回
                Map<RegisterMeta, RegisterState> providers = serverRegisterService.providers();
                for (Map.Entry<RegisterMeta, RegisterState> entry : providers.entrySet()) {
                    channel.writeAndFlush(entry.getKey() + " | " + entry.getValue().toString() + JConstants.NEWLINE);
                }
            }

            // consumer side
            if (clientRegisterService != null) {
                channel.writeAndFlush("Consumer side: " + JConstants.NEWLINE);
                channel.writeAndFlush("--------------------------------------------------------------------------------"
                        + JConstants.NEWLINE);
                Map<ServiceMeta, Integer> consumers = clientRegisterService.consumers();
                for (Map.Entry<ServiceMeta, Integer> entry : consumers.entrySet()) {
                    channel.writeAndFlush(entry.getKey() + " | address_size=" + entry.getValue() + JConstants.NEWLINE);
                }
            }
        }
    }
}
