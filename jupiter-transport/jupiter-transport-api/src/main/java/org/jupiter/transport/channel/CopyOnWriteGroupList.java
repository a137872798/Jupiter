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

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

import org.jupiter.common.util.internal.UnsafeUtil;

/**
 * 相同服务, 不同服务节点的channel group容器,
 * 线程安全(写时复制), 实现原理类似 {@link java.util.concurrent.CopyOnWriteArrayList}.
 * <p>
 * update操作仅支持addIfAbsent/remove, update操作会同时更新对应服务节点(group)的引用计数.
 * <p>
 * jupiter
 * org.jupiter.transport.channel
 * <p>
 * 该对象内部管理多个 channelGroup
 *
 * @author jiachun.fjc
 */
public class CopyOnWriteGroupList {

    /**
     * 看来这些group 的 服务都是相同的 不过地址不同 而每个地址又分别有很多channel
     */
    private static final JChannelGroup[] EMPTY_GROUP = new JChannelGroup[0];
    private static final Object[] EMPTY_ARRAY = new Object[]{EMPTY_GROUP, null};

    private transient final ReentrantLock lock = new ReentrantLock();

    // 该对象内部维护了 多个 directory 与 CopyOnWriteGroupList 的关系
    private final DirectoryJChannelGroup parent;

    // array[0]: JChannelGroup[]
    // array[1]: Map<DirectoryString, WeightArray>
    private transient volatile Object[] array;

    public CopyOnWriteGroupList(DirectoryJChannelGroup parent) {
        this.parent = parent;
        // 初始状态 array 是默认值
        setArray(EMPTY_ARRAY);
    }

    public final JChannelGroup[] getSnapshot() {
        // 通过Unsafe获取数组对应的值  (底层具备跟volatile 相同能力的读取)
        return tabAt0(array);
    }

    /**
     * 获取快照对应的 权重数组
     *
     * @param snapshot
     * @param directory
     * @return
     */
    public final Object getWeightArray(JChannelGroup[] snapshot, String directory) {
        Object[] array = this.array; // data snapshot  写时拷贝
        // 这里不是严格同步的  前面的判断条件和后面不是原子操作
        return tabAt0(array) != snapshot
                ? null  // 如果已经发生了变化 返回null
                : (tabAt1(array) == null ? null : tabAt1(array).get(directory));
    }

    /**
     * 设置权重数组
     *
     * @param snapshot    需要有快照信息 确保当前数据没有变化
     * @param directory
     * @param weightArray
     * @return
     */
    public final boolean setWeightArray(JChannelGroup[] snapshot, String directory, Object weightArray) {
        // 如果快照发生变化 设置失败
        if (weightArray == null || snapshot != tabAt0(array)) {
            return false;
        }
        final ReentrantLock lock = this.lock;
        // 在锁中保证线程安全
        boolean locked = lock.tryLock();
        if (locked) { // give up if there is competition
            try {
                // 双重检查 数据发生变化返回false
                if (snapshot != tabAt0(array)) {
                    return false;
                }
                // 设置权重
                setWeightArray(directory, weightArray);
                return true;
            } finally {
                lock.unlock();
            }
        }
        return false;
    }

    @SuppressWarnings("SameParameterValue")
    private void setArray(Object[] array) {
        this.array = array;
    }

    @SuppressWarnings("SameParameterValue")
    private void setArray(JChannelGroup[] groups, Object weightArray) {
        array = new Object[]{groups, weightArray};
    }

    /**
     * 设置权重
     *
     * @param directory
     * @param weightArray
     */
    private void setWeightArray(String directory, Object weightArray) {
        // 数组的第一个元素 是一个map key 对应 服务三元组格式化后的字符串 value 代表对应的权重
        Map<String, Object> weightsMap = tabAt1(array);
        if (weightsMap == null) {
            weightsMap = new HashMap<>();
            setTabAt(array, 1, weightsMap);
        }
        weightsMap.put(directory, weightArray);
    }

    public int size() {
        return tabAt0(array).length;
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public boolean contains(JChannelGroup o) {
        JChannelGroup[] elements = tabAt0(array);
        return indexOf(o, elements, 0, elements.length) >= 0;
    }

    public int indexOf(JChannelGroup o) {
        JChannelGroup[] elements = tabAt0(array);
        return indexOf(o, elements, 0, elements.length);
    }

    public int indexOf(JChannelGroup o, int index) {
        JChannelGroup[] elements = tabAt0(array);
        return indexOf(o, elements, index, elements.length);
    }

    public JChannelGroup[] toArray() {
        JChannelGroup[] elements = tabAt0(array);
        return Arrays.copyOf(elements, elements.length);
    }

    private JChannelGroup get(JChannelGroup[] array, int index) {
        return array[index];
    }

    public JChannelGroup get(int index) {
        return get(tabAt0(array), index);
    }

    /**
     * Removes the first occurrence of the specified element from this list,
     * if it is present.  If this list does not contain the element, it is
     * unchanged.  More formally, removes the element with the lowest index
     * {@code i} such that
     * <tt>(o==null&nbsp;?&nbsp;get(i)==null&nbsp;:&nbsp;o.equals(get(i)))</tt>
     * (if such an element exists).  Returns {@code true} if this list
     * contained the specified element (or equivalently, if this list
     * changed as a result of the call).
     *
     * @param o element to be removed from this list, if present
     * @return {@code true} if this list contained the specified element
     */
    public boolean remove(JChannelGroup o) {
        JChannelGroup[] snapshot = tabAt0(array);
        int index = indexOf(o, snapshot, 0, snapshot.length);
        return (index >= 0) && remove(o, snapshot, index);
    }

    /**
     * A version of remove(JChannelGroup) using the strong hint that given
     * recent snapshot contains o at the given index.
     */
    private boolean remove(JChannelGroup o, JChannelGroup[] snapshot, int index) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            JChannelGroup[] current = tabAt0(array);
            int len = current.length;
            if (snapshot != current)
                findIndex:{
                    int prefix = Math.min(index, len);
                    for (int i = 0; i < prefix; i++) {
                        // 从current 中找到与o一样的 这时就返回 要被移除的下标 方便之后的数组数据迁移
                        if (current[i] != snapshot[i] && eq(o, current[i])) {
                            index = i;
                            break findIndex;
                        }
                    }
                    if (index >= len) {
                        return false;
                    }
                    if (current[index] == o) {
                        break findIndex;
                    }
                    index = indexOf(o, current, index, len);
                    if (index < 0) {
                        return false;
                    }
                }
            JChannelGroup[] newElements = new JChannelGroup[len - 1];
            System.arraycopy(current, 0, newElements, 0, index);
            System.arraycopy(current, index + 1, newElements, index, len - index - 1);
            // 这里清空了权重
            setArray(newElements, null);
            parent.decrementRefCount(o); // reference count -1
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Appends the element, if not present.
     *
     * @param o element to be added to this list, if absent
     * @return {@code true} if the element was added
     */
    public boolean addIfAbsent(JChannelGroup o) {
        JChannelGroup[] snapshot = tabAt0(array);
        return indexOf(o, snapshot, 0, snapshot.length) < 0 && addIfAbsent(o, snapshot);
    }

    /**
     * A version of addIfAbsent using the strong hint that given
     * recent snapshot does not contain o.
     */
    private boolean addIfAbsent(JChannelGroup o, JChannelGroup[] snapshot) {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            JChannelGroup[] current = tabAt0(array);
            int len = current.length;
            if (snapshot != current) {
                // optimize for lost race to another addXXX operation
                int common = Math.min(snapshot.length, len);
                for (int i = 0; i < common; i++) {
                    if (current[i] != snapshot[i] && eq(o, current[i])) {
                        return false;
                    }
                }
                if (indexOf(o, current, common, len) >= 0) {
                    return false;
                }
            }
            JChannelGroup[] newElements = Arrays.copyOf(current, len + 1);
            newElements[len] = o;
            setArray(newElements, null);
            parent.incrementRefCount(o); // reference count +1
            return true;
        } finally {
            lock.unlock();
        }
    }

    public boolean containsAll(Collection<? extends JChannelGroup> c) {
        JChannelGroup[] elements = tabAt0(array);
        int len = elements.length;
        for (JChannelGroup e : c) {
            if (indexOf(e, elements, 0, len) < 0) {
                return false;
            }
        }
        return true;
    }

    void clear() {
        final ReentrantLock lock = this.lock;
        lock.lock();
        try {
            setArray(EMPTY_ARRAY);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public String toString() {
        return Arrays.toString(tabAt0(array));
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        }
        if (!(o instanceof CopyOnWriteGroupList)) {
            return false;
        }

        CopyOnWriteGroupList other = (CopyOnWriteGroupList) (o);

        JChannelGroup[] elements = tabAt0(array);
        JChannelGroup[] otherElements = tabAt0(other.array);
        int len = elements.length;
        int otherLen = otherElements.length;

        if (len != otherLen) {
            return false;
        }

        for (int i = 0; i < len; i++) {
            if (!eq(elements[i], otherElements[i])) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("all")
    @Override
    public int hashCode() {
        int hashCode = 1;
        JChannelGroup[] elements = tabAt0(array);
        for (int i = 0, len = elements.length; i < len; i++) {
            JChannelGroup o = elements[i];
            hashCode = 31 * hashCode + (o == null ? 0 : o.hashCode());
        }
        return hashCode;
    }

    private boolean eq(JChannelGroup o1, JChannelGroup o2) {
        return (o1 == null ? o2 == null : o1.equals(o2));
    }

    private int indexOf(JChannelGroup o, JChannelGroup[] elements, int index, int fence) {
        if (o == null) {
            for (int i = index; i < fence; i++) {
                if (elements[i] == null) {
                    return i;
                }
            }
        } else {
            for (int i = index; i < fence; i++) {
                if (o.equals(elements[i])) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static JChannelGroup[] tabAt0(Object[] array) {
        return (JChannelGroup[]) tabAt(array, 0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> tabAt1(Object[] array) {
        return (Map<String, Object>) tabAt(array, 1);
    }

    private static Object tabAt(Object[] array, int index) {
        return UnsafeUtil.getObjectVolatile(array, index);
    }

    @SuppressWarnings("SameParameterValue")
    private static void setTabAt(Object[] array, int index, Object value) {
        UnsafeUtil.putObjectVolatile(array, index, value);
    }
}