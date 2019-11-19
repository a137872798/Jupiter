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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.StampedLock;

import org.jupiter.common.concurrent.NamedThreadFactory;
import org.jupiter.common.concurrent.collection.ConcurrentSet;
import org.jupiter.common.util.Lists;
import org.jupiter.common.util.Maps;
import org.jupiter.common.util.StackTraceUtil;
import org.jupiter.common.util.internal.logging.InternalLogger;
import org.jupiter.common.util.internal.logging.InternalLoggerFactory;
import org.jupiter.registry.RegisterMeta.ServiceMeta;

/**
 * jupiter
 * org.jupiter.registry
 *
 * 注册中心骨架类   该对象内部组合的第三方注册中心框架自带服务器功能 所以该对象不需要继承 NettyAccepter
 * @author jiachun.fjc
 */
public abstract class AbstractRegistryService implements RegistryService {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractRegistryService.class);

    /**
     * 注册中心维护着一组注册元数据
     */
    private final LinkedBlockingQueue<RegisterMeta> queue = new LinkedBlockingQueue<>();
    /**
     * 注册用线程池
     */
    private final ExecutorService registerExecutor =
            Executors.newSingleThreadExecutor(new NamedThreadFactory("register.executor", true));
    /**
     * 注册用定时器
     */
    private final ScheduledExecutorService registerScheduledExecutor =
            Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("register.schedule.executor", true));
    /**
     * 本地用注册
     */
    private final ExecutorService localRegisterWatchExecutor =
            Executors.newSingleThreadExecutor(new NamedThreadFactory("local.register.watch.executor", true));

    /**
     * 是否已经被关闭
     */
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    private final ConcurrentMap<RegisterMeta.ServiceMeta, RegisterValue> registries =
            Maps.newConcurrentMap();

    /**
     * 针对实时性要求不强的监听器 使用 CopyOnWriter 对象
     */
    private final ConcurrentMap<RegisterMeta.ServiceMeta, CopyOnWriteArrayList<NotifyListener>> subscribeListeners =
            Maps.newConcurrentMap();
    private final ConcurrentMap<RegisterMeta.Address, CopyOnWriteArrayList<OfflineListener>> offlineListeners =
            Maps.newConcurrentMap();

    // Consumer已订阅的信息
    private final ConcurrentSet<RegisterMeta.ServiceMeta> subscribeSet = new ConcurrentSet<>();
    // Provider已发布的注册信息
    private final ConcurrentMap<RegisterMeta, RegisterState> registerMetaMap = Maps.newConcurrentMap();

    public AbstractRegistryService() {
        registerExecutor.execute(() -> {
            while (!shutdown.get()) {
                RegisterMeta meta = null;
                try {
                    // 不断从阻塞队列中拉取注册元数据 并填充到registerMetaMap中， 此时的state标记为 prepare 代表注册还未完成
                    meta = queue.take();
                    registerMetaMap.put(meta, RegisterState.PREPARE);
                    // 针对元数据做真正的注册行为
                    doRegister(meta);
                } catch (InterruptedException e) {
                    logger.warn("[register.executor] interrupted.");
                } catch (Throwable t) {
                    if (meta != null) {
                        logger.error("Register [{}] fail: {}, will try again...", meta.getServiceMeta(), StackTraceUtil.stackTrace(t));

                        // 进行重试
                        final RegisterMeta finalMeta = meta;
                        registerScheduledExecutor.schedule(() -> {
                            queue.add(finalMeta);
                        }, 1, TimeUnit.SECONDS);
                    }
                }
            }
        });

        /**
         * 该对象用于检查map 中是否有未注册成功的元数据对象
         */
        localRegisterWatchExecutor.execute(() -> {
            while (!shutdown.get()) {
                try {
                    Thread.sleep(3000);
                    doCheckRegisterNodeStatus();
                } catch (InterruptedException e) {
                    logger.warn("[local.register.watch.executor] interrupted.");
                } catch (Throwable t) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Check register node status fail: {}, will try again...", StackTraceUtil.stackTrace(t));
                    }
                }
            }
        });
    }

    /**
     * 注册不是立即完成的 而是先加入到阻塞队列 之后通过线程池做异步注册
     * @param meta
     */
    @Override
    public void register(RegisterMeta meta) {
        queue.add(meta);
    }

    @Override
    public void unregister(RegisterMeta meta) {
        if (!queue.remove(meta)) {
            registerMetaMap.remove(meta);
            // 进行注销逻辑
            doUnregister(meta);
        }
    }

    /**
     * 增加某一订阅者
     * @param serviceMeta
     * @param listener  传入一个监听器对象 当某个服务上线/下线时会通知到该对象
     */
    @Override
    public void subscribe(RegisterMeta.ServiceMeta serviceMeta, NotifyListener listener) {
        // 获取针对某一服务的所有监听器 并插入该监听器
        CopyOnWriteArrayList<NotifyListener> listeners = subscribeListeners.get(serviceMeta);
        if (listeners == null) {
            CopyOnWriteArrayList<NotifyListener> newListeners = new CopyOnWriteArrayList<>();
            listeners = subscribeListeners.putIfAbsent(serviceMeta, newListeners);
            if (listeners == null) {
                listeners = newListeners;
            }
        }
        listeners.add(listener);

        // 将订阅者设置到容器中
        subscribeSet.add(serviceMeta);
        // 委托子类实现
        doSubscribe(serviceMeta);
    }

    /**
     * 查找某一服务的 所有注册信息
     * @param serviceMeta
     * @return
     */
    @Override
    public Collection<RegisterMeta> lookup(RegisterMeta.ServiceMeta serviceMeta) {
        // 这里维护了 某一服务各个版本/组的信息吗
        RegisterValue value = registries.get(serviceMeta);

        if (value == null) {
            return Collections.emptyList();
        }

        // do not try optimistic read
        final StampedLock stampedLock = value.lock;
        final long stamp = stampedLock.readLock();
        try {
            // 返回一个副本
            return Lists.newArrayList(value.metaSet);
        } finally {
            stampedLock.unlockRead(stamp);
        }
    }

    /**
     * 返回所有的消费者   map.value= 某一服务注册次数
     * @return
     */
    @Override
    public Map<ServiceMeta, Integer> consumers() {
        Map<ServiceMeta, Integer> result = Maps.newHashMap();
        for (Map.Entry<RegisterMeta.ServiceMeta, RegisterValue> entry : registries.entrySet()) {
            RegisterValue value = entry.getValue();
            final StampedLock stampedLock = value.lock;
            long stamp = stampedLock.tryOptimisticRead();
            // 获取某一服务所有注册信息
            int optimisticVal = value.metaSet.size();
            if (stampedLock.validate(stamp)) {
                result.put(entry.getKey(), optimisticVal);
                continue;
            }
            stamp = stampedLock.readLock();
            try {
                result.put(entry.getKey(), value.metaSet.size());
            } finally {
                stampedLock.unlockRead(stamp);
            }
        }
        return result;
    }

    @Override
    public Map<RegisterMeta, RegisterState> providers() {
        return new HashMap<>(registerMetaMap);
    }

    @Override
    public boolean isShutdown() {
        return shutdown.get();
    }

    @Override
    public void shutdownGracefully() {
        if (!shutdown.getAndSet(true)) {
            try {
                registerExecutor.shutdownNow();
                registerScheduledExecutor.shutdownNow();
                localRegisterWatchExecutor.shutdownNow();
            } catch (Exception e) {
                logger.error("Failed to shutdown: {}.", StackTraceUtil.stackTrace(e));
            } finally {
                destroy();
            }
        }
    }

    /**
     * 销毁方法 就是终止第三方注册中心
     */
    public abstract void destroy();

    /**
     * 添加某一地址 有关下线的监听器
     * @param address
     * @param listener
     */
    public void offlineListening(RegisterMeta.Address address, OfflineListener listener) {
        CopyOnWriteArrayList<OfflineListener> listeners = offlineListeners.get(address);
        if (listeners == null) {
            CopyOnWriteArrayList<OfflineListener> newListeners = new CopyOnWriteArrayList<>();
            listeners = offlineListeners.putIfAbsent(address, newListeners);
            if (listeners == null) {
                listeners = newListeners;
            }
        }
        listeners.add(listener);
    }

    /**
     * 某个地址下线了 通知相关的监听器
     * @param address
     */
    public void offline(RegisterMeta.Address address) {
        // remove & notify
        CopyOnWriteArrayList<OfflineListener> listeners = offlineListeners.remove(address);
        if (listeners != null) {
            for (OfflineListener l : listeners) {
                l.offline();
            }
        }
    }

    // 通知新增或删除服务
    protected void notify(
            RegisterMeta.ServiceMeta serviceMeta, NotifyListener.NotifyEvent event, long version, RegisterMeta... array) {

        if (array == null || array.length == 0) {
            return;
        }

        // 获取某一服务所有的变动信息
        RegisterValue value = registries.get(serviceMeta);
        if (value == null) {
            RegisterValue newValue = new RegisterValue();
            value = registries.putIfAbsent(serviceMeta, newValue);
            if (value == null) {
                value = newValue;
            }
        }

        boolean notifyNeeded = false;

        // segment-lock
        final StampedLock stampedLock = value.lock;
        final long stamp = stampedLock.writeLock();
        try {
            // 获取当前版本号
            long lastVersion = value.version;
            if (version > lastVersion
                    // 代表版本号越界 不过都代表 本次传入的version 更大 如果version 变小 应该就忽略本次请求
                    || (version < 0 && lastVersion > 0 /* version overflow */)) {
                // 根据事件类型 移除对应的 服务提供信息
                if (event == NotifyListener.NotifyEvent.CHILD_REMOVED) {
                    for (RegisterMeta m : array) {
                        value.metaSet.remove(m);
                    }
                } else if (event == NotifyListener.NotifyEvent.CHILD_ADDED) {
                    Collections.addAll(value.metaSet, array);
                }
                value.version = version;
                notifyNeeded = true;
            }
        } finally {
            stampedLock.unlockWrite(stamp);
        }

        // 代表本次注册是有效的 通知所有订阅者
        if (notifyNeeded) {
            CopyOnWriteArrayList<NotifyListener> listeners = subscribeListeners.get(serviceMeta);
            if (listeners != null) {
                for (NotifyListener l : listeners) {
                    for (RegisterMeta m : array) {
                        l.notify(m, event);
                    }
                }
            }
        }
    }

    protected abstract void doSubscribe(RegisterMeta.ServiceMeta serviceMeta);

    protected abstract void doRegister(RegisterMeta meta);

    protected abstract void doUnregister(RegisterMeta meta);

    protected abstract void doCheckRegisterNodeStatus();

    protected ConcurrentSet<ServiceMeta> getSubscribeSet() {
        return subscribeSet;
    }

    protected ConcurrentMap<RegisterMeta, RegisterState> getRegisterMetaMap() {
        return registerMetaMap;
    }

    protected static class RegisterValue {
        private long version = Long.MIN_VALUE;
        private final Set<RegisterMeta> metaSet = new HashSet<>();
        private final StampedLock lock = new StampedLock(); // segment-lock
    }
}
