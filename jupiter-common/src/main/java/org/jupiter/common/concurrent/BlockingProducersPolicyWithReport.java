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
package org.jupiter.common.concurrent;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 使用阻塞生产者的饱和策略, 不抛弃任务, 也不抛出异常, 当队列满时改为调用BlockingQueue.put来实现生产者的阻塞.
 *
 * jupiter
 * org.jupiter.common.concurrent
 * 基于阻塞生产者的方式来执行任务， 确保队列中的任务能被消费者消耗而非丢弃
 * @author jiachun.fjc
 */
public class BlockingProducersPolicyWithReport extends AbstractRejectedExecutionHandler {

    public BlockingProducersPolicyWithReport(String threadPoolName) {
        super(threadPoolName, false, "");
    }

    /**
     * 强制指定转储地址
     * @param threadPoolName
     * @param dumpPrefixName
     */
    public BlockingProducersPolicyWithReport(String threadPoolName, String dumpPrefixName) {
        super(threadPoolName, true, dumpPrefixName);
    }

    /**
     * 当线程池的阻塞队列无法存放新任务时
     * @param r
     * @param e
     */
    @Override
    public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
        logger.error("Thread pool [{}] is exhausted! {}.", threadPoolName, e.toString());

        // 如果设置了要转储 记录当jvm 信息
        dumpJvmInfoIfNeeded();

        if (!e.isShutdown()) {
            try {
                // 此时继续往阻塞队列中添加任务的结果就是阻塞直到队列中的任务被消耗
                e.getQueue().put(r);
            } catch (InterruptedException ignored) { /* should not be interrupted */ }
        }
    }
}
