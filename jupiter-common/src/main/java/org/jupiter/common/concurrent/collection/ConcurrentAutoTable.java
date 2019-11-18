/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jupiter.common.concurrent.collection;

import java.io.Serializable;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import sun.misc.Unsafe;

import org.jupiter.common.util.internal.UnsafeUtil;


/**
 * An auto-resizing table of {@code longs}, supporting low-contention CAS
 * operations.  Updates are done with CAS's to no particular table element.
 * The intent is to support highly scalable counters, r/w locks, and other
 * structures where the updates are associative, loss-free (no-brainer), and
 * otherwise happen at such a high volume that the cache contention for
 * CAS'ing a single word is unacceptable.
 *
 * @author Cliff Click
 * @since 1.5
 *
 * Forked from <a href="https://github.com/JCTools/JCTools">JCTools</a>.
 * 具备自动扩容的低竞争并发容器
 */
public class ConcurrentAutoTable implements Serializable {

    private static final long serialVersionUID = -754466836461919739L;

    /**
     * 获取unsafe 对象
     */
    private static Unsafe unsafe = UnsafeUtil.getUnsafeAccessor().getUnsafe();

    // --- public interface ---

    /**
     * Add the given value to current counter value.  Concurrent updates will
     * not be lost, but addAndGet or getAndAdd are not implemented because the
     * total counter value (i.e., {@link #get}) is not atomically updated.
     * Updates are striped across an array of counters to avoid cache contention
     * and has been tested with performance scaling linearly up to 768 CPUs.
     */
    public void add(long x) {
        add_if(x);
    }

    /**
     * {@link #add} with -1
     */
    public void decrement() {
        add_if(-1L);
    }

    /**
     * {@link #add} with +1
     */
    public void increment() {
        add_if(1L);
    }

    /**
     * Atomically set the sum of the striped counters to specified value.
     * Rather more expensive than a simple store, in order to remain atomic.
     * 这里生成一个空的cat 对象并替换了 _cat 字段
     */
    @SuppressWarnings("StatementWithEmptyBody")
    public void set(long x) {
        CAT newcat = new CAT(null, 4, x);
        // Spin until CAS works
        while (!CAS_cat(_cat, newcat)) { /* empty */ }
    }

    /**
     * Current value of the counter.  Since other threads are updating furiously
     * the value is only approximate, but it includes all counts made by the
     * current thread.  Requires a pass over the internally striped counters.
     * 计算所有元素总和
     */
    public long get() {
        return _cat.sum();
    }

    /**
     * Same as {@link #get}, included for completeness.
     */
    public int intValue() {
        return (int) _cat.sum();
    }

    /**
     * Same as {@link #get}, included for completeness.
     */
    public long longValue() {
        return _cat.sum();
    }

    /**
     * A cheaper {@link #get}.  Updated only once/millisecond, but as fast as a
     * simple load instruction when not updating.
     * 获取一个预估值
     */
    public long estimate_get() {
        return _cat.estimate_sum();
    }

    /**
     * Return the counter's {@code long} value converted to a string.
     */
    public String toString() {
        return _cat.toString();
    }

    /**
     * A more verbose print than {@link #toString}, showing internal structure.
     * Useful for debugging.
     */
    public void print() {
        _cat.print();
    }

    /**
     * Return the internal counter striping factor.  Useful for diagnosing
     * performance problems.
     */
    public int internal_size() {
        return _cat._t.length;
    }

    // Only add 'x' to some slot in table, hinted at by 'hash'.  The sum can
    // overflow.  Value is CAS'd so no counts are lost.  The CAS is retried until
    // it succeeds.  Returned value is the old value.
    // 不同的线程会生成不同的hash值 当竞争很激烈时 也就是有很多线程开始往里面添加值，这样就容易hash冲突(因为这里的hash是跟线程挂钩的，线程多了才容易冲突)
    // 这时 竞争达到一定激烈程度导致CAS 经常失败 ， 就会创建一个更大的数组，便于更激烈的竞争。同时计算sum时会连带之前槽中的数据
    private long add_if(long x) {
        return _cat.add_if(x, hash(), this);
    }

    // The underlying array of concurrently updated long counters
    private volatile CAT _cat = new CAT(null, 16/*Start Small, Think Big!*/, 0L);
    private static final AtomicReferenceFieldUpdater<ConcurrentAutoTable, CAT> _catUpdater =
            AtomicReferenceFieldUpdater.newUpdater(ConcurrentAutoTable.class, CAT.class, "_cat");

    /**
     * cas 方式更新cat
     * @param oldcat
     * @param newcat
     * @return
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private boolean CAS_cat(CAT oldcat, CAT newcat) {
        return _catUpdater.compareAndSet(this, oldcat, newcat);
    }

    // Hash spreader
    private static int hash() {
        //int h = (int)Thread.currentThread().getId();
        int h = System.identityHashCode(Thread.currentThread());
        return h << 3;                // Pad out cache lines.  The goal is to avoid cache-line contention
    }

    // --- CAT -----------------------------------------------------------------
    private static class CAT implements Serializable {

        // Unsafe crud: get a function which will CAS arrays  获取long[]的 基础偏移量
        private static final int _Lbase = unsafe.arrayBaseOffset(long[].class);
        // 获取每个元素的偏移量
        private static final int _Lscale = unsafe.arrayIndexScale(long[].class);

        // 通过 unsafe对象来获取偏移量
        private static long rawIndex(long[] ary, int i) {
            assert i >= 0 && i < ary.length;
            return _Lbase + ((long) i * _Lscale);
        }

        /**
         * CAS 更新数组中某个值
         * @param A
         * @param idx
         * @param old
         * @param nnn
         * @return
         */
        private static boolean CAS(long[] A, int idx, long old, long nnn) {
            return unsafe.compareAndSwapLong(A, rawIndex(A, idx), old, nnn);
        }

        //volatile long _resizers;    // count of threads attempting a resize
        //static private final AtomicLongFieldUpdater<CAT> _resizerUpdater =
        //  AtomicLongFieldUpdater.newUpdater(CAT.class, "_resizers");

        /**
         * CAT 是一个链表结构
         */
        private final CAT _next;
        /**
         * 模糊缓存总数
         */
        private volatile long _fuzzy_sum_cache;
        /**
         * 模糊时间
         */
        private volatile long _fuzzy_time;
        private static final int MAX_SPIN = 1;
        /**
         * 数组大小一开始就是固定的
         */
        private final long[] _t;     // Power-of-2 array of longs

        CAT(CAT next, int sz, long init) {
            _next = next;
            _t = new long[sz];
            _t[0] = init;
        }

        // Only add 'x' to some slot in table, hinted at by 'hash'.  The sum can
        // overflow.  Value is CAS'd so no counts are lost.  The CAS is attempted
        // ONCE.
        @SuppressWarnings("StatementWithEmptyBody")
        public long add_if(long x, int hash, ConcurrentAutoTable master) {
            final long[] t = _t;
            // 找到数组下标
            final int idx = hash & (t.length - 1);
            // Peel loop; try once fast
            long old = t[idx];
            // 更新指定值
            final boolean ok = CAS(t, idx, old, old + x);
            // 成功情况下返回旧的数据
            if (ok) return old;      // Got it
            // Try harder  失败准备进行自旋
            int cnt = 0;
            while (true) {
                old = t[idx];
                if (CAS(t, idx, old, old + x)) break; // Got it!
                cnt++;
            }
            // 第二次就成功的情况下 返回old
            if (cnt < MAX_SPIN) return old; // Allowable spin loop count
            // 数组过大的情况也返回old
            if (t.length >= 1024 * 1024) return old; // too big already

            // 以下情况代表竞争很激烈

            // Too much contention; double array size in an effort to reduce contention
            //long r = _resizers;
            //final int newbytes = (t.length<<1)<<3/*word to bytes*/;
            //while( !_resizerUpdater.compareAndSet(this,r,r+newbytes) )
            //  r = _resizers;
            //r += newbytes;
            // _cat 已经被替换的情况下 直接返回old
            if (master._cat != this) return old; // Already doubled, don't bother
            //if( (r>>17) != 0 ) {      // Already too much allocation attempts?
            //  // We could use a wait with timeout, so we'll wakeup as soon as the new
            //  // table is ready, or after the timeout in any case.  Annoyingly, this
            //  // breaks the non-blocking property - so for now we just briefly sleep.
            //  //synchronized( this ) { wait(8*megs); }         // Timeout - we always wakeup
            //  try { Thread.sleep(r>>17); } catch( InterruptedException e ) { }
            //  if( master._cat != this ) return old;
            //}

            // 扩容生成了一个新对象
            CAT newcat = new CAT(this, t.length * 2, 0);
            // Take 1 stab at updating the CAT with the new larger size.  If this
            // fails, we assume some other thread already expanded the CAT - so we
            // do not need to retry until it succeeds.
            // 将master的 下个元素替换成新生成的 cat
            while (master._cat == this && !master.CAS_cat(this, newcat)) { /* empty */ }
            return old;
        }


        // Return the current sum of all things in the table.  Writers can be
        // updating the table furiously, so the sum is only locally accurate.

        /**
         * 当前总值就是将链表中所有元素值总和加起来   这里好像是允许脏读的
         * @return
         */
        @SuppressWarnings("UnnecessaryLocalVariable")
        public long sum() {
            long sum = _next == null ? 0 : _next.sum(); // Recursively get cached sum
            final long[] t = _t;
            for (long cnt : t) sum += cnt;
            return sum;
        }

        // Fast fuzzy version.  Used a cached value until it gets old, then re-up
        // the cache.
        // 获取一个预估值
        public long estimate_sum() {
            // For short tables, just do the work
            // 当数组长度还比较小的时候直接计算sum
            if (_t.length <= 64) return sum();
            // For bigger tables, periodically freshen a cached value  在相距1毫秒内 返回缓存值
            long millis = System.currentTimeMillis();
            if (_fuzzy_time != millis) { // Time marches on?
                // 如果数值比较大的话 就生成一个缓存值 下次尝试获取值时 只要没有超过预定的时间 就继续返回缓存值
                _fuzzy_sum_cache = sum(); // Get sum the hard way
                _fuzzy_time = millis;   // Indicate freshness of cached value
            }
            return _fuzzy_sum_cache;  // Return cached sum
        }

        public String toString() {
            return Long.toString(sum());
        }

        public void print() {
            long[] t = _t;
            System.out.print("[" + t[0]);
            for (int i = 1; i < t.length; i++)
                System.out.print("," + t[i]);
            System.out.print("]");
            if (_next != null) _next.print();
        }
    }
}
