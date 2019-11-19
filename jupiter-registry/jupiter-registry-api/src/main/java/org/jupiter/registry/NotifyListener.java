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
package org.jupiter.registry;

/**
 * Service subscribers listener.
 *
 * jupiter
 * org.jupiter.registry
 *
 * @author jiachun.fjc
 */
public interface NotifyListener {

    /**
     * 通知到某个注册者
     * @param registerMeta
     * @param event
     */
    void notify(RegisterMeta registerMeta, NotifyEvent event);

    /**
     * 注册中心的事件监听对象 每当某个 添加/删除服务提供者
     */
    enum NotifyEvent {
        CHILD_ADDED,
        CHILD_REMOVED
    }
}
