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
package org.jupiter.monitor;

import java.util.Map;

import org.jupiter.common.util.Maps;
import org.jupiter.monitor.handler.AddressHandler;
import org.jupiter.monitor.handler.AuthHandler;
import org.jupiter.monitor.handler.ByAddressHandler;
import org.jupiter.monitor.handler.ByServiceHandler;
import org.jupiter.monitor.handler.CommandHandler;
import org.jupiter.monitor.handler.HelpHandler;
import org.jupiter.monitor.handler.JStackHandler;
import org.jupiter.monitor.handler.LsHandler;
import org.jupiter.monitor.handler.MemoryUsageHandler;
import org.jupiter.monitor.handler.MetricsHandler;
import org.jupiter.monitor.handler.QuitHandler;
import org.jupiter.monitor.handler.RegistryHandler;

/**
 * Monitor command.
 *
 * jupiter
 * org.jupiter.monitor
 *
 * 监控模块的命令
 * @author jiachun.fjc
 */
public enum Command {
    // 这里定义了命令的描述信息以及对应的处理器
    AUTH("Login with password", new AuthHandler()),
    HELP("Help information", new HelpHandler()),
    STACK("Prints java stack traces of java threads for the current java process", new JStackHandler()),
    MEMORY_USAGE("Prints memory usage for the current java process", new MemoryUsageHandler()),
    LS("List all provider and consumer info", new LsHandler()),
    METRICS("Performance metrics", new MetricsHandler(),
            ChildCommand.REPORT),
    REGISTRY("Registry info(P/S command must follow behind ADDRESS)", new RegistryHandler(),
            ChildCommand.ADDRESS,
            ChildCommand.P,
            ChildCommand.S,
            ChildCommand.BY_SERVICE,
            ChildCommand.BY_ADDRESS,
            ChildCommand.GREP),
    QUIT("Quit monitor", new QuitHandler());

    /**
     * 描述信息
     */
    private final String description;
    /**
     * 对应的命令处理器
     */
    private final CommandHandler handler;
    /**
     * 每个命令 可能会包含一些子命令  比如 ADDRESS 命令依赖于 REGISTRY 命令
     */
    private final ChildCommand[] children;

    Command(String description, CommandHandler handler, ChildCommand... children) {
        this.description = description;
        this.handler = handler;
        this.children = children;
    }

    public String description() {
        return description;
    }

    public CommandHandler handler() {
        return handler;
    }

    public ChildCommand[] children() {
        return children;
    }

    /**
     * 查找该command 下 指定的childCommand
     * @param childName
     * @return
     */
    public ChildCommand parseChild(String childName) {
        if (childName.indexOf('-') == 0) {
            childName = childName.substring(1);
        }
        for (ChildCommand c : children()) {
            if (c.name().equalsIgnoreCase(childName)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 通过名字查找对应的命令
     * @param name
     * @return
     */
    public static Command parse(String name) {
        return commands.get(name.toLowerCase());
    }

    /**
     * 缓存了所有 command  key 是 Command.name()
     */
    private static final Map<String, Command> commands = Maps.newHashMap();

    static {
        for (Command c : Command.values()) {
            commands.put(c.name().toLowerCase(), c);
        }
    }

    public enum ChildCommand {
        REPORT("Report the current values of all metrics in the registry", null),
        ADDRESS("List all publisher/subscriber's addresses", new AddressHandler()),
        BY_SERVICE("List all providers by service name", new ByServiceHandler()),
        BY_ADDRESS("List all services by addresses", new ByAddressHandler()),
        P("Publisher", null),
        S("Subscriber", null),
        GREP("Search for pattern in each line", null);

        private final String description;
        private final CommandHandler handler;

        ChildCommand(String description, CommandHandler handler) {
            this.description = description;
            this.handler = handler;
        }

        public String description() {
            return description;
        }

        public CommandHandler handler() {
            return handler;
        }
    }
}
