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

import org.jupiter.common.util.SystemClock;
import org.jupiter.transport.Directory;
import org.jupiter.transport.channel.CopyOnWriteGroupList;
import org.jupiter.transport.channel.JChannelGroup;

import static java.lang.Math.min;

/**
 * jupiter
 * org.jupiter.rpc.load.balance
 *
 * 权重相关工具类
 * @author jiachun.fjc
 */
final class WeightSupport {

    static int binarySearchIndex(WeightArray weightArray, int length, int value) {
        int low = 0;
        int high = length - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            long midVal = weightArray.get(mid);

            if (midVal < value) {
                low = mid + 1;
            } else if (midVal > value) {
                high = mid - 1;
            } else {
                return mid;
            }
        }

        return low;
    }

    /**
     * 计算权重
     * @param groups
     * @param elements
     * @param directory
     * @return
     */
    static WeightArray computeWeights(CopyOnWriteGroupList groups, JChannelGroup[] elements, Directory directory) {
        int length = elements.length;
        int[] weights = new int[length];

        // 判断是否预热完成  就是启动时到现在时间差是否超过 预热时间
        boolean allWarmUpComplete = elements[0].isWarmUpComplete();
        boolean allSameWeight = true;
        weights[0] = getWeight(elements[0], directory);
        for (int i = 1; i < length; i++) {
            allWarmUpComplete &= elements[i].isWarmUpComplete();
            weights[i] = getWeight(elements[i], directory);
            allSameWeight &= (weights[i - 1] == weights[i]);
        }

        // 如果所有节点 的权重 都一样 那么将权重置空 代表每个节点优先级相同
        if (allWarmUpComplete && allSameWeight) {
            weights = null;
        }

        if (weights != null) {
            for (int i = 1; i < length; i++) {
                // [curVal += preVal] for binary search
                // 数组中每个元素的值都是之前所有元素累加的和
                weights[i] += weights[i - 1];
            }
        }

        WeightArray weightArray = new WeightArray(weights, length);

        // 只有所有节点都暖机完成时才设置权重
        if (allWarmUpComplete) {
            groups.setWeightArray(elements, directory.directoryString(), weightArray);
        }

        return weightArray;
    }

    // 计算权重, 包含预热逻辑
    static int getWeight(JChannelGroup group, Directory directory) {
        int weight = group.getWeight(directory);
        int warmUp = group.getWarmUp();
        int upTime = (int) (SystemClock.millisClock().now() - group.timestamp());

        if (upTime > 0 && upTime < warmUp) {
            // 对端服务预热中, 计算预热权重    upTime / warmUp 代表预热完成百分比
            weight = (int) (((float) upTime / warmUp) * weight);
        }

        return weight > 0 ? weight : 0;
    }

    /**
     * 计算最大公约数
     * @param array
     * @param n
     * @return
     */
    static int n_gcd(int[] array, int n) {
        if (n == 1) {
            return array[0];
        }
        return gcd(array[n - 1], n_gcd(array, n - 1));
    }

    /**
     * Returns the greatest common divisor of {@code a, b}. Returns {@code 0} if
     * {@code a == 0 && b == 0}.
     */
    static int gcd(int a, int b) {
        if (a == b) {
            return a;
        } else if (a == 0) {
            return b;
        } else if (b == 0) {
            return a;
        }

        /*
         * Uses the binary GCD algorithm; see http://en.wikipedia.org/wiki/Binary_GCD_algorithm.
         * This is >40% faster than the Euclidean algorithm in benchmarks.
         */
        int aTwos = Integer.numberOfTrailingZeros(a);
        a >>= aTwos; // divide out all 2s
        int bTwos = Integer.numberOfTrailingZeros(b);
        b >>= bTwos; // divide out all 2s
        while (a != b) { // both a, b are odd
            // The key to the binary GCD algorithm is as follows:
            // Both a and b are odd.  Assume a > b; then gcd(a - b, b) = gcd(a, b).
            // But in gcd(a - b, b), a - b is even and b is odd, so we can divide out powers of two.

            // We bend over backwards to avoid branching, adapting a technique from
            // http://graphics.stanford.edu/~seander/bithacks.html#IntegerMinOrMax

            int delta = a - b; // can't overflow, since a and b are nonnegative

            int minDeltaOrZero = delta & (delta >> (Integer.SIZE - 1));
            // equivalent to Math.min(delta, 0)

            a = delta - minDeltaOrZero - minDeltaOrZero; // sets a to Math.abs(a - b)
            // a is now nonnegative and even

            b += minDeltaOrZero; // sets b to min(old a, b)
            a >>= Integer.numberOfTrailingZeros(a); // divide out all 2s, since 2 doesn't divide b
        }
        return a << min(aTwos, bTwos);
    }

    private WeightSupport() {}
}
