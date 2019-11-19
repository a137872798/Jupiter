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

import io.netty.channel.Channel;

import org.jupiter.common.util.JConstants;
import org.jupiter.monitor.Command;
import org.jupiter.registry.RegistryMonitor;

/**
 * jupiter
 * org.jupiter.monitor.handler
 *
 * 该对象内部可以获取到注册中心信息
 * @author jiachun.fjc
 */
public class RegistryHandler implements CommandHandler {

    /**
     * 注册中心对外开放的监控api  如果监控中心是通过某个服务器单独部署的 那么注册中心监控是由谁来设置的???
     */
    private volatile RegistryMonitor registryMonitor;

    public RegistryMonitor getRegistryMonitor() {
        return registryMonitor;
    }

    public void setRegistryMonitor(RegistryMonitor registryMonitor) {
        this.registryMonitor = registryMonitor;
    }

    /**
     * 处理某个命令
     * @param channel 该对象内部携带了对端地址信息 通过它可以将数据返回给对端
     * @param command 本次客户端发起的命令类型
     * @param args  commandHandler执行任务需要的参数
     */
    @SuppressWarnings("unchecked")
    @Override
    public void handle(Channel channel, Command command, String... args) {
        // 首先判断该channel 是否已经通过了验证
        if (AuthHandler.checkAuth(channel)) {
            if (args.length < 3) {
                channel.writeAndFlush("Need more args!" + JConstants.NEWLINE);
                return;
            }

            // 如果命令中携带childCommand 解析处理并处理
            Command.ChildCommand child = command.parseChild(args[1]);
            if (child != null) {
                CommandHandler childHandler = child.handler();
                if (childHandler == null) {
                    return;
                }
                if (childHandler instanceof ChildCommandHandler) {
                    if (((ChildCommandHandler) childHandler).getParent() == null) {
                        ((ChildCommandHandler) childHandler).setParent(this);
                    }
                }
                childHandler.handle(channel, command, args);
            } else {
                channel.writeAndFlush("Wrong args denied!" + JConstants.NEWLINE);
            }
        }
    }
}
