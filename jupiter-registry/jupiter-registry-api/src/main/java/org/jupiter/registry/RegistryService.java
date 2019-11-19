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
import java.util.Map;

import org.jupiter.registry.RegisterMeta.ServiceMeta;

/**
 * Registry service.
 *
 * jupiter
 * org.jupiter.registry
 *
 * 注册中心
 * @author jiachun.fjc
 */
public interface RegistryService extends Registry {

    /**
     * Register service to registry server.
     * 具备将某个元数据信息注册到注册中心上
     */
    void register(RegisterMeta meta);

    /**
     * Unregister service to registry server.
     * 注销某个服务
     */
    void unregister(RegisterMeta meta);

    /**
     * Subscribe a service from registry server.
     * 订阅某个服务
     */
    void subscribe(RegisterMeta.ServiceMeta serviceMeta, NotifyListener listener);

    /**
     * Find a service in the local scope.
     * 查询一组服务信息
     */
    Collection<RegisterMeta> lookup(RegisterMeta.ServiceMeta serviceMeta);

    /**
     * List all consumer's info.
     * 返回所有消费者
     */
    Map<ServiceMeta, Integer> consumers();

    /**
     * List all provider's info.
     * 返回所有提供者
     */
    Map<RegisterMeta, RegisterState> providers();

    /**
     * Returns {@code true} if {@link RegistryService} is shutdown.
     * 判断当前注册中心是否关闭
     */
    boolean isShutdown();

    /**
     * Shutdown.
     * 优雅关闭
     */
    void shutdownGracefully();

    /**
     * 注册中心类型  zk/ 单机环境的default
     */
    enum RegistryType {
        DEFAULT("default"),
        ZOOKEEPER("zookeeper");

        private final String value;

        RegistryType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        public static RegistryType parse(String name) {
            for (RegistryType s : values()) {
                if (s.name().equalsIgnoreCase(name)) {
                    return s;
                }
            }
            return null;
        }
    }

    /**
     * 注册状态
     */
    enum RegisterState {
        /**
         * 准备阶段
         */
        PREPARE,
        /**
         * 已完成注册
         */
        DONE
    }
}
