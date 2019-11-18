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
package org.jupiter.common.atomic;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import sun.misc.Unsafe;

/**
 * jupiter
 * org.jupiter.common.concurrent.atomic
 *
 * Forked from <a href="https://github.com/netty/netty">Netty</a>.
 * 基于unsafe 的原子更新
 */
@SuppressWarnings("unchecked")
final class UnsafeAtomicReferenceFieldUpdater<U, W> extends AtomicReferenceFieldUpdater<U, W> {
    private final long offset;
    private final Unsafe unsafe;

    UnsafeAtomicReferenceFieldUpdater(Unsafe unsafe, Class<U> tClass, String fieldName) throws NoSuchFieldException {
        Field field = tClass.getDeclaredField(fieldName);
        // 首先能被原子更新的字段必须被volatile 修饰(否则即使原子更新 读取还是走的高速缓存，还是过时数据)
        if (!Modifier.isVolatile(field.getModifiers())) {
            throw new IllegalArgumentException("Field [" + fieldName + "] must be volatile");
        }
        if (unsafe == null) {
            throw new NullPointerException("unsafe");
        }
        this.unsafe = unsafe;
        // 获取该字段的偏移量便于更改数据
        offset = unsafe.objectFieldOffset(field);
    }

    @Override
    public boolean compareAndSet(U obj, W expect, W update) {
        return unsafe.compareAndSwapObject(obj, offset, expect, update);
    }

    @Override
    public boolean weakCompareAndSet(U obj, W expect, W update) {
        return unsafe.compareAndSwapObject(obj, offset, expect, update);
    }

    @Override
    public void set(U obj, W newValue) {
        unsafe.putObjectVolatile(obj, offset, newValue);
    }

    @Override
    public void lazySet(U obj, W newValue) {
        unsafe.putOrderedObject(obj, offset, newValue);
    }

    @Override
    public W get(U obj) {
        return (W) unsafe.getObjectVolatile(obj, offset);
    }
}
