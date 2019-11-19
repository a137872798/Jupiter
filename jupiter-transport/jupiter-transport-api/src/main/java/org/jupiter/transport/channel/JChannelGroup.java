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
package org.jupiter.transport.channel;

import java.util.List;

import org.jupiter.transport.Directory;
import org.jupiter.transport.UnresolvedAddress;

/**
 * Based on the same address of the channel group.
 *
 * 要注意的是它管理的是相同地址的 {@link JChannel}.
 *
 * jupiter
 * org.jupiter.transport.channel
 * 该group内部的所有channel.remoteAddress() 是一样的那么为什么要建立这么多channel呢 不是增加开销吗???
 * @author jiachun.fjc
 */
public interface JChannelGroup {

    /**
     * Returns the remote address of this group.
     * 返回远端地址 注意返回类型是 UnresolvedAddress
     */
    UnresolvedAddress remoteAddress();

    /**
     * Returns the next {@link JChannel} in the group.
     * 获取 group 中下一个channel 看来针对某个地址的请求还是通过负载给多个channel来执行的
     */
    JChannel next();

    /**
     * Returns all {@link JChannel}s in the group.
     * 返回本组下所有的channel
     */
    List<? extends JChannel> channels();

    /**
     * Returns true if this group contains no {@link JChannel}.
     * 该group 下是否无channel
     */
    boolean isEmpty();

    /**
     * Adds the specified {@link JChannel} to this group.
     * 将某个channel 添加到组中
     */
    boolean add(JChannel channel);

    /**
     * Removes the specified {@link JChannel} from this group.
     * 从group 中移除某个channel
     */
    boolean remove(JChannel channel);

    /**
     * Returns the number of {@link JChannel}s in this group (its cardinality).
     * 返回该组中channel 数量
     */
    int size();

    /**
     * Sets the capacity of this group.
     */
    void setCapacity(int capacity);

    /**
     * The capacity of this group.
     */
    int getCapacity();

    /**
     * If connecting return true, otherwise return false.
     */
    boolean isConnecting();

    /**
     * Sets connecting state
     */
    void setConnecting(boolean connecting);

    /**
     * If available return true, otherwise return false.
     */
    boolean isAvailable();

    /**
     * Wait until the {@link JChannel}s are available or timeout,
     * if available return true, otherwise return false.
     */
    boolean waitForAvailable(long timeoutMillis);

    /**
     * Be called when the {@link JChannel}s are available.
     */
    void onAvailable(Runnable listener);

    /**
     * Gets weight of service.
     * 获得某一服务的权重  Directory 不仅携带了服务名 还有版本号 和 组别
     */
    int getWeight(Directory directory);

    /**
     * Puts the weight of service.
     */
    void putWeight(Directory directory, int weight);

    /**
     * Removes the weight of service.
     */
    void removeWeight(Directory directory);

    /**
     * Warm-up time.
     * 返回预热时间
     */
    int getWarmUp();

    /**
     * Sets warm-up time.
     */
    void setWarmUp(int warmUp);

    /**
     * Returns {@code true} if warm up to complete.
     * 是否完成预热
     */
    boolean isWarmUpComplete();

    /**
     * Time of birth.
     * 该group的创建时间
     */
    long timestamp();

    /**
     * Deadline millis.
     */
    long deadlineMillis();
}
