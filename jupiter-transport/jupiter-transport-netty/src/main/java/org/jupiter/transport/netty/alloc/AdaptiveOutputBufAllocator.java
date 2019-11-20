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
package org.jupiter.transport.netty.alloc;

import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import org.jupiter.common.util.Lists;

/**
 * jupiter
 * org.jupiter.transport.alloc
 *
 * 自适应缓冲区
 * @author jiachun.fjc
 */
public class AdaptiveOutputBufAllocator {

    /**
     * 默认最小值
     */
    private static final int DEFAULT_MINIMUM = 64;
    private static final int DEFAULT_INITIAL = 512;
    private static final int DEFAULT_MAXIMUM = 524288;

    private static final int INDEX_INCREMENT = 4;
    private static final int INDEX_DECREMENT = 1;

    private static final int[] SIZE_TABLE;

    static {
        List<Integer> sizeTable = Lists.newArrayList();
        for (int i = 16; i < 512; i += 16) {
            // 以16的步长递增并添加到list中   16 32 48 64 ...
            sizeTable.add(i);
        }

        // 步长变成翻倍 这个代码好像跟netty 那么内存划分很像  这里直到溢出为止
        for (int i = 512; i > 0; i <<= 1) { // lgtm [java/constant-comparison]
            sizeTable.add(i);
        }

        // 那为什么不一开始就初始化 SIZE_TABLE  ???
        SIZE_TABLE = new int[sizeTable.size()];
        for (int i = 0; i < SIZE_TABLE.length; i++) {
            SIZE_TABLE[i] = sizeTable.get(i);
        }
    }

    public static final AdaptiveOutputBufAllocator DEFAULT = new AdaptiveOutputBufAllocator();

    private static int getSizeTableIndex(final int size) {
        // 二分法
        for (int low = 0, high = SIZE_TABLE.length - 1; ; ) {
            if (high < low) {
                return low;
            }
            if (high == low) {
                return high;
            }

            // 快速定位到 目标size 最贴近的 长度的下标
            int mid = low + high >>> 1;
            int a = SIZE_TABLE[mid];
            int b = SIZE_TABLE[mid + 1];
            if (size > b) {
                low = mid + 1;
            } else if (size < a) {
                high = mid - 1;
            } else if (size == a) {
                return mid;
            } else {
                return mid + 1;
            }
        }
    }

    /**
     * 分配内存处理器
     */
    public interface Handle {

        /**
         * Creates a new buffer whose capacity is probably large enough to write all outbound data and small
         * enough not to waste its space.
         * 通过分配器分配 buf 对象
         */
        ByteBuf allocate(ByteBufAllocator alloc);

        /**
         * Similar to {@link #allocate(ByteBufAllocator)} except that it does not allocate anything but just tells the
         * capacity.  仅告知容量
         */
        int guess();

        /**
         * Records the the actual number of wrote bytes in the previous write operation so that the allocator allocates
         * the buffer with potentially more correct capacity.
         *
         * 记录本次分配的大小
         * @param actualWroteBytes the actual number of wrote bytes in the previous allocate operation
         */
        void record(int actualWroteBytes);
    }

    /**
     * 分配器的默认实现
     */
    private static final class HandleImpl implements Handle {

        /**
         * 最小的下标
         */
        private final int minIndex;
        /**
         * 最大的下标    推测每个handle 一开始就有一个划分的区域 只能在这块区域内进行划分
         */
        private final int maxIndex;
        private int index;                          // Single IO thread read/write
        private volatile int nextAllocateBufSize;   // Single IO thread read/write, other thread read
        private boolean decreaseNow;                // Single IO thread read/write

        /**
         * 初始化时指定了 允许扫描的index 范围
         * @param minIndex
         * @param maxIndex
         * @param initial  初始化的size
         */
        HandleImpl(int minIndex, int maxIndex, int initial) {
            this.minIndex = minIndex;
            this.maxIndex = maxIndex;

            // 找到对应size 最接近的偏移量
            index = getSizeTableIndex(initial);
            // 获取真正的size
            nextAllocateBufSize = SIZE_TABLE[index];
        }

        /**
         * 分配指定大小
         * @param alloc
         * @return
         */
        @Override
        public ByteBuf allocate(ByteBufAllocator alloc) {
            return alloc.buffer(guess());
        }

        /**
         * 预估需要分配的大小
         * @return
         */
        @Override
        public int guess() {
            return nextAllocateBufSize;
        }

        @Override
        public void record(int actualWroteBytes) {
            if (actualWroteBytes <= SIZE_TABLE[Math.max(0, index - INDEX_DECREMENT - 1)]) {
                if (decreaseNow) {
                    index = Math.max(index - INDEX_DECREMENT, minIndex);
                    nextAllocateBufSize = SIZE_TABLE[index];
                    decreaseNow = false;
                } else {
                    decreaseNow = true;
                }
            } else if (actualWroteBytes >= nextAllocateBufSize) {
                index = Math.min(index + INDEX_INCREMENT, maxIndex);
                nextAllocateBufSize = SIZE_TABLE[index];
                decreaseNow = false;
            }
        }
    }

    private final int minIndex;
    private final int maxIndex;
    private final int initial;

    /**
     * Creates a new predictor with the default parameters.  With the default
     * parameters, the expected buffer size starts from {@code 512}, does not
     * go down below {@code 64}, and does not go up above {@code 524288}.
     * 通过要求的容量来初始化
     */
    private AdaptiveOutputBufAllocator() {
        this(DEFAULT_MINIMUM, DEFAULT_INITIAL, DEFAULT_MAXIMUM);
    }

    /**
     * Creates a new predictor with the specified parameters.
     *
     * @param minimum the inclusive lower bound of the expected buffer size
     * @param initial the initial buffer size when no feed back was received
     * @param maximum the inclusive upper bound of the expected buffer size
     *                将容量转换成 数组下标
     */
    public AdaptiveOutputBufAllocator(int minimum, int initial, int maximum) {
        if (minimum <= 0) {
            throw new IllegalArgumentException("minimum: " + minimum);
        }
        if (initial < minimum) {
            throw new IllegalArgumentException("initial: " + initial);
        }
        if (maximum < initial) {
            throw new IllegalArgumentException("maximum: " + maximum);
        }

        int minIndex = getSizeTableIndex(minimum);
        if (SIZE_TABLE[minIndex] < minimum) {
            this.minIndex = minIndex + 1;
        } else {
            this.minIndex = minIndex;
        }

        int maxIndex = getSizeTableIndex(maximum);
        if (SIZE_TABLE[maxIndex] > maximum) {
            this.maxIndex = maxIndex - 1;
        } else {
            this.maxIndex = maxIndex;
        }

        this.initial = initial;
    }

    public Handle newHandle() {
        return new AdaptiveOutputBufAllocator.HandleImpl(minIndex, maxIndex, initial);
    }
}
