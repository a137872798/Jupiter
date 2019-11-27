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
package org.jupiter.rpc;

import java.util.Collection;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import org.jupiter.common.util.JConstants;
import org.jupiter.common.util.JServiceLoader;
import org.jupiter.common.util.Requires;
import org.jupiter.common.util.Strings;
import org.jupiter.common.util.ThrowUtil;
import org.jupiter.registry.AbstractRegistryService;
import org.jupiter.registry.NotifyListener;
import org.jupiter.registry.OfflineListener;
import org.jupiter.registry.RegisterMeta;
import org.jupiter.registry.RegistryService;
import org.jupiter.rpc.consumer.processor.DefaultConsumerProcessor;
import org.jupiter.rpc.model.metadata.ServiceMetadata;
import org.jupiter.transport.Directory;
import org.jupiter.transport.JConnection;
import org.jupiter.transport.JConnectionManager;
import org.jupiter.transport.JConnector;
import org.jupiter.transport.UnresolvedAddress;
import org.jupiter.transport.UnresolvedSocketAddress;
import org.jupiter.transport.channel.JChannelGroup;

/**
 * Jupiter默认客户端实现.
 *
 * jupiter
 * org.jupiter.rpc
 *
 * 针对consumer
 * @author jiachun.fjc
 */
public class DefaultClient implements JClient {

    // 服务订阅(SPI)
    private final RegistryService registryService;
    private final String appName;

    /**
     * 该对象包含了连接的逻辑
     */
    private JConnector<JConnection> connector;

    public DefaultClient() {
        this(JConstants.UNKNOWN_APP_NAME, RegistryService.RegistryType.DEFAULT);
    }

    public DefaultClient(RegistryService.RegistryType registryType) {
        this(JConstants.UNKNOWN_APP_NAME, registryType);
    }

    public DefaultClient(String appName) {
        this(appName, RegistryService.RegistryType.DEFAULT);
    }

    public DefaultClient(String appName, RegistryService.RegistryType registryType) {
        this.appName = Strings.isBlank(appName) ? JConstants.UNKNOWN_APP_NAME : appName;
        registryType = registryType == null ? RegistryService.RegistryType.DEFAULT : registryType;
        registryService = JServiceLoader.load(RegistryService.class).find(registryType.getValue());
    }

    @Override
    public String appName() {
        return appName;
    }

    @Override
    public JConnector<JConnection> connector() {
        return connector;
    }

    /**
     * 这里的套路跟 DefaultServer 一样，为connector 设置默认的处理器 该处理器包含了接收provider返回结果的逻辑
     * @param connector
     * @return
     */
    @Override
    public JClient withConnector(JConnector<JConnection> connector) {
        if (connector.processor() == null) {
            connector.withProcessor(new DefaultConsumerProcessor());
        }
        this.connector = connector;
        return this;
    }

    @Override
    public RegistryService registryService() {
        return registryService;
    }

    @Override
    public Collection<RegisterMeta> lookup(Directory directory) {
        RegisterMeta.ServiceMeta serviceMeta = toServiceMeta(directory);
        return registryService.lookup(serviceMeta);
    }

    @Override
    public JConnector.ConnectionWatcher watchConnections(Class<?> interfaceClass) {
        return watchConnections(interfaceClass, JConstants.DEFAULT_VERSION);
    }

    /**
     * 返回一个自动管理连接对象
     * @param interfaceClass
     * @param version
     * @return
     */
    @Override
    public JConnector.ConnectionWatcher watchConnections(Class<?> interfaceClass, String version) {
        Requires.requireNotNull(interfaceClass, "interfaceClass");
        // 获取接口上的 注解信息
        ServiceProvider annotation = interfaceClass.getAnnotation(ServiceProvider.class);
        Requires.requireNotNull(annotation, interfaceClass + " is not a ServiceProvider interface");
        String providerName = annotation.name();
        // 如果指定了服务名 使用指定的 否则使用接口名作为服务名
        providerName = Strings.isNotBlank(providerName) ? providerName : interfaceClass.getName();
        // 没有指定的情况下使用默认版本号
        version = Strings.isNotBlank(version) ? version : JConstants.DEFAULT_VERSION;

        // 包装成服务元数据后生成自动管理连接对象
        return watchConnections(new ServiceMetadata(annotation.group(), providerName, version));
    }

    /**
     * 以服务为单位 监听新连接状况
     * @param directory
     * @return
     */
    @Override
    public JConnector.ConnectionWatcher watchConnections(final Directory directory) {
        JConnector.ConnectionWatcher manager = new JConnector.ConnectionWatcher() {

            // 获取 对应consumer的连接管理器
            private final JConnectionManager connectionManager = connector.connectionManager();

            private final ReentrantLock lock = new ReentrantLock();
            private final Condition notifyCondition = lock.newCondition();
            // attempts to elide conditional wake-ups when the lock is uncontended.
            private final AtomicBoolean signalNeeded = new AtomicBoolean(false);

            @Override
            public void start() {
                // 为每个订阅的服务增加监听器
                subscribe(directory, new NotifyListener() {

                    /**
                     * 2种情况触发该方法
                     * 1.首次订阅某个服务 发现当前注册中心已经有一组provider 注册中心会直接将信息返回
                     * 2.有新的服务提供者上线，同时注册中心已经保存了该服务的订阅者，那么借助channel将provider信息返回
                     * @param registerMeta
                     * @param event add/remove 服务
                     */
                    @Override
                    public void notify(RegisterMeta registerMeta, NotifyEvent event) {
                        UnresolvedAddress address = new UnresolvedSocketAddress(registerMeta.getHost(), registerMeta.getPort());
                        // 获取目标地址的所有连接  如果之前还没有provider的情况 那么该group 应该是一个空容器
                        final JChannelGroup group = connector.group(address);
                        // 如果是添加事件
                        if (event == NotifyEvent.CHILD_ADDED) {
                            // 如果目标地址已经存在连接了
                            if (group.isAvailable()) {
                                onSucceed(group, signalNeeded.getAndSet(false));
                            // 如果还没有对应provider的连接 那么现在创建
                            } else {
                                // 如果当前正在连接中
                                if (group.isConnecting()) {
                                    // 设置回调对象
                                    group.onAvailable(() -> onSucceed(group, signalNeeded.getAndSet(false)));
                                } else {
                                    // 代表正在连接中
                                    group.setConnecting(true);
                                    // 开始连接到目标地址，注意这里返回了一组connection 对象
                                    JConnection[] connections = connectTo(address, group, registerMeta, true);
                                    final AtomicInteger countdown = new AtomicInteger(connections.length);
                                    for (JConnection c : connections) {
                                        // 添加一组监听器 当某个future完成时 触发onSucceed 取消waitAvailable 的阻塞状态
                                        c.operationComplete(isSuccess -> {
                                            if (isSuccess) {
                                                onSucceed(group, signalNeeded.getAndSet(false));
                                            }
                                            // 当全部连接完成时 取消 connection 状态 同时 每个连接内部都设置了重连狗 即使某次连接失败也会触发重连
                                            if (countdown.decrementAndGet() <= 0) {
                                                group.setConnecting(false);
                                            }
                                        });
                                    }
                                }
                            }
                            // 为该服务设置权重  因为每个group 是 以address为单位 而每个address提供多个服务是可能的 所以在设置权重时以服务为划分级别
                            group.putWeight(directory, registerMeta.getWeight());
                        // 如果是某服务下线的通知
                        } else if (event == NotifyEvent.CHILD_REMOVED) {
                            // connector 抽象的是针对所有需要服务的连接
                            connector.removeChannelGroup(directory, group);
                            // 同时移除该服务权重相关信息
                            group.removeWeight(directory);
                            // 如果已经没有引用该group 的对象了 那么关闭自动连接
                            if (connector.directoryGroup().getRefCount(group) <= 0) {
                                connectionManager.cancelAutoReconnect(address);
                            }
                        }
                    }

                    /**
                     * 连接到某个服务提供者
                     * @param address 该提供者地址
                     * @param group 当前连接组
                     * @param registerMeta 该服务注册到注册中心时的元数据信息
                     * @param async 是否异步
                     * @return
                     */
                    @SuppressWarnings("SameParameterValue")
                    private JConnection[] connectTo(final UnresolvedAddress address, final JChannelGroup group, RegisterMeta registerMeta, boolean async) {
                        // 获取该服务允许的单消费者连接数 每个消费者还有可能并发访问(当应用服务器在高并发访问场景)
                        int connCount = registerMeta.getConnCount(); // global value from single client
                        connCount = connCount < 1 ? 1 : connCount;

                        JConnection[] connections = new JConnection[connCount];
                        group.setCapacity(connCount);
                        for (int i = 0; i < connCount; i++) {
                            // 建立连接 同时会将连接设置到 channelGroup 中
                            JConnection connection = connector.connect(address, async);
                            connections[i] = connection;
                            // 在manager中同样维护多个连接
                            connectionManager.manage(connection);
                        }

                        // 添加下线监听器 当对应的address  断开连接时触发
                        offlineListening(address, () -> {
                            // 监听到下线时 取消自动连接
                            connectionManager.cancelAutoReconnect(address);
                            if (!group.isAvailable()) {
                                // 从connector移除后 就不会被 代理对象观测到了
                                connector.removeChannelGroup(directory, group);
                            }
                        });

                        return connections;
                    }

                    /**
                     * @param group
                     * @param doSignal
                     */
                    private void onSucceed(JChannelGroup group, boolean doSignal) {
                        // 此时连接组已经可用 就添加到connector中
                        connector.addChannelGroup(directory, group);

                        // 如果是待唤醒状态 唤醒condition
                        if (doSignal) {
                            final ReentrantLock _look = lock;
                            _look.lock();
                            try {
                                notifyCondition.signalAll();
                            } finally {
                                _look.unlock();
                            }
                        }
                    }
                });
            }

            /**
             * 等待直到有可用的连接 一般在创建Client后 先连接到注册中心，之后创建该自动连接管理对象并等待连接上提供者
             * @param timeoutMillis
             * @return
             */
            @Override
            public boolean waitForAvailable(long timeoutMillis) {
                // 如果当前服务已经存在可用地址，直接返回
                if (connector.isDirectoryAvailable(directory)) {
                    return true;
                }

                long remains = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);

                boolean available = false;
                final ReentrantLock _look = lock;
                _look.lock();
                try {
                    // 代表需要被通知， 当从注册中心检测到某个服务上线的情况就可以唤醒
                    signalNeeded.set(true);
                    // avoid "spurious wakeup" occurs  担心被意外唤醒
                    while (!(available = connector.isDirectoryAvailable(directory))) {
                        // 如果超过了等待时间 退出循环
                        if ((remains = notifyCondition.awaitNanos(remains)) <= 0) {
                            break;
                        }
                    }
                } catch (InterruptedException e) {
                    ThrowUtil.throwException(e);
                } finally {
                    _look.unlock();
                }

                return available || connector.isDirectoryAvailable(directory);
            }
        };

        // 初始化完成后启动 自动连接管理对象
        manager.start();

        return manager;
    }

    @Override
    public boolean awaitConnections(Class<?> interfaceClass, long timeoutMillis) {
        return awaitConnections(interfaceClass, JConstants.DEFAULT_VERSION, timeoutMillis);
    }

    @Override
    public boolean awaitConnections(Class<?> interfaceClass, String version, long timeoutMillis) {
        JConnector.ConnectionWatcher watcher = watchConnections(interfaceClass, version);
        return watcher.waitForAvailable(timeoutMillis);
    }

    @Override
    public boolean awaitConnections(Directory directory, long timeoutMillis) {
        JConnector.ConnectionWatcher watcher = watchConnections(directory);
        return watcher.waitForAvailable(timeoutMillis);
    }

    /**
     * 订阅动作要手动触发 在dubbo中是自动触发
     * @param directory
     * @param listener
     */
    @Override
    public void subscribe(Directory directory, NotifyListener listener) {
        registryService.subscribe(toServiceMeta(directory), listener);
    }

    /**
     * 添加下线的监听器
     * @param address
     * @param listener
     */
    @Override
    public void offlineListening(UnresolvedAddress address, OfflineListener listener) {
        if (registryService instanceof AbstractRegistryService) {
            ((AbstractRegistryService) registryService).offlineListening(toAddress(address), listener);
        } else {
            throw new UnsupportedOperationException();
        }
    }

    @Override
    public void shutdownGracefully() {
        registryService.shutdownGracefully();
        connector.shutdownGracefully();
    }

    @Override
    public void connectToRegistryServer(String connectString) {
        registryService.connectToRegistryServer(connectString);
    }

    // setter for spring-support
    public void setConnector(JConnector<JConnection> connector) {
        withConnector(connector);
    }

    /**
     * 将directory 的数据转换成 service元数据
     * @param directory
     * @return
     */
    private static RegisterMeta.ServiceMeta toServiceMeta(Directory directory) {
        RegisterMeta.ServiceMeta serviceMeta = new RegisterMeta.ServiceMeta();
        serviceMeta.setGroup(Requires.requireNotNull(directory.getGroup(), "group"));
        serviceMeta.setServiceProviderName(Requires.requireNotNull(directory.getServiceProviderName(), "serviceProviderName"));
        serviceMeta.setVersion(Requires.requireNotNull(directory.getVersion(), "version"));
        return serviceMeta;
    }

    private static RegisterMeta.Address toAddress(UnresolvedAddress address) {
        return new RegisterMeta.Address(address.getHost(), address.getPort());
    }
}
