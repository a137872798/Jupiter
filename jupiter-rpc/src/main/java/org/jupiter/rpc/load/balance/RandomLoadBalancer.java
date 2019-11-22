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
package org.jupiter.rpc.load.balance;

import java.util.concurrent.ThreadLocalRandom;

import org.jupiter.transport.Directory;
import org.jupiter.transport.channel.CopyOnWriteGroupList;
import org.jupiter.transport.channel.JChannelGroup;

/**
 * 加权随机负载均衡.
 *
 * <pre>
 * *****************************************************************************
 *
 *            random value
 * ─────────────────────────────────▶
 *                                  │
 *                                  ▼
 * ┌─────────────────┬─────────┬──────────────────────┬─────┬─────────────────┐
 * │element_0        │element_1│element_2             │...  │element_n        │
 * └─────────────────┴─────────┴──────────────────────┴─────┴─────────────────┘
 *
 * *****************************************************************************
 * </pre>
 *
 * jupiter
 * org.jupiter.rpc.load.balance
 *
 * 权重挂钩的随机节点均衡负载
 * @author jiachun.fjc
 */
public class RandomLoadBalancer implements LoadBalancer {

    private static final RandomLoadBalancer instance = new RandomLoadBalancer();

    public static RandomLoadBalancer instance() {
        return instance;
    }

    @Override
    public JChannelGroup select(CopyOnWriteGroupList groups, Directory directory) {
        // 获取内部channel 组 （两层维度的组）
        JChannelGroup[] elements = groups.getSnapshot();
        int length = elements.length;

        if (length == 0) {
            return null;
        }

        if (length == 1) {
            return elements[0];
        }

        // 返回维护权重的数组
        WeightArray weightArray = (WeightArray) groups.getWeightArray(elements, directory.directoryString());
        if (weightArray == null || weightArray.length() != length) {
            // 代表权重值无效 重新计算
            weightArray = WeightSupport.computeWeights(groups, elements, directory);
        }

        // 生成随机值
        ThreadLocalRandom random = ThreadLocalRandom.current();

        // computeWeights 如果所有节点的权重一致 内部就是null 对应 isAllSameWeight is true
        if (weightArray.isAllSameWeight()) {
            // 随机返回一个 channelGroup
            return elements[random.nextInt(length)];
        }

        int nextIndex = getNextServerIndex(weightArray, length, random);

        return elements[nextIndex];
    }

    private static int getNextServerIndex(WeightArray weightArray, int length, ThreadLocalRandom random) {
        // WeightArray 数组中每个元素 都是之前所有元素的总和
        int sumWeight = weightArray.get(length - 1);
        int val = random.nextInt(sumWeight + 1);
        // 按照随机值去找对应的 数组元素  采用 每个元素求和的原因是为了 使用二分查找 这样强行构造了一个顺序数组  否则数组是无序的就无法使用二分查找了
        return WeightSupport.binarySearchIndex(weightArray, length, val);
    }
}
