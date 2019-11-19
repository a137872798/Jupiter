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
import io.netty.channel.ChannelFutureListener;
import io.netty.util.AttributeKey;

import org.jupiter.common.util.JConstants;
import org.jupiter.common.util.MD5Util;
import org.jupiter.common.util.SystemPropertyUtil;
import org.jupiter.monitor.Command;

/**
 * jupiter
 * org.jupiter.monitor.handler
 * <p>
 * 监控模块中 权限校验相关的
 *
 * @author jiachun.fjc
 */
public class AuthHandler implements CommandHandler {

    /**
     * 在通过权限校验后 channel 会携带一个attr这样在后面的handler 上可以通过channel是否携带该标识判断是否通过校验
     */
    private static final AttributeKey<Object> AUTH_KEY = AttributeKey.valueOf("auth");
    /**
     * 设置了该对象代表某channel 已经完成校验
     */
    private static final Object AUTH_OBJECT = new Object();
    /**
     * 默认登录密码
     */
    private static final String DEFAULT_PASSWORD = MD5Util.getMD5("jupiter");

    @Override
    public void handle(Channel channel, Command command, String... args) {
        if (args.length < 2) {
            channel.writeAndFlush("Need password!" + JConstants.NEWLINE);
            return;
        }

        // 从系统变量中获取密码
        String password = SystemPropertyUtil.get("monitor.server.password");
        if (password == null) {
            password = DEFAULT_PASSWORD;
        }

        // 第二个参数与密码相同的情况下 为该channel 绑定attr  在netty中channel是怎么复用的???  channel 本身被创建后是存放在一个pool中的
        // args[0] 是干嘛用的
        if (password.equals(MD5Util.getMD5(args[1]))) {
            channel.attr(AUTH_KEY).setIfAbsent(AUTH_OBJECT);
            // 将验证成功的结果返回给对端
            channel.writeAndFlush("OK" + JConstants.NEWLINE);
        } else {
            channel.writeAndFlush("Permission denied!" + JConstants.NEWLINE)
                    // 校验失败后 关闭channel
                    .addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * 该channel 上是否有校验通过的标识
     * @param channel
     * @return
     */
    public static boolean checkAuth(Channel channel) {
        if (channel.attr(AUTH_KEY).get() == AUTH_OBJECT) {
            return true;
        }
        channel.writeAndFlush("Permission denied" + JConstants.NEWLINE)
                // 失败后会关闭该channel  channel.close() 是由 sun包实现的
                .addListener(ChannelFutureListener.CLOSE);
        return false;
    }
}
