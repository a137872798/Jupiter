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
package org.jupiter.tracing;

import java.util.Iterator;

import io.opentracing.Tracer;
import io.opentracing.noop.NoopTracerFactory;

import org.jupiter.common.util.JServiceLoader;
import org.jupiter.common.util.StackTraceUtil;
import org.jupiter.common.util.internal.logging.InternalLogger;
import org.jupiter.common.util.internal.logging.InternalLoggerFactory;

/**
 *
 * jupiter
 * org.jupiter.tracing
 *
 * 链路追踪工厂  这里借助第三方的框架 而不是自身实现
 * @author jiachun.fjc
 */
public interface TracerFactory {

    /**
     * 默认的链路工厂
     */
    TracerFactory DEFAULT = new DefaultTracerFactory();

    /**
     * Get a {@link Tracer} implementation.
     * 获取链路对象
     */
    Tracer getTracer();

    /**
     * 默认实现类 该工厂可以生成链路对象
     */
    class DefaultTracerFactory implements TracerFactory {

        private static final InternalLogger logger = InternalLoggerFactory.getInstance(DefaultTracerFactory.class);

        private static Tracer tracer = loadTracer();

        /**
         * 加载链路对象
         * @return
         */
        private static Tracer loadTracer() {
            try {
                // 通过SPI 加载实现类
                Iterator<Tracer> implementations = JServiceLoader.load(Tracer.class).iterator();
                if (implementations.hasNext()) {
                    Tracer first = implementations.next();
                    // 只有一个实现类 直接返回
                    if (!implementations.hasNext()) {
                        return first;
                    }

                    logger.warn("More than one tracer is found, NoopTracer will be used as default.");

                    // 如果存在多个 则使用noop 的 链路对象
                    return NoopTracerFactory.create();
                }
            } catch (Throwable t) {
                logger.error("Load tracer failed: {}.", StackTraceUtil.stackTrace(t));
            }
            return NoopTracerFactory.create();
        }

        @Override
        public Tracer getTracer() {
            return tracer;
        }
    }
}
