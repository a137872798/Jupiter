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
package org.jupiter.common.util.internal;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.jupiter.common.util.SystemPropertyUtil;

/**
 * 利用对象继承的内存布局规则来padding避免false sharing, 注意其中对象头会至少占用8个字节
 * ---------------------------------------
 *  For 32 bit JVM:
 *      _mark   : 4 byte constant
 *      _klass  : 4 byte pointer to class
 *  For 64 bit JVM:
 *      _mark   : 8 byte constant
 *      _klass  : 8 byte pointer to class
 *  For 64 bit JVM with compressed-oops:
 *      _mark   : 8 byte constant
 *      _klass  : 4 byte pointer to class
 * ---------------------------------------
 */
class LhsPadding {
    @SuppressWarnings("unused")
    protected long p01, p02, p03, p04, p05, p06, p07;
}

class Fields extends LhsPadding {
    Object[] indexedVariables;

    // string-related thread-locals
    StringBuilder stringBuilder;
}

class RhsPadding extends Fields {
    @SuppressWarnings("unused")
    protected long p09, p10, p11, p12, p13, p14, p15;
}

/**
 * 参考了 <a href="https://github.com/netty/netty">Netty</a> FastThreadLocal 的设计, 有一些改动, 更适合jupiter使用
 *
 * jupiter
 * org.jupiter.common.util.internal
 * 首先该对象使用了缓存行填充，这样在高并发情况下针对map 进行反复插入不会性能不会下降太多
 * 该对象的查询需要传入下标 看它是怎么使用的   因为ThreadLocal 是通过kv形式查询数据的
 * @author jiachun.fjc
 */
public final class InternalThreadLocalMap extends RhsPadding {

    /**
     * 原子更新对象
     */
    private static final ReferenceFieldUpdater<StringBuilder, char[]> stringBuilderValueUpdater =
            Updaters.newReferenceFieldUpdater(StringBuilder.class.getSuperclass(), "value");

    /**
     * stringBuilder 的最大长度
     */
    private static final int DEFAULT_STRING_BUILDER_MAX_CAPACITY =
            SystemPropertyUtil.getInt("jupiter.internal.thread.local.string_builder_max_capacity", 1024 << 6);
    /**
     * stringBuilder 的初始长度
     */
    private static final int DEFAULT_STRING_BUILDER_INITIAL_CAPACITY =
            SystemPropertyUtil.getInt("jupiter.internal.thread.local.string_builder_initial_capacity", 512);

    /**
     * 默认的本地线程变量
     */
    private static final ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = new ThreadLocal<>();
    /**
     * id生成器
     */
    private static final AtomicInteger nextIndex = new AtomicInteger();

    public static final Object UNSET = new Object();

    public static InternalThreadLocalMap getIfSet() {
        Thread thread = Thread.currentThread();
        // 如果该线程是改良的线程 那么可以获取到 改良版的本地线程变量
        if (thread instanceof InternalThread) {
            return ((InternalThread) thread).threadLocalMap();
        }
        // 如果是forkJoin 线程池的线程 也可以获取到改良版的本地线程变量
        if (thread instanceof InternalForkJoinWorkerThread) {
            return ((InternalForkJoinWorkerThread) thread).threadLocalMap();
        }
        // 否则获取本线程对应的 普通 ThreadLocal
        return slowThreadLocalMap.get();
    }

    public static InternalThreadLocalMap get() {
        Thread thread = Thread.currentThread();
        if (thread instanceof InternalThread) {
            return fastGet((InternalThread) thread);
        }
        if (thread instanceof InternalForkJoinWorkerThread) {
            return fastGet((InternalForkJoinWorkerThread) thread);
        }
        // 获取普通的本地线程变量
        return slowGet();
    }

    /**
     * 将当前线程绑定的本地线程变量移除
     */
    public static void remove() {
        Thread thread = Thread.currentThread();
        if (thread instanceof InternalThread) {
            ((InternalThread) thread).setThreadLocalMap(null);
        } else if (thread instanceof InternalForkJoinWorkerThread) {
            ((InternalForkJoinWorkerThread) thread).setThreadLocalMap(null);
        } else {
            slowThreadLocalMap.remove();
        }
    }

    public static void destroy() {
        slowThreadLocalMap.remove();
    }

    /**
     * 生成下一个唯一标识
     * @return
     */
    public static int nextVariableIndex() {
        int index = nextIndex.getAndIncrement();
        if (index < 0) {
            nextIndex.decrementAndGet();
            throw new IllegalStateException("Too many thread-local indexed variables");
        }
        return index;
    }

    public static int lastVariableIndex() {
        return nextIndex.get() - 1;
    }

    private InternalThreadLocalMap() {
        // 获取一个填满空对象的数组
        indexedVariables = newIndexedVariableTable();
    }

    /**
     * 根据 index 查询数组中某个元素 如果元素未设置 返回 UNSET (也就是空对象) 如果index 超过最大长度限制也是返回空对象
     * @param index
     * @return
     */
    public Object indexedVariable(int index) {
        Object[] lookup = indexedVariables;
        return index < lookup.length ? lookup[index] : UNSET;
    }

    /**
     * 将指定对象设置到数组中   这个填充数组是为了避免数组被GC回收吗???
     * @return {@code true} if and only if a new thread-local variable has been created  只有从 UNSET 变成一个有效的值才会返回true
     */
    public boolean setIndexedVariable(int index, Object value) {
        Object[] lookup = indexedVariables;
        if (index < lookup.length) {
            Object oldValue = lookup[index];
            lookup[index] = value;
            return oldValue == UNSET;
        } else {
            // 扩容
            expandIndexedVariableTableAndSet(index, value);
            return true;
        }
    }

    /**
     * 将对应值还原成 UNSET
     * @param index
     * @return
     */
    public Object removeIndexedVariable(int index) {
        Object[] lookup = indexedVariables;
        if (index < lookup.length) {
            Object v = lookup[index];
            lookup[index] = UNSET;
            return v;
        } else {
            return UNSET;
        }
    }

    public int size() {
        int count = 0;
        for (Object o : indexedVariables) {
            if (o != UNSET) {
                ++count;
            }
        }
        return count;
    }

    public StringBuilder stringBuilder() {
        StringBuilder builder = stringBuilder;
        if (builder == null) {
            stringBuilder = builder = new StringBuilder(DEFAULT_STRING_BUILDER_INITIAL_CAPACITY);
        } else {
            if (builder.capacity() > DEFAULT_STRING_BUILDER_MAX_CAPACITY) {
                // ensure memory overhead
                stringBuilderValueUpdater.set(builder, new char[DEFAULT_STRING_BUILDER_INITIAL_CAPACITY]);
            }
            builder.setLength(0);
        }
        return builder;
    }

    /**
     * 获取一个填满元素的数组对象
     * @return
     */
    private static Object[] newIndexedVariableTable() {
        Object[] array = new Object[32];
        Arrays.fill(array, UNSET);
        return array;
    }

    /**
     * 获取比较快的本地线程变量
     * @param thread
     * @return
     */
    private static InternalThreadLocalMap fastGet(InternalThread thread) {
        InternalThreadLocalMap threadLocalMap = thread.threadLocalMap();
        if (threadLocalMap == null) {
            thread.setThreadLocalMap(threadLocalMap = new InternalThreadLocalMap());
        }
        return threadLocalMap;
    }

    private static InternalThreadLocalMap fastGet(InternalForkJoinWorkerThread thread) {
        InternalThreadLocalMap threadLocalMap = thread.threadLocalMap();
        if (threadLocalMap == null) {
            thread.setThreadLocalMap(threadLocalMap = new InternalThreadLocalMap());
        }
        return threadLocalMap;
    }

    /**
     * 获取比较慢的本地线程变量
     * @return
     */
    private static InternalThreadLocalMap slowGet() {
        ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = InternalThreadLocalMap.slowThreadLocalMap;
        InternalThreadLocalMap ret = slowThreadLocalMap.get();
        if (ret == null) {
            ret = new InternalThreadLocalMap();
            slowThreadLocalMap.set(ret);
        }
        return ret;
    }

    /**
     * 针对数组进行扩容  注意因为该对象本身是线程绑定的 那么不需要做任何并发控制
     * @param index
     * @param value
     */
    private void expandIndexedVariableTableAndSet(int index, Object value) {
        Object[] oldArray = indexedVariables;
        final int oldCapacity = oldArray.length;
        int newCapacity = index;
        newCapacity |= newCapacity >>> 1;
        newCapacity |= newCapacity >>> 2;
        newCapacity |= newCapacity >>> 4;
        newCapacity |= newCapacity >>> 8;
        newCapacity |= newCapacity >>> 16;
        newCapacity++;

        Object[] newArray = Arrays.copyOf(oldArray, newCapacity);
        Arrays.fill(newArray, oldCapacity, newArray.length, UNSET);
        newArray[index] = value;
        indexedVariables = newArray;
    }
}
