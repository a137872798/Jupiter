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

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jupiter.common.util.JvmTools;
import org.jupiter.common.util.internal.logging.InternalLogger;
import org.jupiter.common.util.internal.logging.InternalLoggerFactory;

import static org.jupiter.common.util.StackTraceUtil.stackTrace;

/**
 * Jupiter
 * org.jupiter.common.concurrent
 * 拒绝策略对象
 * @author jiachun.fjc
 */
public abstract class AbstractRejectedExecutionHandler implements RejectedExecutionHandler {

    protected static final InternalLogger logger = InternalLoggerFactory.getInstance(AbstractRejectedExecutionHandler.class);

    /**
     * 代表该拒绝策略是针对哪个线程池的
     */
    protected final String threadPoolName;
    /**
     * 是否应该转储
     */
    private final AtomicBoolean dumpNeeded;
    /**
     * 转储前缀
     */
    private final String dumpPrefixName;

    public AbstractRejectedExecutionHandler(String threadPoolName, boolean dumpNeeded, String dumpPrefixName) {
        this.threadPoolName = threadPoolName;
        this.dumpNeeded = new AtomicBoolean(dumpNeeded);
        this.dumpPrefixName = dumpPrefixName;
    }

    /**
     * 将当前jvm信息保存到某个文件中
     */
    public void dumpJvmInfoIfNeeded() {
        // 只允许转储一次 之后就会将该值设置为false
        if (dumpNeeded.getAndSet(false)) {
            String now = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
            String name = threadPoolName + "_" + now;
            // 生成文件用于记录当前被转储的数据
            try (FileOutputStream fileOutput = new FileOutputStream(new File(dumpPrefixName + "_dump_" + name + ".log"))) {

                // 生成当前所有线程的栈轨迹信息
                List<String> stacks = JvmTools.jStack();
                for (String s : stacks) {
                    // 写入到文件中
                    fileOutput.write(s.getBytes(StandardCharsets.UTF_8));
                }

                // 获取当前内存使用情况
                List<String> memoryUsages = JvmTools.memoryUsage();
                for (String m : memoryUsages) {
                    fileOutput.write(m.getBytes(StandardCharsets.UTF_8));
                }

                // 获取使用率 如果超过了90%  该方法是 sun 的先不管
                if (JvmTools.memoryUsed() > 0.9) {
                    JvmTools.jMap(dumpPrefixName + "_dump_" + name + ".hprof", false);
                }
            } catch (Throwable t) {
                logger.error("Dump jvm info error: {}.", stackTrace(t));
            }
        }
    }
}
