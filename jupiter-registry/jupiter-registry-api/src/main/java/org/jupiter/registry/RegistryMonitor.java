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

import java.util.List;

/**
 * Registry monitor.
 *
 * jupiter
 * org.jupiter.registry
 *
 * 注册中心开放出来的用于监控用 api
 * @author jiachun.fjc
 */
public interface RegistryMonitor {

    /**
     * Returns the address list of publisher.
     * 返回当前所有的服务提供者
     */
    List<String> listPublisherHosts();

    /**
     * Returns the address list of subscriber.
     * 返回当前所有的服务订阅者
     */
    List<String> listSubscriberAddresses();

    /**
     * Returns to the service of all the specified service provider's address.
     * 获取具备提供某个服务的所有服务地址
     */
    List<String> listAddressesByService(String group, String serviceProviderName, String version);

    /**
     * Finds the address(host, port) of the corresponding node and returns all
     * the service names it provides.
     * 返回指定地址能提供的所有服务
     */
    List<String> listServicesByAddress(String host, int port);
}
