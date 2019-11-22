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
package org.jupiter.rpc;

/**
 *
 * jupiter
 * org.jupiter.rpc
 *
 * invoker调用时的过滤链   看来链表的职能是通过chain 实现的 而每个chain 对象绑定一个filter对象
 * @author jiachun.fjc
 */
public interface JFilterChain {

    /**
     * 获取过滤器对象
     * @return
     */
    JFilter getFilter();

    /**
     * 获取下一个节点
     * @return
     */
    JFilterChain getNext();

    <T extends JFilterContext> void doFilter(JRequest request, T filterCtx) throws Throwable;
}
