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
package org.jupiter.common.concurrent.disruptor;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.jupiter.common.concurrent.NamedThreadFactory;
import org.jupiter.common.concurrent.RejectedTaskPolicyWithReport;
import org.jupiter.common.util.Pow2;
import org.jupiter.common.util.Requires;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.EventFactory;
import com.lmax.disruptor.InsufficientCapacityException;
import com.lmax.disruptor.LiteBlockingWaitStrategy;
import com.lmax.disruptor.LiteTimeoutBlockingWaitStrategy;
import com.lmax.disruptor.PhasedBackoffWaitStrategy;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.TimeoutBlockingWaitStrategy;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;

/**
 *
 * The default wait strategy used by the Disruptor is the BlockingWaitStrategy.
 *
 * BlockingWaitStrategy:
 * Internally the BlockingWaitStrategy uses a typical lock and condition variable to handle thread wake-up.
 * The BlockingWaitStrategy is the slowest of the available wait strategies,
 * but is the most conservative with the respect to CPU usage and will give the most consistent behaviour across
 * the widest variety of deployment options. However, again knowledge of the deployed system can allow for additional
 * performance.
 *
 * SleepingWaitStrategy:
 * Like the BlockingWaitStrategy the SleepingWaitStrategy it attempts to be conservative with CPU usage,
 * by using a simple busy wait loop, but uses a call to LockSupport.parkNanos(1) in the middle of the loop.
 * On a typical Linux system this will pause the thread for around 60µs.
 * However it has the benefit that the producing thread does not need to take any action other increment the appropriate
 * counter and does not require the cost of signalling a condition variable. However, the mean latency of moving the
 * event between the producer and consumer threads will be higher. It works best in situations where low latency is not
 * required, but a low impact on the producing thread is desired. A common use case is for asynchronous logging.
 *
 * YieldingWaitStrategy:
 * The YieldingWaitStrategy is one of 2 Wait Strategies that can be use in low latency systems,
 * where there is the option to burn CPU cycles with the goal of improving latency.
 * The YieldingWaitStrategy will busy spin waiting for the sequence to increment to the appropriate value.
 * Inside the body of the loop Thread.yield() will be called allowing other queued threads to run.
 * This is the recommended wait strategy when need very high performance and the number of Event Handler threads is
 * less than the total number of logical cores, e.g. you have hyper-threading enabled.
 *
 * BusySpinWaitStrategy:
 * The BusySpinWaitStrategy is the highest performing Wait Strategy, but puts the highest constraints on the deployment
 * environment. This wait strategy should only be used if the number of Event Handler threads is smaller than the number
 * of physical cores on the box. E.g. hyper-threading should be disabled
 *
 * jupiter
 * org.jupiter.common.concurrent.disruptor
 *
 * @author jiachun.fjc
 */
public class TaskDispatcher implements Dispatcher<Runnable>, Executor {

    /**
     * 使用MessageEvent 去填充环形缓冲区
     */
    private static final EventFactory<MessageEvent<Runnable>> eventFactory = MessageEvent::new;

    private final Disruptor<MessageEvent<Runnable>> disruptor;
    /**
     * 储存用的线程池对象
     */
    private final ExecutorService reserveExecutor;

    public TaskDispatcher(int numWorkers, ThreadFactory threadFactory) {
        this(numWorkers, threadFactory, BUFFER_SIZE, 0, WaitStrategyType.BLOCKING_WAIT, null);
    }

    /**
     * 初始化 disruptor 以及 线程池
     * @param numWorkers
     * @param threadFactory
     * @param bufSize
     * @param numReserveWorkers
     * @param waitStrategyType
     * @param dumpPrefixName
     */
    public TaskDispatcher(int numWorkers,
                          ThreadFactory threadFactory,
                          int bufSize,
                          int numReserveWorkers,
                          WaitStrategyType waitStrategyType,
                          String dumpPrefixName) {

        Requires.requireTrue(bufSize > 0, "bufSize must be larger than 0");
        if (!Pow2.isPowerOfTwo(bufSize)) {
            bufSize = Pow2.roundToPowerOfTwo(bufSize);
        }

        // 如果设置了保存工作线程的话
        if (numReserveWorkers > 0) {
            String name = "reserve.processor";

            RejectedExecutionHandler handler;
            if (dumpPrefixName == null) {
                // 默认采用拒绝策略 如果设置了转储文件名 那么在拒绝同时会存储当前jvm 状态信息
                handler = new RejectedTaskPolicyWithReport(name);
            } else {
                handler = new RejectedTaskPolicyWithReport(name, dumpPrefixName);
            }

            reserveExecutor = new ThreadPoolExecutor(
                    0,
                    numReserveWorkers,
                    60L,
                    TimeUnit.SECONDS,
                    new SynchronousQueue<>(), // 这里使用同步队列
                    new NamedThreadFactory(name),
                    handler);
        } else {
            reserveExecutor = null;
        }

        // 根据当前策略选择不同的 disruptor阻塞策略
        WaitStrategy waitStrategy;
        switch (waitStrategyType) {
            case BLOCKING_WAIT:
                waitStrategy = new BlockingWaitStrategy();
                break;
            case LITE_BLOCKING_WAIT:
                waitStrategy = new LiteBlockingWaitStrategy();
                break;
            case TIMEOUT_BLOCKING_WAIT:
                waitStrategy = new TimeoutBlockingWaitStrategy(1000, TimeUnit.MILLISECONDS);
                break;
            case LITE_TIMEOUT_BLOCKING_WAIT:
                waitStrategy = new LiteTimeoutBlockingWaitStrategy(1000, TimeUnit.MILLISECONDS);
                break;
            case PHASED_BACK_OFF_WAIT:
                waitStrategy = PhasedBackoffWaitStrategy.withLiteLock(1000, 1000, TimeUnit.NANOSECONDS);
                break;
            case SLEEPING_WAIT:
                waitStrategy = new SleepingWaitStrategy();
                break;
            case YIELDING_WAIT:
                waitStrategy = new YieldingWaitStrategy();
                break;
            case BUSY_SPIN_WAIT:
                waitStrategy = new BusySpinWaitStrategy();
                break;
            default:
                throw new UnsupportedOperationException(waitStrategyType.toString());
        }

        if (threadFactory == null) {
            threadFactory = new NamedThreadFactory("disruptor.processor");
        }
        // 这里将netty的优化线程组合到 disruptor 进一步提高性能
        // TODO 明天复习下 disruptor 是怎么使用线程工厂的
        Disruptor<MessageEvent<Runnable>> dr =
                new Disruptor<>(eventFactory, bufSize, threadFactory, ProducerType.MULTI, waitStrategy);
        dr.setDefaultExceptionHandler(new LoggingExceptionHandler());  // 默认的异常处理器就是打印日志
        numWorkers = Math.min(Math.abs(numWorkers), MAX_NUM_WORKERS);
        // 如果只有一个消费者 生成单个handler
        if (numWorkers == 1) {
            dr.handleEventsWith(new TaskHandler());
        } else {
            // 生成一个消费者组
            TaskHandler[] handlers = new TaskHandler[numWorkers];
            for (int i = 0; i < numWorkers; i++) {
                handlers[i] = new TaskHandler();
            }
            dr.handleEventsWithWorkerPool(handlers);
        }

        dr.start();
        disruptor = dr;
    }

    /**
     * 该请求分发处理器对象 处理传入的 message
     * @param message
     * @return
     */
    @Override
    public boolean dispatch(Runnable message) {
        RingBuffer<MessageEvent<Runnable>> ringBuffer = disruptor.getRingBuffer();
        try {
            // 这是一种老旧的写法 吧 实际上直接通过 transfer设置任务到 ringBuffer就可以
            long sequence = ringBuffer.tryNext();
            try {
                MessageEvent<Runnable> event = ringBuffer.get(sequence);
                event.setMessage(message);
            } finally {
                ringBuffer.publish(sequence);
            }
            return true;
        } catch (InsufficientCapacityException e) {
            // 这个异常是Disruptor当做全局goto使用的, 是单例的并且没有堆栈信息, 不必担心抛出异常的性能问题
            return false;
        }
    }

    /**
     * 该对象执行 runnable 时 首先会将任务设置到 disruptor中  如果因为消费者阻塞 生产者长时间没办法写入任务的话(可能性很小)
     * 那么就使用备选的线程池
     * @param message
     */
    @Override
    public void execute(Runnable message) {
        if (!dispatch(message)) {
            // 备选线程池  当备选线程池也处理不完任务时才触发拒绝策略
            if (reserveExecutor != null) {
                reserveExecutor.execute(message);
            } else {
                throw new RejectedExecutionException("Ring buffer is full");
            }
        }
    }

    @Override
    public void shutdown() {
        disruptor.shutdown();
        if (reserveExecutor != null) {
            reserveExecutor.shutdownNow();
        }
    }
}
