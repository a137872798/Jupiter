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

import org.jupiter.common.util.JConstants;

/**
 * Task message dispatcher.
 *
 * jupiter
 * org.jupiter.common.concurrent.disruptor
 *
 * 消息分发处理器
 * @author jiachun.fjc
 */
public interface Dispatcher<T> {

    /**
     * ringBuffer 默认大小
     */
    int BUFFER_SIZE = 32768;
    /**
     * 工作线程数
     */
    int MAX_NUM_WORKERS = JConstants.AVAILABLE_PROCESSORS << 3;

    /**
     * Dispatch a task message.
     * 分发任务
     */
    boolean dispatch(T message);

    /**
     * Shutdown
     * 终止该分发对象
     */
    void shutdown();
}
